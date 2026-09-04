package com.youma.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.ImageIntake
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
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
class EditorViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private class RecordingSink : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { writes++; return Uri.parse("content://fake/$writes") }
    }

    private lateinit var sink: RecordingSink

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): EditorViewModel {
        sink = RecordingSink()
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engine = emptyEngine(),
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
    }

    /**
     * 什么都不产出的引擎。本测试类验的是编辑与导出，不验识别——
     * 识别接进 ViewModel 的行为由 EditorViewModelAnalysisTest 覆盖。
     */
    private fun emptyEngine() = RedactionEngine(
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

    private fun sampleUri(name: String = "vm.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private fun manual(id: String, l: Float, t: Float, r: Float, b: Float, state: MaskState = MaskState.MASKED) =
        MaskItem(id, Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.MANUAL, DetectorSource.MANUAL, state)

    @Test
    fun `loading an image populates state and clears history`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri())
        advanceUntilIdle()

        assertThat(vm.state.value.image).isNotNull()
        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `drawing a manual box adds a masked item`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))

        val item = vm.state.value.plan.items.single()
        assertThat(item.state).isEqualTo(MaskState.MASKED)
        assertThat(item.kind).isEqualTo(SensitiveKind.MANUAL)
        assertThat(item.source).isEqualTo(DetectorSource.MANUAL)
    }

    @Test
    fun `tapping a mask toggles it to outlined`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onTap(PointF(50f, 50f))

        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.OUTLINED)
    }

    @Test
    fun `tapping empty space clears the selection`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onLongPress(PointF(50f, 50f))
        assertThat(vm.state.value.selectedManualId).isNotNull()

        vm.onTap(PointF(300f, 300f))
        assertThat(vm.state.value.selectedManualId).isNull()
    }

    @Test
    fun `undo restores the plan before the last box`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onManualBox(Quad.fromRect(RectF(120f, 120f, 200f, 200f)))
        assertThat(vm.state.value.plan.items).hasSize(2)

        vm.undo()
        assertThat(vm.state.value.plan.items).hasSize(1)
        assertThat(vm.state.value.canRedo).isTrue()

        vm.redo()
        assertThat(vm.state.value.plan.items).hasSize(2)
    }

    @Test
    fun `deleting works for a manual box and is a no-op for a rule candidate`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onLongPress(PointF(50f, 50f))
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).isEmpty()

        // 塞进一个规则候选，长按选中后删不掉
        vm.replacePlanForTest(vm.state.value.plan.add(
            manual("rule", 10f, 10f, 90f, 90f).copy(source = DetectorSource.RULE, kind = SensitiveKind.URL)
        ))
        vm.onLongPress(PointF(50f, 50f))
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).hasSize(1)
    }

    @Test
    fun `changing style is undoable and global`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.setStyle(MaskStyle.PIXELATE)
        assertThat(vm.state.value.plan.style).isEqualTo(MaskStyle.PIXELATE)
        vm.undo()
        assertThat(vm.state.value.plan.style).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `export with no pending items writes immediately`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.requestExport()
        advanceUntilIdle()

        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(1)
        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Exported::class.java)
    }

    @Test
    fun `export with pending items opens the interception dialog and writes nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))

        vm.requestExport()
        advanceUntilIdle()

        assertThat(vm.state.value.pendingDialogVisible).isTrue()
        assertThat(sink.writes).isEqualTo(0)
    }

    @Test
    fun `mask all then export clears pending and writes once`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))
        vm.requestExport(); advanceUntilIdle()

        vm.confirmMaskAllAndExport(); advanceUntilIdle()

        assertThat(vm.state.value.plan.pendingCount).isEqualTo(0)
        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(1)
    }

    @Test
    fun `export anyway writes without masking the pending items`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))
        vm.requestExport(); advanceUntilIdle()

        vm.confirmExportAnyway(); advanceUntilIdle()

        assertThat(vm.state.value.plan.pendingCount).isEqualTo(1)
        assertThat(sink.writes).isEqualTo(1)
    }

    @Test
    fun `loading a second image clears the undo history`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("one.jpg")); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        assertThat(vm.state.value.canUndo).isTrue()

        vm.onImageChosen(sampleUri("two.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()
        assertThat(vm.state.value.plan.items).isEmpty()
    }
}
