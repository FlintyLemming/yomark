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
class EditorViewModelBatchTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class CountingSink : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { writes++; return Uri.parse("content://fake/$writes") }
    }

    private lateinit var sink: CountingSink

    private fun vm(): EditorViewModel {
        sink = CountingSink()
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
            engine = engine,
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
    }

    private fun sampleUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    @Test
    fun `a batch starts on the first image and reports its size`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("b1.jpg"), sampleUri("b2.jpg"), sampleUri("b3.jpg")))
        advanceUntilIdle()

        assertThat(vm.state.value.batch!!.total).isEqualTo(3)
        assertThat(vm.state.value.batch!!.index).isEqualTo(0)
        assertThat(vm.state.value.image).isNotNull()
    }

    @Test
    fun `advancing keeps the edits made on the previous image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("c1.jpg"), sampleUri("c2.jpg")))
        advanceUntilIdle()

        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.nextImage(); advanceUntilIdle()

        assertThat(vm.state.value.batch!!.index).isEqualTo(1)
        assertThat(vm.state.value.plan.items).isEmpty()                       // 新图是干净的
        assertThat(vm.state.value.batch!!.items[0].plan!!.items).hasSize(1)   // 上一张的编辑还在
    }

    @Test
    fun `the undo stack is cleared when moving to the next image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("d1.jpg"), sampleUri("d2.jpg")))
        advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        assertThat(vm.state.value.canUndo).isTrue()

        vm.nextImage(); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `exporting a batch writes one file per image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("e1.jpg"), sampleUri("e2.jpg")))
        advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        vm.nextImage(); advanceUntilIdle()

        vm.exportBatch()
        advanceUntilIdle()

        assertThat(sink.writes).isEqualTo(2)
    }

    @Test
    fun `a single shared image does not start a batch`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("f1.jpg")))
        advanceUntilIdle()
        assertThat(vm.state.value.batch).isNull()
    }
}
