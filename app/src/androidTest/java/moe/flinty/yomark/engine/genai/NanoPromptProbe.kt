package moe.flinty.yomark.engine.genai

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.prompt.Candidate.FinishReason
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.PromptPrefix
import com.google.mlkit.genai.prompt.SystemInstruction
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.aiKinds
import moe.flinty.yomark.engine.aiOffRules
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 在真机的 Gemini Nano 上批量跑一页页文字，给调提示词用（tools/model-eval/nano_eval.py 出题、打分）。不是测试，平时跳过。
 *
 * 只能在支持 AICore 的真机上跑（模拟器没有 Nano）。AICore 只给前台应用推理，所以先开一个空白页顶在前台。
 * 进出的文件放在本应用私有目录的 files/nano-probe 下，debug 包用 run-as 读写，nano_eval.py 包好了这几步：
 *
 *     adb shell am instrument -w -e class moe.flinty.yomark.engine.genai.NanoPromptProbe \
 *         -e nanoIn in.jsonl -e nanoOut out.jsonl [-e nanoMode app] moe.flinty.yomark.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 两种跑法，结果每答完一条写一行，跑到一半断了也留得下前面的；第一行是本机 Nano 的版本和能力：
 * - `-e nanoMode app`：输入每行 {"id", "lines": [...]}，原样跑一遍 GeminiNanoClassifier（问出厂设置下开着的那几类，
 *   关掉的类型不认，每段限时 20 秒、超时当没回答），输出 {"id", "chunks", "findings"}。量的就是 app 里的提示词和解析。
 *   超时的那段在 chunks 里没有。
 * - 不给 nanoMode：输入每行 {"id", "prompt"}，可选 "system"（走系统指令）、"prefix"（走前缀缓存）、"thinking"，
 *   原样问一句，输出 {"id", "reply", "truncated", "ms", "tokens"}。改提示词之前先拿草稿这样比。
 *
 * 推理参数和 app 一样（GeminiNanoClassifier 的 askNano）：温度 0、topK 1；输出上限可用 -e nanoMaxTokens 改。
 * 出错的写 {"id", "error"}（app 模式写在那一段里），内容是错误码和消息；重试了几次都不行的是最后一次的错。
 * BUSY、BACKGROUND_USE_BLOCKED 会退避重试，好让一轮跑得完（app 不重试，一出错整次识别就报错），
 * 重试过才答上的记下第几次（"tries"）。
 */
@RunWith(AndroidJUnit4::class)
class NanoPromptProbe {

    private val args = InstrumentationRegistry.getArguments()
    private var refront: () -> Unit = {}

    @Test
    fun run() {
        val input = args.getString("nanoIn")
        assumeTrue("没给 -e nanoIn，跳过", input != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "nano-probe")
        val out = File(dir, args.getString("nanoOut") ?: "replies.jsonl")
        val maxTokens = args.getString("nanoMaxTokens")?.toInt() ?: 384
        val appMode = args.getString("nanoMode") == "app"

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            // 跑一轮要几十分钟：屏幕不能熄、锁屏也要顶在最前面，不然 AICore 当它在后台，一律报 BACKGROUND_USE_BLOCKED
            scenario.onActivity { a ->
                a.setShowWhenLocked(true)
                a.setTurnScreenOn(true)
                a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            refront = { runCatching { scenario.moveToState(Lifecycle.State.RESUMED) } }
            runBlocking {
                val model = Generation.getClient()
                out.writeText("")
                val meta = JSONObject().put("id", "_meta")
                    .put("model", model.getBaseModelName())
                    .put("tokenLimit", runCatching { model.getTokenLimit() }.getOrNull())
                    .put("system", runCatching { model.isSystemPromptAvailable() }.getOrNull())
                    .put("caching", runCatching { model.isCachingFeatureAvailable() }.getOrNull())
                    .put("thinking", runCatching { model.isThinkingModeAvailable() }.getOrNull())
                    .put("structured", runCatching { model.isStructuredOutputFeatureAvailable() }.getOrNull())
                out.appendText(meta.toString() + "\n")
                File(dir, input!!).readLines().filter { it.isNotBlank() }.forEach { row ->
                    val job = JSONObject(row)
                    val id = job.getString("id")
                    val result = JSONObject().put("id", id)
                    if (appMode) {
                        // 照 app 原样跑真的 GeminiNanoClassifier：送行、切段、解析、关掉的类型不认、几段合起来去重、20 秒超时，
                        // 问出厂设置下开着的那几类。只把问模型那一步换成这里会退避重试的 ask
                        val lines = job.getJSONArray("lines").let { a -> List(a.length()) { a.getString(it) } }
                        val config = RecognitionConfig()
                        val replies = org.json.JSONArray()
                        val classifier = GeminiNanoClassifier(aiKinds(config), aiOffRules(config)) { prompt ->
                            val r = ask(model, prompt, maxTokens)
                            replies.put(r)
                            NanoReply(r.optString("reply").takeIf { r.has("reply") && !r.isNull("reply") }, r.optBoolean("truncated"))
                        }
                        val findings = org.json.JSONArray()
                        classifier.classify(lines.mapIndexed(::fakeLine)).forEach { c ->
                            val box = c.quad.bounds()
                            val line = (box.top / ROW).roundToInt()
                            val start = (box.left / CHAR).roundToInt()
                            val end = (box.right / CHAR).roundToInt()
                            findings.put(
                                JSONObject().put("line", line).put("kind", c.kind.name).put("start", start).put("end", end)
                                    .put("text", lines[line].substring(start, end))
                            )
                        }
                        result.put("chunks", replies).put("findings", findings)
                    } else {
                        val r = ask(
                            model, job.getString("prompt"), maxTokens,
                            system = job.optString("system").takeIf { it.isNotEmpty() },
                            prefix = job.optString("prefix").takeIf { it.isNotEmpty() },
                            thinking = job.optBoolean("thinking"),
                        )
                        r.keys().forEach { k -> result.put(k, r.get(k)) }
                    }
                    out.appendText(result.toString() + "\n")
                }
            }
        }
    }

