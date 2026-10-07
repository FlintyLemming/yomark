package com.yomark.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
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
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
import com.yomark.app.ui.canvas.ScanStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
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
    private lateinit var settings: SettingsStore

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm(): EditorViewModel {
        sink = RecordingSink()
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { emptyEngine() },
            settings = isolatedSettingsStore(context).also { settings = it },
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
    fun `a freshly drawn box is selected so its handles show right away`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))

        val item = vm.state.value.plan.items.single()
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)

        vm.previewSelectedQuad(Quad.fromRect(RectF(10f, 10f, 150f, 120f)))
        vm.commitDrag()
        assertThat(vm.state.value.plan.items.single().quad.bounds()).isEqualTo(RectF(10f, 10f, 150f, 120f))
    }

    private fun rule(id: String, l: Float, t: Float, r: Float, b: Float) =
        manual(id, l, t, r, b).copy(source = DetectorSource.RULE, kind = SensitiveKind.URL)

    @Test
    fun `tapping a detected mask toggles it to outlined`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 10f, 10f, 90f, 90f)))
        vm.onTap(PointF(50f, 50f))

        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.OUTLINED)
    }

    @Test
    fun `tapping a manual box selects it again instead of toggling it`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onTap(PointF(300f, 300f))
        assertThat(vm.state.value.selectedManualId).isNull()

        vm.onTap(PointF(50f, 50f))
        val item = vm.state.value.plan.items.single()
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
        assertThat(item.state).isEqualTo(MaskState.MASKED)

        // 选中时再点一下，还是选中、还是打码
        vm.onTap(PointF(50f, 50f))
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `tapping empty space clears the selection`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        assertThat(vm.state.value.selectedManualId).isNotNull()

        vm.onTap(PointF(300f, 300f))
        assertThat(vm.state.value.selectedManualId).isNull()
    }

    @Test
    fun `tapping a detected box toggles it and clears the selection`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 200f, 200f, 300f, 300f)))
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        assertThat(vm.state.value.selectedManualId).isNotNull()

        vm.onTap(PointF(250f, 250f))
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.plan.find("rule")!!.state).isEqualTo(MaskState.OUTLINED)
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
        vm.onTap(PointF(300f, 300f))
        vm.onTap(PointF(50f, 50f))
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).isEmpty()

        // 塞进一个规则候选：点它进不了选中态，删除也就删不掉它
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 10f, 10f, 90f, 90f)))
        vm.onTap(PointF(50f, 50f))
        assertThat(vm.state.value.selectedManualId).isNull()
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).hasSize(1)
    }

    // ---------- 样式：每块码有自己的样式，样式栏是画笔（spec §7.5 修订） ----------

    private fun box(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    private fun EditorViewModel.looks() = state.value.plan.items.map { it.look.style }

    @Test
    fun `a new style applies to the next mask, not to the ones already made`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        vm.clearSelection()

        vm.setStyle(MaskStyle.PIXELATE)
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID)          // 已经打好的不跟着变

        vm.onManualBox(box(120f, 120f, 200f, 200f))
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID, MaskStyle.PIXELATE).inOrder()
    }

    @Test
    fun `tapping an outlined candidate masks it with the current style`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 10f, 10f, 90f, 90f).copy(state = MaskState.OUTLINED)))
        vm.setStyle(MaskStyle.EMOJI)

        vm.onTap(PointF(50f, 50f))
        val item = vm.state.value.plan.items.single()
        assertThat(item.state).isEqualTo(MaskState.MASKED)
        assertThat(item.look.style).isEqualTo(MaskStyle.EMOJI)
    }

    @Test
    fun `changing the style with nothing selected is not an undo step`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.BLUR)
        assertThat(vm.state.value.canUndo).isFalse()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.BLUR)
    }

    /** 刚画完的框就是选中的：画完接着调颜色，改的就是它，之后再画的也是这个颜色。 */
    @Test
    fun `panel edits change the selected box and the boxes drawn after it`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(solidColor = Color.RED)))

        assertThat(vm.state.value.plan.items.single().look.options.solidColor).isEqualTo(Color.RED)
        vm.clearSelection()
        vm.onManualBox(box(120f, 120f, 200f, 200f))
        assertThat(vm.state.value.plan.items.map { it.look.options.solidColor })
            .containsExactly(Color.RED, Color.RED)

        // 撤销只撤 plan，画笔不回退
        vm.undo(); vm.undo()
        assertThat(vm.state.value.plan.items.single().look.options.solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(vm.state.value.brush.options.solidColor).isEqualTo(Color.RED)
    }

    @Test
    fun `selecting a box shows its look without changing the brush`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        vm.clearSelection()
        vm.setStyle(MaskStyle.EMOJI)

        vm.onTap(PointF(50f, 50f))                                         // 点选那个色块
        assertThat(vm.state.value.barLook.style).isEqualTo(MaskStyle.SOLID)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.EMOJI)

        vm.onTap(PointF(300f, 300f))                                       // 点空白，样式栏回到画笔
        assertThat(vm.state.value.barLook.style).isEqualTo(MaskStyle.EMOJI)
    }

    @Test
    fun `a slider drag on the selected box is a single undo step`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.BLUR)
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        val start = vm.state.value.barLook

        listOf(0.12f, 0.18f, 0.24f, 0.3f).forEach {
            vm.editLook(start.copy(options = start.options.copy(blurRadiusRatio = it)), inProgress = true)
        }
        vm.finishLookEdit()
        assertThat(vm.state.value.plan.items.single().look.options.blurRadiusRatio).isEqualTo(0.3f)

        vm.undo()
        assertThat(vm.state.value.plan.items.single().look).isEqualTo(start)
        vm.undo()
        assertThat(vm.state.value.plan.items).isEmpty()
    }

    @Test
    fun `apply to all restyles every masked item in one step and leaves outlined ones`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(
            vm.state.value.plan
                .add(rule("a", 10f, 10f, 90f, 90f))
                .add(rule("b", 120f, 10f, 200f, 90f).copy(state = MaskState.OUTLINED))
                .add(manual("m", 10f, 120f, 90f, 200f))
        )
        vm.setStyle(MaskStyle.MARKER)
        vm.applyLookToAll()

        val looks = vm.state.value.plan.items.associate { it.candidateId to it.look.style }
        assertThat(looks).containsExactly("a", MaskStyle.MARKER, "b", MaskStyle.SOLID, "m", MaskStyle.MARKER)
        vm.undo()
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID, MaskStyle.SOLID, MaskStyle.SOLID)
    }

    @Test
    fun `reset puts back only the current style's params`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(solidColor = Color.BLACK, emoji = "🐱")))
        vm.resetLookOptions()
        assertThat(vm.state.value.brush.options.solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(vm.state.value.brush.options.emoji).isEqualTo("🐱")
    }

    @Test
    fun `tapping a style opens its panel and tapping it again closes it`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onStyleChipClick(MaskStyle.EMOJI)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.EMOJI)

        vm.onStyleChipClick(MaskStyle.EMOJI)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        vm.onStyleChipClick(MaskStyle.EMOJI)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        vm.dismissStylePanel()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
    }

    @Test
    fun `the brush is remembered across image changes and sessions`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("brush-1.jpg")); advanceUntilIdle()
        vm.setStyle(MaskStyle.EMOJI)
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(emoji = "🐱", emojiTiled = true)))
        advanceUntilIdle()                                                 // 落盘有去抖

        vm.onImageChosen(sampleUri("brush-2.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.EMOJI)
        assertThat(vm.state.value.brush.options.emoji).isEqualTo("🐱")

        val next = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { emptyEngine() },
            settings = settings,
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertThat(next.state.value.brush.style).isEqualTo(MaskStyle.EMOJI)
        assertThat(next.state.value.brush.options.emojiTiled).isTrue()
    }

    private fun twoToneUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(200f, 0f, 400f, 400f, android.graphics.Paint().apply { color = Color.rgb(0x20, 0x60, 0xA0) })
        }
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.toUri()
    }

    @Test
    fun `the eyedropper takes the color under the tap and toggles nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(twoToneUri("eyedropper.png")); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 250f, 250f, 350f, 350f)))
        vm.startColorPick(ColorTarget.SOLID)

        vm.onTap(PointF(-20f, 100f))                                       // 点在图外：接着等
        assertThat(vm.state.value.colorPick).isEqualTo(ColorTarget.SOLID)

        vm.onTap(PointF(300f, 300f))                                       // 点在一块码上：只取色，不切换
        assertThat(vm.state.value.colorPick).isNull()
        assertThat(vm.state.value.brush.options.solidColor).isEqualTo(Color.rgb(0x20, 0x60, 0xA0))
        assertThat(vm.state.value.plan.find("rule")!!.state).isEqualTo(MaskState.MASKED)
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `the eyedropper keeps the marker's opacity`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(twoToneUri("eyedropper-marker.png")); advanceUntilIdle()
        vm.setStyle(MaskStyle.MARKER)
        val before = vm.state.value.brush.options.markerAlpha
        vm.startColorPick(ColorTarget.MARKER)
        vm.onTap(PointF(300f, 100f))
        val after = vm.state.value.brush.options
        assertThat(after.markerColor and 0xFFFFFF).isEqualTo(0x2060A0)
        assertThat(after.markerAlpha).isWithin(0.01f).of(before)
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
    fun `do not show again exports this time and skips the dialog next time`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))
        vm.requestExport(); advanceUntilIdle()

        vm.confirmExportAnyway(stopReminding = true); advanceUntilIdle()
        assertThat(sink.writes).isEqualTo(1)
        assertThat(settings.pendingExportReminder.first()).isFalse()

        vm.requestExport(); advanceUntilIdle()
        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(2)
        assertThat(vm.state.value.plan.pendingCount).isEqualTo(1)
    }

    @Test
    fun `with the reminder turned off in settings pending items export without asking`() = runTest(dispatcher) {
        val vm = vm()
        settings.setPendingExportReminder(false)
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(manual("p", 10f, 10f, 90f, 90f, MaskState.OUTLINED)))

        vm.requestExport(); advanceUntilIdle()

        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(1)
    }

    @Test
    fun `the canvas follows the scan effect chosen in settings, across image changes`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.scanStyle.value).isEqualTo(ScanStyle.SWEEP)

        settings.setScanStyle(ScanStyle.FROST); advanceUntilIdle()
        assertThat(vm.scanStyle.value).isEqualTo(ScanStyle.FROST)

        // 换图会整个重建 EditorUiState，样式不在那里面，不会被换回出厂值
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.scanStyle.value).isEqualTo(ScanStyle.FROST)
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
