package moe.flinty.yomark.engine.genai

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceLog
import moe.flinty.yomark.debug.TraceSection
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
        line("12", 20f),                                       // 纯数字的行不送
        line("让张伟明天来拿", 40f),
    )

    private suspend fun traced(ask: suspend (String) -> NanoReply): Pair<List<TraceSection>, Int> {
        val log = TraceLog()
        val found = withContext(log.start("AI 增强识别")) { GeminiNanoClassifier(ask).classify(lines) }
        return log.runs.value.single().sections to found.size
    }

    @Test fun withoutARecorderItJustClassifies() = runTest {
        val found = GeminiNanoClassifier { NanoReply("2|人名|张伟") }.classify(lines)
        assertThat(found.single().kind).isEqualTo(SensitiveKind.PERSON_NAME)
    }

    @Test fun thePromptTheReplyAndWhatWasParsedAreRecorded() = runTest {
        val (sections, found) = traced { NanoReply("2|人名|张伟") }
        assertThat(found).isEqualTo(1)

        val sent = sections.first { it.title == "Gemini Nano · 送去的文字" }
        assertThat(sent.summary).startsWith("3 行里送 2 行，分 1 段")

        val exchange = sections.first { it.title == "Gemini Nano · 第 1/1 段" }
        assertThat(exchange.summary).contains("送了 2 行 · 解析出 1 处")
        // OCR 的第 3 行，在提示词里是第 2 行（第 2 行纯数字没送）
        assertThat(exchange.lines).containsExactly("第 3 行（提示词里第 2 行）  人名  「张伟」")
        val prompt = exchange.blocks.first { it.label == "提示词" }.text
        assertThat(prompt).contains("1: 订单详情\n2: 让张伟明天来拿")
        assertThat(exchange.blocks.first { it.label == "模型的回答" }.text).isEqualTo("2|人名|张伟")
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
