package moe.flinty.yomark.engine.genai

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceLog
import moe.flinty.yomark.debug.TraceSection
import moe.flinty.yomark.rules.RuleCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AI 增强识别在调试信息里留下的东西：每一段送了什么提示词、模型原样回了什么、解析出哪几处，
 * 以及超时、截断、出错这几种「没有结果」各是哪一种。
 */
@RunWith(RobolectricTestRunner::class)
class GeminiNanoClassifierTraceTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    private fun line(text: String, y: Float, confidence: Float = 0.98f) = TextLine(
        rect(0f, y, text.length * 10f, y + 10f), text, confidence,
        text.indices.map { i -> TextElement(rect(i * 10f, y, i * 10f + 10f, y + 10f), text[i].toString(), i..i) },
    )

    private val lines = listOf(
        line("订单详情", 0f),
        line("<", 20f),                                        // 只有一个符号的行不送
        line("让张伟明天来拿", 40f),
    )

    private suspend fun traced(ask: suspend (String) -> NanoReply): Pair<List<TraceSection>, Int> {
        val log = TraceLog()
        val found = withContext(log.start("AI 增强识别")) { GeminiNanoClassifier(ask = ask).classify(lines) }
        return log.runs.value.single().sections to found.size
    }

    @Test fun withoutARecorderItJustClassifies() = runTest {
        val found = GeminiNanoClassifier { NanoReply("人名|张伟") }.classify(lines)
        assertThat(found.single().kind).isEqualTo(SensitiveKind.PERSON_NAME)
    }

    /** 纯数字的行也送：单独一行的取件码、手机号，模型看不到就认不出。 */
    @Test fun aLineOfDigitsIsSent() = runTest {
        var prompt = ""
        GeminiNanoClassifier { prompt = it; NanoReply("无") }.classify(listOf(line("取件码", 0f), line("3-2-1234", 20f)))
        assertThat(prompt).endsWith("1: 取件码\n2: 3-2-1234\n")
    }

    /** 只问用户开着的那几类：提示词里没有关掉的，回答里写了也不认。 */
    @Test fun onlyTheKindsItWasGivenAreAskedForAndTaken() = runTest {
        var prompt = ""
        val found = GeminiNanoClassifier(kinds = setOf(SensitiveKind.PHONE)) { prompt = it; NanoReply("人名|张伟") }.classify(lines)
        assertThat(found).isEmpty()
        assertThat(prompt).contains("电话：")
        assertThat(prompt).doesNotContain("人名：")
    }

    /** 关了电话：模型把手机号报成证件号，拿电话的规则认一遍，整段就是手机号的不认；真的证件号照认。 */
    @Test fun aKindSwitchedOffDoesNotComeBackUnderAnotherType() = runTest {
        val phone = RuleCatalog.all.first { it.kind == SensitiveKind.PHONE }
        val page = listOf(line("联系我 18910327755", 0f), line("身份证 110101199003071234", 20f))
        val found = GeminiNanoClassifier(kinds = SemanticPrompt.KINDS - SensitiveKind.PHONE, offRules = listOf(phone)) {
            NanoReply("证件号|18910327755\n证件号|110101199003071234")
        }.classify(page)
        assertThat(found.map { it.id }).containsExactly("llm-1-4-ID_NUMBER")
    }

    /** 日期、IP 出厂关着：下单时间报成证件号、网关报成证件号都不认；生日、长得像日期的取件码照认。 */
    @Test fun datesAndAddressesOfMachinesSwitchedOffStayOff() = runTest {
        val off = RuleCatalog.all.filter { it.kind == SensitiveKind.DATETIME || it.kind == SensitiveKind.IP_ADDR }
        val page = listOf(
            line("下单时间 2026-10-14 09:12:33", 0f), line("网关 192.168.31.1", 20f),
            line("生日 1990-03-07", 40f), line("取件码 3-2-2025", 60f),
        )
        val found = GeminiNanoClassifier(kinds = SemanticPrompt.KINDS - SensitiveKind.DATETIME - SensitiveKind.IP_ADDR, offRules = off) {
            NanoReply("证件号|2026-10-14 09:12:33\n证件号|192.168.31.1\n生日|1990-03-07\n取件码|3-2-2025")
        }.classify(page)
        assertThat(found.map { it.id }).containsExactly("llm-2-3-BIRTH_DATE", "llm-3-4-PICKUP_CODE")
    }

    @Test fun thePromptTheReplyAndWhatWasParsedAreRecorded() = runTest {
        val (sections, found) = traced { NanoReply("人名|张伟") }
        assertThat(found).isEqualTo(1)

        val sent = sections.first { it.title == "Gemini Nano · 送去的文字" }
        assertThat(sent.summary).startsWith("3 行里送 2 行，分 1 段")

        val exchange = sections.first { it.title == "Gemini Nano · 第 1/1 段" }
        assertThat(exchange.summary).contains("送了 2 行 · 解析出 1 处")
        // 与「文字识别」那一节的行号一致：OCR 的第 3 行
        assertThat(exchange.lines).containsExactly("第 3 行  人名  「张伟」")
        val prompt = exchange.blocks.first { it.label == "提示词" }.text
        assertThat(prompt).contains("1: 订单详情\n2: 让张伟明天来拿")
        assertThat(exchange.blocks.first { it.label == "模型的回答" }.text).isEqualTo("人名|张伟")
    }

    /** 回答里认不出、对不上原文的行被丢掉：解析出 0 处，但原样的回答还在，看得出模型说了什么。 */
    @Test fun aReplyThatParsesToNothingIsStillShownVerbatim() = runTest {
        val (sections, found) = traced { NanoReply("好的，我找到了：张伟") }
        assertThat(found).isEqualTo(0)
        val exchange = sections.first { it.title == "Gemini Nano · 第 1/1 段" }
        assertThat(exchange.summary).contains("解析出 0 处")
        assertThat(exchange.blocks.first { it.label == "模型的回答" }.text).isEqualTo("好的，我找到了：张伟")
    }

    @Test fun aTruncatedReplySaysSo() = runTest {
        val (sections, _) = traced { NanoReply("2|人名|张伟\n2|地址|", truncated = true) }
        assertThat(sections.first { it.title == "Gemini Nano · 第 1/1 段" }.summary).contains("被截断")
    }

    @Test fun aTimeoutSaysSo() = runTest {
        val (sections, found) = traced { delay(60_000); NanoReply("2|人名|张伟") }
        assertThat(found).isEqualTo(0)
        val exchange = sections.first { it.title == "Gemini Nano · 第 1/1 段" }
        assertThat(exchange.summary).contains("超时")
        assertThat(exchange.blocks.map { it.label }).containsExactly("提示词")
    }

    @Test fun noAnswerAtAllSaysSo() = runTest {
        val (sections, _) = traced { NanoReply(null) }
        assertThat(sections.first { it.title == "Gemini Nano · 第 1/1 段" }.summary).contains("模型没有给出回答")
    }

    /** AICore 抛的异常照旧往上抛（引擎据此报「增强识别失败」），调试信息里留着它和送出去的提示词。 */
    @Test fun aFailureIsRecordedAndStillThrown() = runTest {
        val log = TraceLog()
        val thrown = runCatching {
            withContext(log.start("AI 增强识别")) {
                GeminiNanoClassifier { error("AICore busy") }.classify(lines)
            }
        }.exceptionOrNull()
        assertThat(thrown).hasMessageThat().isEqualTo("AICore busy")
        val exchange = log.runs.value.single().sections.first { it.title == "Gemini Nano · 第 1/1 段" }
        assertThat(exchange.summary).contains("出错：IllegalStateException：AICore busy")
        assertThat(exchange.blocks.map { it.label }).containsExactly("提示词", "堆栈").inOrder()
    }
}
