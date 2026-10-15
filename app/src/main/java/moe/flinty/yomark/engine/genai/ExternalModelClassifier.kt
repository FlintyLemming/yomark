package moe.flinty.yomark.engine.genai

import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
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
import moe.flinty.yomark.rules.Rule
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * 实验室：AI 增强识别改用外部模型（OpenAI 兼容接口，见 [ExternalModelClient]）。
 * 设计见 docs/superpowers/specs/2026-10-15-yomark-lab-external-model-design.md。
 *
 * 和 [GeminiNanoClassifier] 一样只发 OCR 的文字、用同一套提示词和解析，原文由 PP-OCR 的字级框定位；
 * 不发截图——实测看图不多认出一处，模型自己给的框也框不住字。不同的是：
 * - **规则认出的号码先换成占位符**（〔电话〕〔证件号〕）再发：这些号码规则已经打上码了，不必离开手机，
 *   留个占位符模型照样拿它当线索（实测人名、地址的召回没有变差）。人名、地址、昵称不换，那正是要模型看的；
 * - 一段 6000 字，一页通常一次发完；
 * - **没答上来就是没跑成**：超时、连不上、接口报错都抛出去，编辑器显示「识别失败，重试」，
 *   不能让用户以为这一页已经被模型看过、是干净的。
 *
 * 只在用户点「AI 增强识别」时联网；[isAvailable] 只看设置填没填好，不发请求。
 */
class ExternalModelClassifier(
    /** 问哪几类，同 [GeminiNanoClassifier]。 */
    private val kinds: Set<SensitiveKind> = SemanticPrompt.KINDS,
    /** 用户关掉的那几类的规则，拿它们拦回答（见 [vetoBy]）。 */
    offRules: List<Rule> = emptyList(),
    /** 开着的那几类的规则：它们认出的号码发出去之前换成占位符。 */
    private val onRules: List<Rule> = emptyList(),
    /** 眼下的设置，每次用时现读：改了接口不用重建引擎。 */
    private val settings: suspend () -> ExternalModelSettings,
    /** 问模型一句、拿回正文。拆成参数是为了单测。 */
    private val ask: suspend (ExternalModelSettings, String) -> String = ExternalModelClient::ask,
) : SensitivityClassifier {

    override val id = "external-model"

    private val vetoed = vetoBy(offRules)

    override suspend fun isAvailable(): Boolean = settings().active

    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
        val trace = currentTrace()
        val s = settings()
        val sendable = reviewTexts(lines)
        val texts = SemanticPrompt.mask(sendable, ::numbersIn)
        val masked = texts.indices.sumOf { i -> texts[i].orEmpty().count { it == '〔' } - sendable[i].orEmpty().count { it == '〔' } }
        val chunks = SemanticPrompt.chunks(texts, kinds, CHUNK_CHARS, masked = true)
        trace?.add(
            TraceSection(
                TraceStage.CLASSIFY,
                "外部模型 · 送去的文字",
                "${s.model} @ ${s.host} · ${lines.size} 行里送 ${sendable.count { it != null }} 行，" +
                    "$masked 处号码换成了占位符，分 ${chunks.size} 段",
            )
        )
        val all = lines.map { it.text }
        val findings = chunks.flatMapIndexed { n, chunk ->
            val started = trace?.now() ?: 0L
            val reply = try {
                withTimeoutOrNull(TIMEOUT_MS) { ask(s, chunk.prompt) }
                    ?: throw ExternalModelException(ExternalModelException.Reason.TIMEOUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                trace?.recordExchange(n, chunks.size, chunk, lines, started, reply = null, error = e)
                throw e
            }
            val found = SemanticPrompt.parse(reply, all, kinds, vetoed)
            trace?.recordExchange(n, chunks.size, chunk, lines, started, reply, findings = found)
            found
        }.let(SemanticPrompt::collapse)
        return findings.toCandidates(lines)
    }

    /** 一行里开着的规则有把握认出的号码（不是要旁证的猜测、不是不打码的那一档）。人名、地址这几类不换。 */
    private fun numbersIn(text: String): List<Pair<IntRange, SensitiveKind>> = onRules
        .filter { it.kind !in SENT_AS_IS }
        .flatMap { rule ->
            runCatching { rule.findIn(text) }.getOrDefault(emptyList())
                .filter { !it.needsAnchor && !it.outlineOnly }
                .map { it.range to rule.kind }
        }

    /** 一段的来回记成一节，同 [GeminiNanoClassifier]。提示词里的号码已经换成了占位符；不记 key。 */
    private fun TraceRecorder.recordExchange(
        index: Int,
        total: Int,
        chunk: SemanticPrompt.Chunk,
        lines: List<TextLine>,
        started: Long,
        reply: String?,
        error: Throwable? = null,
        findings: List<SemanticPrompt.Finding> = emptyList(),
    ) {
        add(
            TraceSection(
                stage = TraceStage.CLASSIFY,
                title = "外部模型 · 第 ${index + 1}/$total 段",
                summary = "${formatDuration(now() - started)} · 送了 ${chunk.lineIndices.size} 行 · " +
                    if (error != null) "出错：${error.describe()}" else "解析出 ${findings.size} 处",
                lines = findings.map { f ->
                    val quote = lines[f.lineIndex].text.substring(f.range.first, f.range.last + 1)
                    "第 ${f.lineIndex + 1} 行  ${SensitiveKindLabels.debugName(f.kind)}  「$quote」"
                },
                blocks = listOfNotNull(
                    TraceBlock("提示词", chunk.prompt),
                    reply?.let { TraceBlock("模型的回答", it) },
                    error?.let { TraceBlock("堆栈", it.stackTraceToString()) },
                ),
            )
        )
    }

    private companion object {
        /** 连接、读各有自己的超时（见 ExternalModelClient），这是整段的上限。 */
        const val TIMEOUT_MS = 45_000L
        const val CHUNK_CHARS = 6000

        /** 发出去时不换成占位符的几类：模型要看的就是它们，日期时间是上下文。 */
        val SENT_AS_IS = setOf(
            SensitiveKind.PERSON_NAME, SensitiveKind.POSTAL_ADDRESS, SensitiveKind.ORG_NAME,
            SensitiveKind.CHAT_NAME, SensitiveKind.CHAT_TITLE, SensitiveKind.DATETIME,
        )
    }
}
