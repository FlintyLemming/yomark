package moe.flinty.yomark.engine.genai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate.FinishReason
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKindLabels
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceBlock
import moe.flinty.yomark.debug.TraceRecorder
import moe.flinty.yomark.debug.TraceSection
import moe.flinty.yomark.debug.TraceStage
import moe.flinty.yomark.debug.currentTrace
import moe.flinty.yomark.debug.describe
import moe.flinty.yomark.debug.formatDuration
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.rules.RuleClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Gemini Nano 语义判定（spec §14「Gemini Nano 语义判定 | SensitivityClassifier」那一行）。
 *
 * 规则认的是形状：「收货人」后面、电话前面、门牌号那样的串。认不出的是**语义**——
 * 聊天里随口提到的「我住在XX小区」「让张伟来拿」。这一层把 OCR 出来的整页文字
 * 交给设备上的 Gemini Nano（ML Kit GenAI Prompt API，经系统的 AICore 服务），
 * 让它把规则漏掉的人名、地址指出来。
 *
 * - **全程在本机**：推理在 AICore 里跑，本应用不联网、不加 INTERNET 权限；
 * - **找到的直接打码**（2026-10-14 起没有「圈出」）：是用户点了才跑的，模型猜错的点一下就拿掉，整批不要撤销一下；
 *   宽屏列表上标「AI」，与规则命中区分开（spec §3 DetectorSource 的本意）；
 * - **只加不减**：它不能撤销规则的结果。漏检是事故，让模型否决规则等于让它制造漏检；
 * - **不支持就跳过**：只有 AICore 支持的机型（Pixel 9 及以后等）有 Nano，
 *   isAvailable() 探测不到就整层不跑，其余识别照常。
 *
 * 调试信息开着时（见 debug/Trace.kt），每一段送了什么提示词、模型原样回了什么、解析出哪几处都记下来。
 */
