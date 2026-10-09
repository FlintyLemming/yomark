package moe.flinty.yomark.engine.genai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.rules.RuleClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gemini Nano 语义判定（spec §14「Gemini Nano 语义判定 | SensitivityClassifier」那一行）。
 *
 * 规则认的是形状：「收货人」后面、电话前面、门牌号那样的串。认不出的是**语义**——
 * 聊天里随口提到的「我住在XX小区」「让张伟来拿」。这一层把 OCR 出来的整页文字
 * 交给设备上的 Gemini Nano（ML Kit GenAI Prompt API，经系统的 AICore 服务），
 * 让它把规则漏掉的人名、地址指出来。
 *
 * - **全程在本机**：推理在 AICore 里跑，本应用不联网、不加 INTERNET 权限；
 * - **只圈出不打码**：模型会猜错，按 §6「按误报率划线」归到仅圈出，导出拦截兜底；
 *   UI 上标「AI」，与规则命中区分开（spec §3 DetectorSource 的本意）；
 * - **只加不减**：它不能撤销规则的结果。漏检是事故，让模型否决规则等于让它制造漏检；
 * - **不支持就跳过**：只有 AICore 支持的机型（Pixel 9 及以后等）有 Nano，
 *   isAvailable() 探测不到就整层不跑，其余识别照常。
 */
class GeminiNanoClassifier(
    private val model: () -> GenerativeModel = { NanoClient.model },
) : SensitivityClassifier {

    override val id = "gemini-nano"

    override suspend fun isAvailable(): Boolean = NanoClient.ready()

    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
        val texts = lines.map { l -> l.text.takeIf { l.confidence >= RuleClassifier.MIN_LINE_CONFIDENCE && it.hasWords() } }
        return SemanticPrompt.chunks(texts).flatMap { chunk ->
            val reply = withTimeoutOrNull(TIMEOUT_MS) {
                model().generateContent(
                    generateContentRequest(TextPart(chunk.prompt)) {
                        temperature = 0f
                        topK = 1
                        maxOutputTokens = MAX_OUTPUT_TOKENS
                    }
                ).candidates.firstOrNull()?.text
            } ?: return@flatMap emptyList()
            SemanticPrompt.parse(reply, chunk, lines.map { it.text }).map { f ->
                Candidate(
                    id = "llm-${f.lineIndex}-${f.range.first}-${f.kind}",
                    quad = lines[f.lineIndex].quadForRange(f.range),
                    kind = f.kind,
                    source = DetectorSource.LLM,
                    confidence = CONFIDENCE,
                    enabledByDefault = false,
                )
            }
        }
    }

    /** 至少两个汉字或字母，纯数字、纯符号的行交给规则就够了。 */
    private fun String.hasWords() = count { it.isLetter() } >= 2

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val MAX_OUTPUT_TOKENS = 384
        const val CONFIDENCE = 0.5f
    }
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
     */
    suspend fun ready(): Boolean = runCatching {
        when (model.checkStatus()) {
            FeatureStatus.AVAILABLE -> true
            FeatureStatus.DOWNLOADABLE -> { startDownload(); false }
            else -> false
        }
    }.getOrDefault(false)

    /** 本机 Gemini Nano 的情况，设置的「AI」页用。只看不下，见 [NanoStatus]。 */
    suspend fun status(): NanoStatus = NanoStatus.probe({ model.checkStatus() }, { model.getBaseModelName() })

    private fun startDownload() {
        if (downloadStarted) return
        downloadStarted = true
        // 失败了就允许下次再请求一遍
        scope.launch { runCatching { model.download().collect { } }.onFailure { downloadStarted = false } }
    }
}