    /**
     * 问一句。[system] 走系统指令，[prefix] 走前缀缓存（同一前缀第二次起只算后面的部分），都是可选的。
     * AICore 忙（BUSY）或当我们在后台（BACKGROUND_USE_BLOCKED）时退避重试，最多 [TRIES] 次，都不行记最后一次的错；
     * 别的错原样记下。ms 只算答上的那一次。
     */
    private suspend fun ask(
        model: GenerativeModel,
        prompt: String,
        maxTokens: Int,
        system: String? = null,
        prefix: String? = null,
        thinking: Boolean = false,
    ): JSONObject {
        val configure: GenerateContentRequest.Builder.() -> Unit = {
            temperature = 0f
            topK = 1
            maxOutputTokens = maxTokens
            if (prefix != null) promptPrefix = PromptPrefix(prefix)
            if (thinking) enableThinking = true
        }
        val request = if (system != null) generateContentRequest(SystemInstruction(system), TextPart(prompt), configure)
        else generateContentRequest(TextPart(prompt), configure)
        val tokens = runCatching { model.countTokens(request).totalTokens }.getOrNull()
        var wait = 2_000L
        var last = ""
        for (attempt in 1..TRIES) {
            val started = System.currentTimeMillis()
            try {
                val answer = model.generateContent(request).candidates.firstOrNull()
                val r = JSONObject()
                    .put("reply", answer?.text ?: JSONObject.NULL)
                    .put("truncated", answer?.finishReason == FinishReason.MAX_TOKENS)
                    .put("ms", System.currentTimeMillis() - started)
                    .put("tokens", tokens)
                return if (attempt > 1) r.put("tries", attempt) else r
            } catch (e: GenAiException) {
                last = "${e.errorCode}: ${e.message}"
                when (e.errorCode) {
                    GenAiException.ErrorCode.BUSY -> Unit
                    // 有人动了手机、切走了：把空白页拉回前面再试
                    GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> refront()
                    else -> return JSONObject().put("error", last)
                }
                if (attempt < TRIES) {
                    delay(wait)
                    wait *= 2
                }
            } catch (e: Exception) {
                return JSONObject().put("error", e.toString())
            }
        }
        return JSONObject().put("error", "$last (after $TRIES tries)")
    }

    /** 第 [index] 行的假识别结果：一个字 [CHAR] 像素宽、一行 [ROW] 像素高，候选框的坐标除回去就是行号和字的下标。 */
    private fun fakeLine(index: Int, text: String): TextLine {
        val top = index * ROW
        fun box(from: Int, to: Int) = Quad.fromRect(RectF(from * CHAR, top, to * CHAR, top + CHAR))
        return TextLine(box(0, text.length), text, 1f, text.indices.map { TextElement(box(it, it + 1), text[it].toString(), it..it) })
    }

    private companion object {
        const val TRIES = 6
        const val CHAR = 10f
        const val ROW = 20f
    }
}
