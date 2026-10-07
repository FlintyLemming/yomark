package com.yomark.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.Candidate
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.core.model.TextLine
import com.yomark.app.data.SettingsStore
import com.yomark.app.data.isolatedSettingsStore
import com.yomark.app.engine.CandidateMerger
import com.yomark.app.engine.RecognitionConfig
import com.yomark.app.engine.RedactionEngine
import com.yomark.app.engine.RuleState
import com.yomark.app.engine.SensitivityClassifier
import com.yomark.app.engine.TextEngineOption
import com.yomark.app.engine.TextRecognizer
import com.yomark.app.export.Exporter
import com.yomark.app.export.ImageSink
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
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

/**
 * 切换识别方案后的重跑语义（2026-09-04 增补设计 §6）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelRecognitionTest {

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

    private class StubClassifier(private val out: List<Candidate>) : SensitivityClassifier {
        override val id = "stub"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = out
    }

    private class DeadRecognizer : TextRecognizer {
        override val id = "dead"
        override suspend fun recognize(image: SourceImage): List<TextLine> = emptyList()
    }

    private fun candidate(id: String) = Candidate(
        id, Quad.fromRect(RectF(10f, 10f, 60f, 30f)),
        SensitiveKind.URL, DetectorSource.RULE, 0.8f, enabledByDefault = true,
    )

    /** 每套 config 产出一个带自己名字的候选，重跑有没有真的换引擎一看便知。 */
    private fun vm(settings: SettingsStore = isolatedSettingsStore(context)) = EditorViewModel(
        intake = ImageIntake(context),
        exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
        engineProvider = { cfg ->
            RedactionEngine(
                DeadRecognizer(),
                emptyList(),
                listOf(StubClassifier(listOf(candidate("from-${cfg.textEngine}")))),
                CandidateMerger(),
            )
        },
        settings = settings,
        savedState = SavedStateHandle(),
        ioDispatcher = dispatcher,
    )

    private fun sampleUri(name: String = "cfg.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private fun ids(vm: EditorViewModel) = vm.state.value.plan.items.map { it.candidateId }

    @Test fun `switching the config re-runs recognition with the new engine`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(ids(vm)).contains("from-${TextEngineOption.PADDLE}")

        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.LATIN)); advanceUntilIdle()

        assertThat(ids(vm)).contains("from-${TextEngineOption.LATIN}")
        assertThat(ids(vm)).doesNotContain("from-${TextEngineOption.PADDLE}")
    }

    @Test fun `manual boxes survive the re-run`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(200f, 200f, 260f, 240f)))
        val manualId = vm.state.value.plan.items.single { it.kind == SensitiveKind.MANUAL }.candidateId

        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.CHINESE)); advanceUntilIdle()

        assertThat(ids(vm)).contains(manualId)
    }

    @Test fun `the whole re-run is one undo away`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        val before = vm.state.value.plan

        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.LATIN)); advanceUntilIdle()
        assertThat(vm.state.value.plan).isNotEqualTo(before)
        assertThat(vm.state.value.canUndo).isTrue()

        vm.undo()
        assertThat(vm.state.value.plan).isEqualTo(before)
    }

    /**
     * 改任意一根轴都要重跑，不只是 OCR 那根——条码/人脸/规则同样换引擎。
     * 这里只能断言「重跑发生了」（压了快照），不能断言 plan 变了：
     * 桩引擎的候选只按 OCR 轴命名，改条码轴时产出内容相同。
     */
    @Test fun `changing an unrelated axis still re-runs`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()

        vm.setRecognitionConfig(RecognitionConfig(barcodeState = RuleState.OFF)); advanceUntilIdle()

        assertThat(vm.state.value.canUndo).isTrue()
        assertThat(vm.state.value.recognitionConfig.barcodeState).isEqualTo(RuleState.OFF)
    }

    @Test fun `writing the same config again does not re-run`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.setRecognitionConfig(RecognitionConfig()); advanceUntilIdle()

        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test fun `no image means no re-run and no crash`() = runTest(dispatcher) {
        val vm = vm()
        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.LATIN)); advanceUntilIdle()

        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.analyzing).isFalse()
        assertThat(vm.state.value.recognitionConfig.textEngine).isEqualTo(TextEngineOption.LATIN)
    }

    /** 冷启动时读到的持久化 config 必须用在第一张图上，不能等到用户再改一次才生效。 */
    @Test fun `a persisted config applies to the first image`() = runTest(dispatcher) {
        val settings = isolatedSettingsStore(context)
        settings.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.LATIN))
        val vm = vm(settings); advanceUntilIdle()

        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(ids(vm)).contains("from-${TextEngineOption.LATIN}")
    }
}
