package com.youma.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.ImageIntake
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.TextLine
import com.youma.app.data.isolatedSettingsStore
import com.youma.app.engine.CandidateMerger
import com.youma.app.engine.RedactionEngine
import com.youma.app.engine.SensitivityClassifier
import com.youma.app.engine.TextRecognizer
import com.youma.app.export.Exporter
import com.youma.app.export.ImageSink
import com.youma.app.export.WatermarkDrawer
import com.youma.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun vm(): EditorViewModel {
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
            settings = isolatedSettingsStore(context),
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
}
