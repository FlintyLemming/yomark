package com.yomark.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.Candidate
import com.yomark.app.core.model.TextLine
import com.yomark.app.data.SettingsStore
import com.yomark.app.data.isolatedSettingsStore
import com.yomark.app.engine.CandidateMerger
import com.yomark.app.engine.RedactionEngine
import com.yomark.app.engine.SensitivityClassifier
import com.yomark.app.engine.TextRecognizer
import com.yomark.app.export.Exporter
import com.yomark.app.export.ImageSink
import com.yomark.app.export.PurposeWatermarkStyle
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelWatermarkTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class CapturingSink : ImageSink {
        var last: Bitmap? = null
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { last = bitmap.copy(Bitmap.Config.ARGB_8888, false); return Uri.parse("content://fake/1") }
    }

    private lateinit var sink: CapturingSink

    private fun vm(settings: SettingsStore = isolatedSettingsStore(context)): EditorViewModel {
        sink = CapturingSink()
        val engine = RedactionEngine(
            object : TextRecognizer {
                override val id = "none"
                override suspend fun recognize(image: SourceImage) = emptyList<TextLine>()
            },
            emptyList(),
            listOf(object : SensitivityClassifier {
                override val id = "none"
                override suspend fun isAvailable() = true
                override suspend fun classify(lines: List<TextLine>) = emptyList<Candidate>()
            }),
            CandidateMerger(),
        )
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { engine },
            settings = settings,
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
    }

    private fun whiteUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.toUri()
    }

    /** 水印落在右下角。这块区域有没有非白像素，就是有没有水印。 */
    private fun cornerMarked(): Boolean {
        val bmp = sink.last!!
        for (x in 500 until 590 step 3) for (y in 500 until 590 step 3) {
            if (bmp.getPixel(x, y) != Color.WHITE) return true
        }
        return false
    }

    @Test
    fun `a free user gets the brand watermark`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(whiteUri("free.png")); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.requestExport(); advanceUntilIdle()
        assertThat(cornerMarked()).isTrue()
    }

    @Test
    fun `a pro user gets no watermark`() = runTest(dispatcher) {
        val vm = vm()
        val pro = MutableStateFlow(true)
        vm.observePro(pro); advanceUntilIdle()

        vm.onImageChosen(whiteUri("pro.png")); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.requestExport(); advanceUntilIdle()
        assertThat(cornerMarked()).isFalse()
    }

    @Test
    fun `pro state flowing in flips the ui state`() = runTest(dispatcher) {
        val vm = vm()
        val pro = MutableStateFlow(false)
        vm.observePro(pro); advanceUntilIdle()
        assertThat(vm.state.value.isPro).isFalse()

        pro.value = true; advanceUntilIdle()
        assertThat(vm.state.value.isPro).isTrue()
    }

    // ---------- 用途水印 ----------

    @Test
    fun `opening the panel turns the watermark on with a default text so the preview shows at once`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(whiteUri("open.png")); advanceUntilIdle()
        vm.showPurposeSheet()
        assertThat(vm.state.value.purposeSheetVisible).isTrue()
        assertThat(vm.state.value.purposeText).isNotEmpty()
    }

    @Test
    fun `clearing the text turns it off, and reopening brings back the last text`() = runTest(dispatcher) {
        val vm = vm()
        vm.showPurposeSheet()
        vm.setPurposeText("仅供办理签证使用")
        vm.setPurposeText("")
        assertThat(vm.state.value.purposeText).isNull()
        assertThat(vm.state.value.purposeSheetVisible).isTrue()      // 打字途中清空不收面板

        vm.removePurposeWatermark()
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        vm.showPurposeSheet()
        assertThat(vm.state.value.purposeText).isEqualTo("仅供办理签证使用")
    }

    @Test
    fun `the tuned style is what the export draws`() = runTest(dispatcher) {
        val vm = vm()
        vm.observePro(MutableStateFlow(true)); advanceUntilIdle()     // 去掉品牌水印，只看用途水印
        vm.onImageChosen(whiteUri("styled.png")); advanceUntilIdle()
        vm.showPurposeSheet()
        vm.setPurposeText("仅供办理签证使用")
        vm.setPurposeStyle(PurposeWatermarkStyle(color = PurposeWatermarkStyle.PALETTE[3], opacity = 0.6f))
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.requestExport(); advanceUntilIdle()

        val bmp = sink.last!!
        var reddish = 0
        for (x in 0 until bmp.width step 3) for (y in 0 until bmp.height step 3) {
            val c = bmp.getPixel(x, y)
            if (Color.red(c) > Color.blue(c) + 40) reddish++
        }
        assertThat(reddish).isGreaterThan(50)
    }

    @Test
    fun `the style is remembered for next time and survives picking another image`() = runTest(dispatcher) {
        val settings = isolatedSettingsStore(context)
        val vm = vm(settings)
        advanceUntilIdle()
        val tuned = PurposeWatermarkStyle(angle = 15f, opacity = 0.3f, density = 1.5f)
        vm.setPurposeStyle(tuned)
        vm.onImageChosen(whiteUri("next.png")); advanceUntilIdle()

        assertThat(vm.state.value.purposeStyle).isEqualTo(tuned)
        assertThat(settings.purposeWatermarkStyle.first()).isEqualTo(tuned)
        assertThat(vm(settings).also { advanceUntilIdle() }.state.value.purposeStyle).isEqualTo(tuned)
    }
}
