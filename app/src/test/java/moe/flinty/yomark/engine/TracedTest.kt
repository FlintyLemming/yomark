package moe.flinty.yomark.engine

import android.graphics.Bitmap
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceLog
import moe.flinty.yomark.debug.TraceStage
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 识别各环节套的那一层（buildEngine 里的 traced()）：挂着记录器时记下每一环的输出，
 * 包括引擎吞掉的异常；没挂时原样转发。
 */
@RunWith(RobolectricTestRunner::class)
class TracedTest {

    private val image = SourceImage(Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888), 1f, 200, 100, "image/png")

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    private val line = TextLine(
        rect(0f, 0f, 60f, 10f), "电话：1234", 0.97f,
        "电话：1234".indices.map { i -> TextElement(rect(i * 10f, 0f, i * 10f + 10f, 10f), "电话：1234"[i].toString(), i..i) },
    )

    private class Recognizer(private val out: () -> List<TextLine>) : TextRecognizer {
        override val id = "ocr"
        var calls = 0
        override suspend fun recognize(image: SourceImage): List<TextLine> { calls++; return out() }
    }

    private class Faces : RegionDetector {
        override val id = "faces"
        override val kind = SensitiveKind.FACE
        override suspend fun detect(image: SourceImage) = listOf(
            Candidate("face-0", Quad.fromRect(RectF(100f, 20f, 150f, 80f)), SensitiveKind.FACE, DetectorSource.FACE, 0.9f)
        )
    }

    private class Phones : SensitivityClassifier {
        override val id = "rule"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = listOf(
            Candidate("rule-phone-0-3-0", Quad.fromRect(RectF(30f, 0f, 70f, 10f)), SensitiveKind.PHONE, DetectorSource.RULE, 0.95f)
        )
    }

    private fun engine(recognizer: TextRecognizer) = RedactionEngine(
        recognizer.traced(), listOf(Faces().traced()), listOf(Phones().traced()), CandidateMerger(),
    )

    @Test fun withoutARecorderNothingIsRecordedAndResultsAreUnchanged() = runTest {
        val log = TraceLog()
        val result = engine(Recognizer { listOf(line) }).analyze(image)
        assertThat(result.lines).containsExactly(line)
        assertThat(result.candidates).hasSize(2)
        assertThat(log.runs.value).isEmpty()
    }

    @Test fun eachStepIsRecordedWithWhatItProduced() = runTest {
        val log = TraceLog()
        val recorder = log.start("识别")
        withContext(recorder) { engine(Recognizer { listOf(line) }).analyze(image) }

        val sections = log.runs.value.single().sections
        assertThat(sections.map { it.stage })
            .containsExactly(TraceStage.TEXT, TraceStage.REGION, TraceStage.CLASSIFY).inOrder()
        assertThat(sections[0].title).isEqualTo("文字识别 · ocr")
        assertThat(sections[0].lines.single()).isEqualTo("  1  0.97  0,0 60×10  电话：1234")
        assertThat(sections[1].title).isEqualTo("人脸 · faces")
        assertThat(sections[1].lines.single()).startsWith("人脸  打码  0.90  100,20 50×60")
        // 判定的那一节看得到框里压着的字和命中的规则
        assertThat(sections[2].title).isEqualTo("判定 · rule")
        assertThat(sections[2].lines.single()).contains("「1234」")
        assertThat(sections[2].lines.single()).endsWith("rule-phone-0-3-0")
    }

    /** 引擎把识别器的异常吞掉、降级成空结果：调试信息里要看得见吞掉的是什么。 */
    @Test fun aSwallowedFailureShowsUpInTheTrace() = runTest {
        val log = TraceLog()
        val recorder = log.start("识别")
        val result = withContext(recorder) { engine(Recognizer { error("onnx session gone") }).analyze(image) }

        assertThat(result.lines).isEmpty()                   // 引擎照旧降级
        val text = log.runs.value.single().sections.first { it.stage == TraceStage.TEXT }
        assertThat(text.summary).contains("出错：IllegalStateException：onnx session gone")
        assertThat(text.blocks.single().text).contains("onnx session gone")
    }
}
