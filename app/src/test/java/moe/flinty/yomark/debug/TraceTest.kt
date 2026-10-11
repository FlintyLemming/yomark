package moe.flinty.yomark.debug

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 调试信息的记录：挂在协程上下文里才记，按环节排，只留最近几次，异常记下后照样抛。 */
@RunWith(RobolectricTestRunner::class)
class TraceTest {

    private var time = 1_000L
    private val log = TraceLog(capacity = 3, clock = { time })

    private fun section(stage: TraceStage, title: String) = TraceSection(stage, title, "")

    @Test fun nothingIsRecordedWithoutARecorderInTheContext() = runTest {
        assertThat(currentTrace()).isNull()
    }

    @Test fun theRecorderTravelsWithTheCoroutine() = runTest {
        val recorder = log.start("识别")
        withContext(recorder) { currentTrace()?.add(section(TraceStage.TEXT, "文字识别")) }
        assertThat(log.runs.value.single().sections.map { it.title }).containsExactly("文字识别")
    }

    /** 文字识别和人脸检测并行跑，谁先记下来不一定：按环节排，同一环节里按先后。 */
    @Test fun sectionsAreOrderedByStage() {
        val recorder = log.start("识别")
        recorder.add(section(TraceStage.RESULT, "结果"))
        recorder.add(section(TraceStage.REGION, "人脸"))
        recorder.add(section(TraceStage.CLASSIFY, "第一段"))
        recorder.add(section(TraceStage.TEXT, "文字识别"))
        recorder.add(section(TraceStage.CLASSIFY, "第二段"))
        assertThat(log.runs.value.single().sections.map { it.title })
            .containsExactly("文字识别", "人脸", "第一段", "第二段", "结果").inOrder()
    }

    @Test fun onlyTheLatestFewRunsAreKeptNewestFirst() {
        repeat(5) { log.start("识别") }
        assertThat(log.runs.value.map { it.number }).containsExactly(5, 4, 3).inOrder()
    }

    @Test fun finishingRecordsTheDuration() {
        val recorder = log.start("识别")
        assertThat(log.runs.value.single().subheading).endsWith("进行中…")
        time += 2_340
        recorder.finish()
        assertThat(log.runs.value.single().durationMs).isEqualTo(2_340)
        assertThat(log.runs.value.single().subheading).endsWith("共 2.3 s")
    }

    @Test fun clearingDropsEverything() {
        val recorder = log.start("识别")
        log.clear()
        recorder.add(section(TraceStage.TEXT, "文字识别"))     // 清空之后还在跑的那次，记了也没处放
        assertThat(log.runs.value).isEmpty()
    }

    @Test fun aStepRecordsItsTimeAndWhatItProduced() = runTest {
        val recorder = log.start("识别")
        val out = recorder.step(TraceStage.TEXT, "文字识别", { time += 40; listOf("a", "b") }) { r, ms ->
            "$ms ms · ${r.size} 行" to r
        }
        assertThat(out).containsExactly("a", "b")
        val s = log.runs.value.single().sections.single()
        assertThat(s.summary).isEqualTo("40 ms · 2 行")
        assertThat(s.lines).containsExactly("a", "b")
    }

    /** 引擎会把异常吞掉降级成空结果：在吞之前记下来，再原样抛出去。 */
    @Test fun aFailingStepIsRecordedAndStillThrows() = runTest {
        val recorder = log.start("识别")
        val thrown = runCatching {
            recorder.step<Unit>(TraceStage.TEXT, "文字识别", { error("no ocr here") }) { _, _ -> "" to emptyList() }
        }.exceptionOrNull()
        assertThat(thrown).hasMessageThat().isEqualTo("no ocr here")
        val s = log.runs.value.single().sections.single()
        assertThat(s.summary).contains("出错：IllegalStateException：no ocr here")
        assertThat(s.blocks.single().label).isEqualTo("堆栈")
    }

