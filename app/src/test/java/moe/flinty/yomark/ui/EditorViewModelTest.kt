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
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.ui.canvas.ScanStyle
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

    private fun manual(id: String, l: Float, t: Float, r: Float, b: Float) =
        MaskItem(id, Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.MANUAL, DetectorSource.MANUAL)

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
        assertThat(item.kind).isEqualTo(SensitiveKind.MANUAL)
        assertThat(item.source).isEqualTo(DetectorSource.MANUAL)
    }

    /** 画笔手画的一笔：和框一样落笔就打上、画完选中，外接框把整条线（连笔宽）包在里面，撤销得回来。 */
    @Test
    fun `a freehand pen stroke becomes a selected item that holds the stroke`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PEN)
        vm.onInk(listOf(PointF(20f, 30f), PointF(60f, 40f), PointF(80f, 30f)))

        val item = vm.state.value.plan.items.single()
        assertThat(item.look.style).isEqualTo(MaskStyle.PEN)
        assertThat(item.ink!!.points).hasSize(3)
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
        val box = item.quad.bounds()
        assertThat(box.left).isLessThan(20f)
        assertThat(box.right).isGreaterThan(80f)
        vm.undo()
        assertThat(vm.state.value.plan.items).isEmpty()
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

    /** 2026-10-14 起没有「圈出」：点一下识别出的码就是拿掉它，撤销能回来。 */
    @Test
    fun `tapping a detected mask takes it off, and undo brings it back`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 10f, 10f, 90f, 90f)))
        vm.onTap(PointF(50f, 50f))

        assertThat(vm.state.value.plan.items).isEmpty()
        vm.undo()
        assertThat(vm.state.value.plan.items.single().candidateId).isEqualTo("rule")
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

        // 选中时再点一下，还是选中、码还在
        vm.onTap(PointF(50f, 50f))
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
        assertThat(vm.state.value.plan.items.single()).isEqualTo(item)
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
    fun `tapping a detected box takes it off and clears the selection`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 200f, 200f, 300f, 300f)))
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        assertThat(vm.state.value.selectedManualId).isNotNull()

        vm.onTap(PointF(250f, 250f))
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.plan.find("rule")).isNull()
    }

    // ---------- 宽屏的框列表 ----------

    @Test
    fun `a list tap takes a detected box off like a tap on the canvas`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 200f, 200f, 300f, 300f)))
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))

        vm.onListItemClick("rule")
        assertThat(vm.state.value.plan.find("rule")).isNull()
        assertThat(vm.state.value.selectedManualId).isNull()       // 点了别处，选中态收起
        vm.undo()
        assertThat(vm.state.value.plan.find("rule")).isNotNull()
    }

    @Test
    fun `a list tap on a manual box selects it and keeps it`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        val id = vm.state.value.plan.items.single().candidateId
        vm.clearSelection()

        vm.onListItemClick(id)
        assertThat(vm.state.value.selectedManualId).isEqualTo(id)
        assertThat(vm.state.value.plan.items.single().candidateId).isEqualTo(id)
    }

    /** 吸管在等着取色时点了列表：点的不是图，收起吸管，这一下照常算数。 */
    @Test
    fun `a list tap puts the eyedropper away and still takes the box off`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 200f, 200f, 300f, 300f)))
        vm.startColorPick(ColorTarget.SOLID)

        vm.onListItemClick("rule")
        assertThat(vm.state.value.colorPick).isNull()
        assertThat(vm.state.value.plan.find("rule")).isNull()
    }

    @Test
    fun `a list tap on a box that is gone does nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.startColorPick(ColorTarget.SOLID)
        vm.onListItemClick("gone")
        assertThat(vm.state.value.colorPick).isEqualTo(ColorTarget.SOLID)
        assertThat(vm.state.value.canUndo).isFalse()
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
    fun `deleting works for a manual box, a rule candidate is never selected and a tap takes it off`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.onTap(PointF(300f, 300f))
        vm.onTap(PointF(50f, 50f))
        vm.deleteSelected()
        assertThat(vm.state.value.plan.items).isEmpty()

        // 塞进一个规则候选：点它进不了选中态，点一下就拿掉了
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 10f, 10f, 90f, 90f)))
        vm.onTap(PointF(50f, 50f))
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.plan.items).isEmpty()
    }

    // ---------- 样式：每块码有自己的样式，样式栏是画笔（spec §7.5 修订） ----------

    private fun box(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    private fun EditorViewModel.looks() = state.value.plan.items.map { it.look.style }

    @Test
    fun `with restyle-all off a new style applies to the next mask, not to the ones already made`() = runTest(dispatcher) {
        val vm = vm()
        settings.setRestyleAllMasks(false)
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        vm.clearSelection()

        vm.setStyle(MaskStyle.PIXELATE)
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID)          // 已经打好的不跟着变

        vm.onManualBox(box(120f, 120f, 200f, 200f))
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID, MaskStyle.PIXELATE).inOrder()
    }

    @Test
    fun `switching between redaction styles restyles every masked redaction in one step`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.replacePlanForTest(
            vm.state.value.plan
                .add(rule("a", 10f, 10f, 90f, 90f))
                .add(manual("m", 10f, 120f, 90f, 200f).copy(look = MaskLook(MaskStyle.MARKER)))
                .add(manual("s", 120f, 120f, 200f, 200f))
        )
        vm.setStyle(MaskStyle.PIXELATE)

        val looks = vm.state.value.plan.items.associate { it.candidateId to it.look.style }
        assertThat(looks).containsExactly("a", MaskStyle.PIXELATE, "m", MaskStyle.MARKER, "s", MaskStyle.PIXELATE)
        // 参数也一起改：图上不会出现两种粗细的马赛克
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(pixelBlockDivisor = 4)))
        assertThat(vm.state.value.plan.find("s")!!.look.options.pixelBlockDivisor).isEqualTo(4)

        vm.undo(); vm.undo()
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID, MaskStyle.MARKER, MaskStyle.SOLID).inOrder()
    }

    @Test
    fun `switching to a stroke style leaves the masks already made`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        vm.clearSelection()

        vm.setStyle(MaskStyle.MARKER)
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID)
        assertThat(vm.state.value.canUndo).isTrue()                      // 只有画框那一步
        vm.undo()
        assertThat(vm.state.value.canUndo).isFalse()
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
        settings.setRestyleAllMasks(false)
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))
        vm.clearSelection()
        vm.setStyle(MaskStyle.PIXELATE)

        vm.onTap(PointF(50f, 50f))                                         // 点选那个色块
        assertThat(vm.state.value.barLook.style).isEqualTo(MaskStyle.SOLID)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.PIXELATE)

        vm.onTap(PointF(300f, 300f))                                       // 点空白，样式栏回到画笔
        assertThat(vm.state.value.barLook.style).isEqualTo(MaskStyle.PIXELATE)
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

    /** 同一组里换样式，选中的框照旧跟着换；换成的那一种用画笔里调好的参数，不是框画上时的老样子。 */
    @Test
    fun `switching within a group restyles the selected box with the brush's params`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))                            // 色块，记着出厂的颗粒
        vm.clearSelection()
        vm.setStyle(MaskStyle.PIXELATE)
        val mosaic = vm.state.value.barLook
        vm.editLook(mosaic.copy(options = mosaic.options.copy(pixelBlockDivisor = 3)))

        vm.onTap(PointF(50f, 50f))                                         // 选中那个色块
        vm.setStyle(MaskStyle.PIXELATE)
        val item = vm.state.value.plan.items.single()
        assertThat(item.look.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(item.look.options.pixelBlockDivisor).isEqualTo(3)
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
    }

    // ---------- 马克笔、画笔不拿来打码，打码用「打码」里上次选的样式（2026-10-14） ----------

    /** 马克笔不往打码的框上用：选着一个色块框换成马克笔，框不变、选中收起，之后画的才是马克笔。 */
    @Test
    fun `picking the marker leaves the selected mask box alone`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(box(10f, 10f, 90f, 90f))                            // 画完就是选中的
        vm.setStyle(MaskStyle.MARKER)

        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID)
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.barLook.style).isEqualTo(MaskStyle.MARKER)
        vm.onManualBox(box(120f, 120f, 200f, 200f))
        assertThat(vm.looks()).containsExactly(MaskStyle.SOLID, MaskStyle.MARKER).inOrder()
    }

    /** 反过来也一样：选着画笔画的一道线换成色块，线还是线，不会变成一块码。 */
    @Test
    fun `picking a redact style leaves the selected pen stroke alone`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PEN)
        vm.onInk(listOf(PointF(20f, 30f), PointF(60f, 40f), PointF(80f, 30f)))
        vm.setStyle(MaskStyle.SOLID)

        assertThat(vm.looks()).containsExactly(MaskStyle.PEN)
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.SOLID)
    }

    /** 选中一道老的画笔线改它的颜色：画笔只接画笔的那几项，线里记着的老色块颜色不会顶掉「打码」里后来选的颜色。 */
    @Test
    fun `editing an old stroke does not bring back the mask colour it was drawn with`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PEN)
        vm.onInk(listOf(PointF(20f, 30f), PointF(60f, 40f)))               // 这一道线记着出厂的天蓝色块
        vm.clearSelection()
        vm.setStyle(MaskStyle.SOLID)
        val solid = vm.state.value.barLook
        vm.editLook(solid.copy(options = solid.options.copy(solidColor = Color.RED)))

        vm.onTap(PointF(40f, 35f))
        val stroke = vm.state.value.barLook
        assertThat(stroke.style).isEqualTo(MaskStyle.PEN)
        vm.editLook(stroke.copy(options = stroke.options.copy(penColor = Color.BLACK)))

        assertThat(vm.state.value.plan.items.single().look.options.penColor).isEqualTo(Color.BLACK)
        assertThat(vm.state.value.maskLook.style).isEqualTo(MaskStyle.SOLID)
        assertThat(vm.state.value.maskLook.options.solidColor).isEqualTo(Color.RED)
    }

    @Test
    fun `reset puts back only the current style's params`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PIXELATE)
        val mosaic = vm.state.value.barLook
        vm.editLook(mosaic.copy(options = mosaic.options.copy(pixelBlockDivisor = 4)))
        vm.setStyle(MaskStyle.SOLID)
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(solidColor = Color.BLACK)))
        vm.resetLookOptions()
        assertThat(vm.state.value.brush.options.solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(vm.state.value.brush.options.pixelBlockDivisor).isEqualTo(4)
    }

    @Test
    fun `tapping a style only switches to it, tapping it again opens and closes its panel`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onStyleChipClick(MaskStyle.PIXELATE)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.PIXELATE)

        vm.onStyleChipClick(MaskStyle.PIXELATE)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        vm.onStyleChipClick(MaskStyle.PIXELATE)
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        vm.onStyleChipClick(MaskStyle.PIXELATE)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        vm.dismissStylePanel()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
    }

    /** 宽屏上面板开着时样式方块还点得到：换样式不把面板收起来，面板换成新样式的参数。 */
    @Test
    fun `switching style while the panel is open keeps it open`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onStyleChipClick(vm.state.value.barLook.style)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        vm.onStyleChipClick(MaskStyle.MARKER)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.MARKER)
    }

    @Test
    fun `the brush is remembered across image changes and sessions`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("brush-1.jpg")); advanceUntilIdle()
        vm.setStyle(MaskStyle.BLUR)
        val blur = vm.state.value.barLook
        vm.editLook(blur.copy(options = blur.options.copy(blurRadiusRatio = 0.28f)))
        vm.setStyle(MaskStyle.PIXELATE)
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(pixelBlockDivisor = 4)))
        advanceUntilIdle()                                                 // 落盘有去抖

        vm.onImageChosen(sampleUri("brush-2.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(vm.state.value.brush.options.pixelBlockDivisor).isEqualTo(4)

        val next = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { emptyEngine() },
            settings = settings,
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertThat(next.state.value.brush.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(next.state.value.brush.options.blurRadiusRatio).isEqualTo(0.28f)
    }

    /** 下次打开编辑器先是「打码」里上次选的样式，哪怕上次最后拿的是画笔；画笔调好的颜色照样记着。 */
    @Test
    fun `a new session starts on the last redact style even if the pen was in hand`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("pen-1.jpg")); advanceUntilIdle()
        vm.setStyle(MaskStyle.BLUR)
        vm.setStyle(MaskStyle.PEN)
        val pen = vm.state.value.barLook
        vm.editLook(pen.copy(options = pen.options.copy(penColor = Color.BLACK)))
        advanceUntilIdle()                                                 // 落盘有去抖

        val next = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { emptyEngine() },
            settings = settings,
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
        advanceUntilIdle()
        assertThat(next.state.value.brush.style).isEqualTo(MaskStyle.BLUR)
        assertThat(next.state.value.maskLook.style).isEqualTo(MaskStyle.BLUR)
        assertThat(next.state.value.brush.options.penColor).isEqualTo(Color.BLACK)
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
    fun `the eyedropper takes the color under the tap and takes nothing off`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(twoToneUri("eyedropper.png")); advanceUntilIdle()
        vm.replacePlanForTest(vm.state.value.plan.add(rule("rule", 250f, 250f, 350f, 350f)))
        vm.startColorPick(ColorTarget.SOLID)

        vm.onTap(PointF(-20f, 100f))                                       // 点在图外：接着等
        assertThat(vm.state.value.colorPick).isEqualTo(ColorTarget.SOLID)

        vm.onTap(PointF(300f, 300f))                                       // 点在一块码上：只取色，不拿掉
        assertThat(vm.state.value.colorPick).isNull()
        assertThat(vm.state.value.brush.options.solidColor).isEqualTo(Color.rgb(0x20, 0x60, 0xA0))
        assertThat(vm.state.value.plan.find("rule")).isNotNull()
        // 换样式时一起换（出厂开着）：已有的色块跟着换颜色
        assertThat(vm.state.value.plan.find("rule")!!.look.options.solidColor).isEqualTo(Color.rgb(0x20, 0x60, 0xA0))
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

    /** 2026-10-14 起没有「圈出」，导出前也就不再拦一下：图上是什么样就导出什么样。 */
    @Test
    fun `export writes immediately`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.requestExport()
        advanceUntilIdle()

        assertThat(sink.writes).isEqualTo(1)
        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Exported::class.java)
    }

    @Test
    fun `the canvas follows the scan effect chosen in settings, across image changes`() = runTest(dispatcher) {
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.scanStyle.value).isEqualTo(ScanStyle.FROST)

        settings.setScanStyle(ScanStyle.SWEEP); advanceUntilIdle()
        assertThat(vm.scanStyle.value).isEqualTo(ScanStyle.SWEEP)

        // 换图会整个重建 EditorUiState，样式不在那里面，不会被换回出厂值
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.scanStyle.value).isEqualTo(ScanStyle.SWEEP)
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

    // ---------- 画笔的箭头 ----------

    /** 画笔选了箭头：拉一下就是一支箭头，只记头尾两个点，外接框把头也框进去；和别的一笔一样选中、一步撤销。 */
    @Test
    fun `a pen set to arrow draws an arrow from where the finger went down to where it lifted`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PEN)
        val pen = vm.state.value.brush
        vm.editLook(pen.copy(options = pen.options.copy(penShape = StrokeShape.ARROW)))
        vm.onInk(listOf(PointF(40f, 300f), PointF(100f, 250f), PointF(300f, 100f)))

        val item = vm.state.value.plan.items.single()
        assertThat(item.look.style).isEqualTo(MaskStyle.PEN)
        assertThat(item.ink!!.arrow).isTrue()
        assertThat(item.ink!!.points).hasSize(2)
        val half = item.look.options.halfWidth(MaskStyle.PEN, 400f)
        val back = Ink.toImage(item.ink!!, item.quad, Ink.margin(half, arrow = true))
        assertThat(back.first().x).isWithin(0.01f).of(40f)
        assertThat(back.last().y).isWithin(0.01f).of(100f)
        assertThat(item.quad.bounds().right).isAtLeast(300f + half * Ink.ARROW_HEAD_HALF - 0.01f)
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
        vm.undo()
        assertThat(vm.state.value.plan.items).isEmpty()
    }

    /** 选着一支箭头拿起马克笔：马克笔画不出箭头，箭头原样留着，选中收起。换成画笔的拉框、手画，它还是箭头。 */
    @Test
    fun `an arrow never turns into a marker stroke`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PEN)
        val pen = vm.state.value.brush
        vm.editLook(pen.copy(options = pen.options.copy(penShape = StrokeShape.ARROW)))
        vm.onInk(listOf(PointF(40f, 300f), PointF(300f, 100f)))
        val arrow = vm.state.value.selectedManualId!!

        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(penShape = StrokeShape.BOX, penColor = Color.BLACK)))
        val kept = vm.state.value.plan.find(arrow)!!
        assertThat(kept.ink!!.arrow).isTrue()
        assertThat(kept.look.options.penColor).isEqualTo(Color.BLACK)

        vm.setStyle(MaskStyle.MARKER)
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.plan.find(arrow)!!.look.style).isEqualTo(MaskStyle.PEN)
    }

    /** 选着一支箭头调粗细：框跟着放宽收窄，箭头指的地方不动。手画的一笔也一样。 */
    @Test
    fun `a new width keeps an arrow pointing where it was drawn`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.setStyle(MaskStyle.PEN)
        val pen = vm.state.value.brush
        vm.editLook(pen.copy(options = pen.options.copy(penShape = StrokeShape.ARROW)))
        vm.onInk(listOf(PointF(100f, 200f), PointF(300f, 250f)))
        val id = vm.state.value.selectedManualId!!
        val bar = vm.state.value.barLook
        vm.editLook(bar.copy(options = bar.options.copy(penWidth = MaskOptions.PEN_WIDTH_RANGE.endInclusive)), inProgress = true)
        vm.editLook(vm.state.value.barLook.copy(options = vm.state.value.barLook.options.copy(penWidth = MaskOptions.PEN_WIDTH_RANGE.start)))

        val item = vm.state.value.plan.find(id)!!
        val half = item.look.options.halfWidth(MaskStyle.PEN, 400f)
        val (tail, tip) = Ink.toImage(item.ink!!, item.quad, Ink.margin(half, arrow = true)).let { it.first() to it.last() }
        assertThat(tail.x).isWithin(0.01f).of(100f)
        assertThat(tail.y).isWithin(0.01f).of(200f)
        assertThat(tip.x).isWithin(0.01f).of(300f)
        assertThat(tip.y).isWithin(0.01f).of(250f)
        assertThat(item.quad.bounds().right).isWithin(0.01f).of(300f + half * Ink.ARROW_HEAD_HALF)

        vm.clearSelection()
        vm.editLook(vm.state.value.brush.copy(options = vm.state.value.brush.options.copy(penShape = StrokeShape.FREEHAND)))
        vm.onInk(listOf(PointF(50f, 50f), PointF(90f, 80f), PointF(150f, 60f)))
        val stroke = vm.state.value.selectedItem!!
        val before = Ink.toImage(stroke.ink!!, stroke.quad, stroke.look.options.halfWidth(MaskStyle.PEN, 400f))
        vm.editLook(vm.state.value.barLook.copy(options = vm.state.value.barLook.options.copy(penWidth = MaskOptions.PEN_WIDTH_RANGE.endInclusive)))
        val after = vm.state.value.selectedItem!!
        val moved = Ink.toImage(after.ink!!, after.quad, after.look.options.halfWidth(MaskStyle.PEN, 400f))
        before.zip(moved).forEach { (a, b) ->
            assertThat(b.x).isWithin(0.01f).of(a.x)
            assertThat(b.y).isWithin(0.01f).of(a.y)
        }
    }
}