class GeminiNanoClassifier(
    /**
     * 问模型一句、拿回它的回答。默认是本机的 Gemini Nano；拆成参数是为了单测——
     * ML Kit 的 GenerativeModel 在 JVM 上造不出来。
     */
    private val ask: suspend (prompt: String) -> NanoReply = ::askNano,
) : SensitivityClassifier {

    override val id = "gemini-nano"

    override suspend fun isAvailable(): Boolean = NanoClient.ready()

    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
        val trace = currentTrace()
        val texts = lines.map { l -> l.text.takeIf { l.confidence >= RuleClassifier.MIN_LINE_CONFIDENCE && it.hasWords() } }
        val chunks = SemanticPrompt.chunks(texts)
        trace?.add(
            TraceSection(
                TraceStage.CLASSIFY,
                "Gemini Nano · 送去的文字",
                "${lines.size} 行里送 ${texts.count { it != null }} 行，分 ${chunks.size} 段" +
                    "（置信度不到 ${RuleClassifier.MIN_LINE_CONFIDENCE}、不到两个字母或汉字的行不送）",
            )
        )
        // 合并时同规则的候选一样按各自的阅读方向量：截图里转过的证件照上两行地址不拼成一块
        val directions by lazy { RuleClassifier.readingDegrees(lines) }
        return chunks.flatMapIndexed { n, chunk ->
            val started = trace?.now() ?: 0L
            val reply = try {
                withTimeoutOrNull(TIMEOUT_MS) { ask(chunk.prompt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace?.recordExchange(n, chunks.size, chunk, lines, started, reply = null, error = e)
                throw e
            }
            val findings = reply?.text?.let { SemanticPrompt.parse(it, chunk, lines.map { l -> l.text }) }.orEmpty()
            trace?.recordExchange(n, chunks.size, chunk, lines, started, reply, findings = findings)
            findings.map { f ->
                Candidate(
                    id = "llm-${f.lineIndex}-${f.range.first}-${f.kind}",
                    quad = lines[f.lineIndex].quadForRange(f.range),
                    kind = f.kind,
                    source = DetectorSource.LLM,
                    confidence = CONFIDENCE,
                    readingDegrees = directions[f.lineIndex],
                )
            }
        }
    }

    /** 至少两个汉字或字母，纯数字、纯符号的行交给规则就够了。 */
    private fun String.hasWords() = count { it.isLetter() } >= 2

    /**
     * 一段的来回记成一节：送了几行、用了多久、结果如何；解析出的每一处写明是 OCR 的第几行（与「文字识别」那一节的行号一致）
     * 和提示词里的第几行（模型回答里的行号）。提示词和回答原样收在下面。[reply] 为 null 而没有 [error] 是超时了。
     */
    private fun TraceRecorder.recordExchange(
        index: Int,
        total: Int,
        chunk: SemanticPrompt.Chunk,
        lines: List<TextLine>,
        started: Long,
        reply: NanoReply?,
        error: Throwable? = null,
        findings: List<SemanticPrompt.Finding> = emptyList(),
    ) {
        val outcome = when {
            error != null -> "出错：${error.describe()}"
            reply == null -> "超时（${TIMEOUT_MS / 1000} 秒没有回答）"
            reply.text == null -> "模型没有给出回答"
            else -> "解析出 ${findings.size} 处" +
                if (reply.truncated) "，回答写满 $MAX_OUTPUT_TOKENS token 被截断" else ""
        }
        add(
            TraceSection(
                stage = TraceStage.CLASSIFY,
                title = "Gemini Nano · 第 ${index + 1}/$total 段",
                summary = "${formatDuration(now() - started)} · 送了 ${chunk.lineIndices.size} 行 · $outcome",
                lines = findings.map { f ->
                    val quote = lines[f.lineIndex].text.substring(f.range.first, f.range.last + 1)
                    "第 ${f.lineIndex + 1} 行（提示词里第 ${chunk.lineIndices.indexOf(f.lineIndex) + 1} 行）" +
                        "  ${SensitiveKindLabels.debugName(f.kind)}  「$quote」"
                },
                blocks = listOfNotNull(
                    TraceBlock("提示词", chunk.prompt),
                    reply?.text?.let { TraceBlock("模型的回答", it) },
                    error?.let { TraceBlock("堆栈", it.stackTraceToString()) },
                ),
            )
        )
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val CONFIDENCE = 0.5f
    }
}

/** 模型的一次回答。[text] 为 null 是它什么也没给；[truncated] 是写满了输出上限被截断，后面几条可能没写完。 */
class NanoReply(val text: String?, val truncated: Boolean = false)

/** 一段回答的输出上限。写满了回答会被截断，调试信息里会标出来。 */
private const val MAX_OUTPUT_TOKENS = 384

/** 问本机的 Gemini Nano。温度 0、只取最可能的那个词：同一页文字每次都给同一个回答。 */
private suspend fun askNano(prompt: String): NanoReply {
    val answer = NanoClient.model.generateContent(
        generateContentRequest(TextPart(prompt)) {
            temperature = 0f
            topK = 1
            maxOutputTokens = MAX_OUTPUT_TOKENS
        }
    ).candidates.firstOrNull()
    return NanoReply(answer?.text, truncated = answer?.finishReason == FinishReason.MAX_TOKENS)
}

/**
 * 进程内共用一个 Nano 客户端：它绑着 AICore 的服务连接，
 * 配置一变 buildEngine 就会重建引擎，跟着重建没有意义。
 */
object NanoClient {

    val model: GenerativeModel by lazy { Generation.getClient() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var downloadStarted = false

    /**
     * 能不能用。机型不支持时 ML Kit 会抛异常，一律当不可用。
     *
     * 模型还没下到设备上（DOWNLOADABLE）时请 AICore 去下，这一次先跳过。
     * 下载是系统的 AICore 服务做的，不是本应用发起的网络请求。
     *
     * 调试信息开着时另问一遍本机 Nano 的情况、连版本一起记下来：「AI 增强识别」按钮没出现，多半就是这里不行。
     */
    suspend fun ready(): Boolean {
        currentTrace()?.add(TraceSection(TraceStage.CLASSIFY, "Gemini Nano · 本机情况", status().describe()))
        return runCatching {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE -> true
                FeatureStatus.DOWNLOADABLE -> { startDownload(); false }
                else -> false
            }
        }.getOrDefault(false)
    }

    /** 本机 Gemini Nano 的情况，设置的「AI」页用。只看不下，见 [NanoStatus]。 */
    suspend fun status(): NanoStatus = NanoStatus.probe({ model.checkStatus() }, { model.getBaseModelName() })

    private fun startDownload() {
        if (downloadStarted) return
        downloadStarted = true
        // 失败了就允许下次再请求一遍
        scope.launch { runCatching { model.download().collect { } }.onFailure { downloadStarted = false } }
    }
}

/** 调试信息里的一句话。设置「AI」页的措辞在 nanoSummary，那边面向用户，这边面向排查。 */
private fun NanoStatus.describe(): String = when (this) {
    is NanoStatus.Unavailable -> "不可用" + (errorCode?.let { "（AICore 错误码 $it）" } ?: "")
    is NanoStatus.Present -> (version ?: "版本未知") + " · " + when (model) {
        NanoStatus.Model.READY -> "已就绪"
        NanoStatus.Model.DOWNLOADING -> "正在下载"
        NanoStatus.Model.NOT_DOWNLOADED -> "未下载（这一次跳过，已请 AICore 去下载）"
    }
}
