package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.debug.TraceStage
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.engine.traced
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 调试信息开着时，编辑器每次识别、每次 AI 增强识别都记一份；关着时一个字都不记。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelTraceTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://fake/1")
    }

    private class Ocr : TextRecognizer {
        override val id = "ocr"
        override suspend fun recognize(image: SourceImage): List<TextLine> = emptyList()
    }

    private class Stub(override val id: String, private val out: List<Candidate>) : SensitivityClassifier {
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = out
    }

    private fun candidate(id: String, source: DetectorSource, l: Float) = Candidate(
        id, Quad.fromRect(RectF(l, 10f, l + 50f, 30f)), SensitiveKind.PERSON_NAME, source, 0.8f,
        enabledByDefault = source == DetectorSource.RULE,
    )

    private fun vm(settings: SettingsStore) = EditorViewModel(
        intake = ImageIntake(context),
        exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
        engineProvider = {
            RedactionEngine(
                Ocr().traced(), emptyList(),
                listOf(Stub("rule", listOf(candidate("rule-name-0-0-0", DetectorSource.RULE, 10f))).traced()),
                CandidateMerger(),
                refiners = listOf(Stub("nano", listOf(candidate("llm-0-0-PERSON_NAME", DetectorSource.LLM, 200f))).traced()),
            )
        },
        settings = settings,
        savedState = SavedStateHandle(),
        ioDispatcher = dispatcher,
    )

    private fun sampleUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private suspend fun TestScope.store(debug: Boolean) = isolatedSettingsStore(context).also {
        it.setDebugTrace(debug)
        advanceUntilIdle()
    }

    @Test
    fun `nothing is recorded while debug info is off`() = runTest(dispatcher) {
        val vm = vm(store(debug = false))
        advanceUntilIdle()
        vm.onImageChosen(sampleUri("trace-off.jpg")); advanceUntilIdle()
        assertThat(vm.debugTrace.value).isFalse()
        assertThat(vm.traceLog.runs.value).isEmpty()
    }

    @Test
    fun `each recognition records its input every step and the result`() = runTest(dispatcher) {
        val vm = vm(store(debug = true))
        advanceUntilIdle()
        vm.onImageChosen(sampleUri("trace-on.jpg")); advanceUntilIdle()

        val run = vm.traceLog.runs.value.single()
        assertThat(run.title).isEqualTo("识别")
        assertThat(run.durationMs).isNotNull()
        assertThat(run.sections.map { it.stage }).containsAtLeast(
            TraceStage.INPUT, TraceStage.TEXT, TraceStage.CLASSIFY, TraceStage.RESULT,
        ).inOrder()
        assertThat(run.sections.first().summary).contains("image/jpeg")
        assertThat(run.sections.last().summary).isEqualTo("合并后 1 处：打码 1 · 不打码 0")
    }

    @Test
    fun `an ai review is recorded as a run of its own`() = runTest(dispatcher) {
        val vm = vm(store(debug = true))
        advanceUntilIdle()
        vm.onImageChosen(sampleUri("trace-ai.jpg")); advanceUntilIdle()
        vm.runAiReview(); advanceUntilIdle()

        val (review, analysis) = vm.traceLog.runs.value
        assertThat(analysis.title).isEqualTo("识别")
        assertThat(review.title).isEqualTo("AI 增强识别")
        assertThat(review.sections.first { it.title == "判定 · nano" }.lines.single()).contains("llm-0-0-PERSON_NAME")
        assertThat(review.sections.last().summary).startsWith("新增 1 处")
    }

    /** 打开调试信息之前就识别完的图：「重新识别」再跑一遍，好记下这一次。 */
    @Test
    fun `rerunning records the current image again`() = runTest(dispatcher) {
        val vm = vm(store(debug = true))
        advanceUntilIdle()
        vm.onImageChosen(sampleUri("trace-rerun.jpg")); advanceUntilIdle()

        vm.rerunRecognition(); advanceUntilIdle()

        assertThat(vm.traceLog.runs.value.map { it.number }).containsExactly(2, 1).inOrder()
        assertThat(vm.state.value.analyzing).isFalse()
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule-name-0-0-0")
    }

    @Test
    fun `turning debug info off clears what was recorded`() = runTest(dispatcher) {
        val settings = store(debug = true)
        val vm = vm(settings)
        advanceUntilIdle()
        vm.onImageChosen(sampleUri("trace-clear.jpg")); advanceUntilIdle()
        assertThat(vm.traceLog.runs.value).isNotEmpty()

        settings.setDebugTrace(false); advanceUntilIdle()

        assertThat(vm.debugTrace.value).isFalse()
        assertThat(vm.traceLog.runs.value).isEmpty()
    }
}