    /** 换了图被取消不算出错，不记。 */
    @Test fun aCancelledStepIsNotRecorded() = runTest {
        val recorder = log.start("识别")
        val thrown = runCatching {
            recorder.step<Unit>(TraceStage.TEXT, "文字识别", { throw CancellationException("换图了") }) { _, _ -> "" to emptyList() }
        }.exceptionOrNull()
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        assertThat(log.runs.value.single().sections).isEmpty()
    }

    /** 写记录出了错不能连累识别：结果照样交回去。 */
    @Test fun aBrokenDescriptionDoesNotLoseTheResult() = runTest {
        val recorder = log.start("识别")
        val out = recorder.step(TraceStage.TEXT, "文字识别", { 42 }) { _, _ -> error("bad format") }
        assertThat(out).isEqualTo(42)
        assertThat(log.runs.value.single().sections.single().summary).contains("记录出错")
    }

    @Test fun theTextExportHasEverySectionLineAndBlock() {
        val recorder = log.start("AI 增强识别")
        recorder.add(
            TraceSection(
                TraceStage.CLASSIFY, "Gemini Nano · 第 1/1 段", "2.1 s · 解析出 1 处",
                lines = listOf("第 3 行  人名  「张三」"),
                blocks = listOf(TraceBlock("提示词", "第一行\n第二行"), TraceBlock("模型的回答", "1|人名|张三")),
            )
        )
        val text = log.runs.value.single().toText()
        assertThat(text).contains("== AI 增强识别 #1 · ")
        assertThat(text).contains("[Gemini Nano · 第 1/1 段] 2.1 s · 解析出 1 处")
        assertThat(text).contains("  第 3 行  人名  「张三」")
        assertThat(text).contains("  --- 提示词 ---\n  第一行\n  第二行\n")
        assertThat(text).contains("  --- 模型的回答 ---\n  1|人名|张三\n")
    }

    @Test fun durationsReadNaturally() {
        assertThat(formatDuration(35)).isEqualTo("35 ms")
        assertThat(formatDuration(1_840)).isEqualTo("1.8 s")
    }

    // ---------- 框里压着的字 ----------

    /** 每个字一个框（PP-OCR 的样子）：「收货人：张三」，字宽 10。按像素补的空格没有框。 */
    private fun line(text: String, y: Float = 0f) = TextLine(
        quad = rect(0f, y, text.length * 10f, y + 10f),
        text = text,
        confidence = 0.98f,
        elements = text.indices.filter { text[it] != ' ' }
            .map { i -> TextElement(rect(i * 10f, y, i * 10f + 10f, y + 10f), text[i].toString(), i..i) },
    )

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    @Test fun textUnderABoxIsTheCharactersItCovers() {
        assertThat(textUnder(rect(40f, 0f, 60f, 10f), listOf(line("收货人：张三")))).isEqualTo("张三")
    }

    @Test fun textUnderKeepsTheSpacesBetween() {
        assertThat(textUnder(rect(0f, 0f, 50f, 10f), listOf(line("ab cd")))).isEqualTo("ab cd")
    }

    @Test fun textUnderJoinsLines() {
        val lines = listOf(line("张三"), line("李四", y = 20f), line("王五", y = 40f))
        assertThat(textUnder(rect(0f, 0f, 20f, 30f), lines)).isEqualTo("张三 / 李四")
    }

    @Test fun aFaceHasNoTextUnderIt() {
        assertThat(textUnder(rect(200f, 200f, 300f, 300f), listOf(line("张三")))).isEmpty()
    }

    @Test fun aLineDescribesItsConfidenceBoxAndText() {
        assertThat(describeLine(2, line("收货人：张三", y = 40f))).isEqualTo("  3  0.98  0,40 60×10  收货人：张三")
    }

    @Test fun aQuadDescribesItsBounds() {
        val tilted = Quad(PointF(10f, 20f), PointF(110f, 25f), PointF(108f, 55f), PointF(8f, 50f))
        assertThat(tilted.describe()).isEqualTo("8,20 102×35")
    }
}
