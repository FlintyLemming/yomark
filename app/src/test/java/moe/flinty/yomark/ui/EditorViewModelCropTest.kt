package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.CropFinding
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.core.model.TopCrop
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.CropFinder
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
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
 * 实验室的「裁切状态栏」（2026-10-29）在编辑器里：识别出的那一条进 plan、压暗显示，点一下留着状态栏，导出时裁。
 * 找那一条的算法在 statusbar 包里，这里用一个给定高度的 [CropFinder] 顶替。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelCropTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** 记下写出的图有多高。 */
    private class HeightSink : ImageSink {
        var height = -1
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { height = bitmap.height; return Uri.parse("content://fake/1") }
    }

    private class Recognizer(private val lines: List<TextLine>) : TextRecognizer {
        override val id = "stub"
        override suspend fun recognize(image: SourceImage): List<TextLine> = lines
    }

    /** 每张图都说顶上 [top] 行是状态栏；null 是没找到。 */
    private class FixedCrop(var top: Float?) : CropFinder {
        override val id = "fixed"
        override suspend fun find(image: SourceImage, lines: List<TextLine>) = CropFinding(top)
    }

    private fun line(l: Float, t: Float, r: Float, b: Float, text: String): TextLine {
        val quad = Quad.fromRect(RectF(l, t, r, b))
        return TextLine(quad, text, 0.95f, listOf(TextElement(quad, text, text.indices)))
    }

    /** 状态栏里的时间（顶上 30 行里），和页面上的一行字。 */
    private val clock = line(10f, 8f, 60f, 22f, "14:30")
    private val body = line(10f, 100f, 300f, 130f, "订单号 1234567890123456")

    private lateinit var sink: HeightSink
    private val finder = FixedCrop(30f)

    private fun vm(saved: SavedStateHandle = SavedStateHandle()): EditorViewModel {
        sink = HeightSink()
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = {
                RedactionEngine(Recognizer(listOf(clock, body)), emptyList(), emptyList(), CandidateMerger(), cropFinders = listOf(finder))
            },
            settings = isolatedSettingsStore(context),
            savedState = saved,
            ioDispatcher = dispatcher,
        )
    }

    private fun sampleUri(name: String = "crop.png"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.toUri()
    }

    private fun EditorViewModel.crop() = state.value.plan.crop

    /** 识别出的那一条和识别出的码一样是初始状态：裁着，撤销不回去。 */
    @Test fun `a found status bar comes in cut, as where the editor starts`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.crop()).isEqualTo(TopCrop(30f))
        assertThat(vm.state.value.plan.cropTop).isEqualTo(30f)
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test fun `tapping the band keeps the status bar, tapping again cuts it, and undo goes back`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onTap(PointF(200f, 15f))
        assertThat(vm.crop()).isEqualTo(TopCrop(30f, on = false))
        assertThat(vm.state.value.plan.cropTop).isEqualTo(0f)
        assertThat(vm.state.value.canUndo).isTrue()

        vm.onTap(PointF(30f, 15f))                                       // 点在时间上：还是那一条，不给时间打码
        assertThat(vm.crop()).isEqualTo(TopCrop(30f))
        assertThat(vm.state.value.plan.items).isEmpty()

        vm.undo()
        assertThat(vm.crop()?.on).isFalse()
        vm.undo()
        assertThat(vm.crop()?.on).isTrue()
    }

    /** 那一条底下照旧：点字打码。 */
    @Test fun `below the band a tap masks text as before`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onTap(PointF(100f, 115f))
        assertThat(vm.state.value.plan.items.single().source).isEqualTo(DetectorSource.LINE)
        assertThat(vm.crop()).isEqualTo(TopCrop(30f))
    }

    @Test fun `export cuts the band off, unless the user kept the status bar`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.requestExport(); advanceUntilIdle()
        assertThat(sink.height).isEqualTo(370)

        vm.onTap(PointF(200f, 15f))
        vm.requestExport(); advanceUntilIdle()
        assertThat(sink.height).isEqualTo(400)
    }

    /** 换方案重跑：留着状态栏的还是留着；这一遍没找到状态栏，就没有那一条。 */
    @Test fun `keeping the status bar survives a change of settings`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onTap(PointF(200f, 15f))

        finder.top = 32f
        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.MLKIT)); advanceUntilIdle()
        assertThat(vm.crop()).isEqualTo(TopCrop(32f, on = false))

        finder.top = null
        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.PADDLE)); advanceUntilIdle()
        assertThat(vm.crop()).isNull()
        vm.onTap(PointF(30f, 15f))                                       // 没有那一条了：点时间就是给时间打码
        assertThat(vm.state.value.plan.items.single().quad).isEqualTo(clock.quad)
    }

    @Test fun `the band comes back with the plan after recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(saved)
        first.onImageChosen(sampleUri()); advanceUntilIdle()
        first.onTap(PointF(200f, 15f))

        val second = vm(saved); advanceUntilIdle()
        assertThat(second.crop()).isEqualTo(TopCrop(30f, on = false))
        second.onTap(PointF(200f, 15f))
        assertThat(second.crop()).isEqualTo(TopCrop(30f))
    }
}
