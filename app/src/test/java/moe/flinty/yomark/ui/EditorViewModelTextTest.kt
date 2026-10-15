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
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.render.TextStamp
import moe.flinty.yomark.ui.canvas.HandleCorner
import moe.flinty.yomark.ui.canvas.SelectionHandles
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 文本（2026-10-15）：拿着文本点图上一处写字，打下第一个字时落下，改字、改字号、拖角都当场变，整段一步撤销。
 * 面板、输入框那一段见 StyleBarTest，画出来什么样见 TextRendererTest。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelTextTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** 记下导出的那张图，看字有没有画上去。 */
    private class KeepingSink : ImageSink {
        var last: Bitmap? = null
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri {
            last = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            return Uri.parse("content://fake/1")
        }
    }

    private val sink = KeepingSink()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun vm() = EditorViewModel(
        intake = ImageIntake(context),
        exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
        engineProvider = {
            RedactionEngine(
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
        },
        settings = isolatedSettingsStore(context),
        savedState = SavedStateHandle(),
        ioDispatcher = dispatcher,
    )

    private fun sampleUri(): Uri {
        val f = File(context.cacheDir, "text.png")
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f.toUri()
    }

    private suspend fun kotlinx.coroutines.test.TestScope.opened(): EditorViewModel {
        val vm = vm()
        vm.onImageChosen(sampleUri())
        advanceUntilIdle()
        return vm
    }

    private val EditorViewModel.items get() = state.value.plan.items
    private val EditorViewModel.text get() = items.single { it.look.style == MaskStyle.TEXT }
    private fun MaskItem.center() = quad.bounds().let { PointF(it.centerX(), it.centerY()) }

    /** 拿着文本写下 [text]，中心在 [at]。 */
    private fun EditorViewModel.write(text: String, at: PointF = PointF(150f, 120f)) {
        setStyle(MaskStyle.TEXT)
        onTap(at)
        setText(text)
    }

    @Test fun `with the text tool a tap opens the panel there and the first character drops the text`() = runTest(dispatcher) {
        val vm = opened()
        vm.setStyle(MaskStyle.TEXT)
        vm.onTap(PointF(150f, 120f))
        val waiting = vm.state.value
        assertThat(waiting.textPlacement?.center).isEqualTo(PointF(150f, 120f))
        assertThat(waiting.stylePanelOpen).isTrue()
        assertThat(waiting.textFocus).isTrue()
        assertThat(waiting.plan.items).isEmpty()                 // 一个字没打，什么也没落下

        vm.setText("H")
        val item = vm.text
        assertThat(item.text).isEqualTo("H")
        assertThat(item.source).isEqualTo(DetectorSource.MANUAL)
        assertThat(item.candidateId).isEqualTo(waiting.textPlacement!!.id)
        assertThat(vm.state.value.selectedManualId).isEqualTo(item.candidateId)
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(item.center().x).isWithin(0.01f).of(150f)
        assertThat(item.center().y).isWithin(0.01f).of(120f)
        assertThat(vm.state.value.canUndo).isTrue()
    }

    @Test fun `typing keeps the text centred and the whole of it is one undo step`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("H")
        val first = vm.text.quad.bounds()
        vm.setText("He")
        vm.setText("Hello")
        val grown = vm.text.quad.bounds()
        assertThat(grown.width()).isGreaterThan(first.width())
        assertThat(grown.centerX()).isWithin(0.01f).of(first.centerX())
        assertThat(grown.height()).isWithin(0.01f).of(first.height())

        vm.dismissStylePanel()
        vm.undo()
        assertThat(vm.items).isEmpty()
        vm.redo()
        assertThat(vm.text.text).isEqualTo("Hello")
    }

    @Test fun `deleting every character takes the text off and typing again puts it back in place`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        val before = vm.text
        vm.setText("")
        assertThat(vm.items).isEmpty()
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.textPlacement?.id).isEqualTo(before.candidateId)
        vm.setText("Yo")
        val after = vm.text
        assertThat(after.candidateId).isEqualTo(before.candidateId)
        assertThat(after.center().x).isWithin(0.01f).of(before.center().x)
        assertThat(after.center().y).isWithin(0.01f).of(before.center().y)
    }

    /** 字删光了，那一块带着它自己的样子等着：这时调颜色，调的是它，接着打出来就是新颜色。 */
    @Test fun `a cleared text keeps its own look and the panel edits it`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        val look = vm.text.look
        vm.editLook(look.copy(options = look.options.copy(textBackground = true)))
        vm.setText("")
        assertThat(vm.state.value.barLook.options.textBackground).isTrue()
        vm.editLook(vm.state.value.barLook.copy(options = vm.state.value.barLook.options.copy(textColor = 0xFF1E88E5.toInt())))
        vm.setText("Yo")
        assertThat(vm.text.look.options.textBackground).isTrue()
        assertThat(vm.text.look.options.textColor).isEqualTo(0xFF1E88E5.toInt())
    }

    @Test fun `a tap elsewhere while a text is selected only deselects, the next one writes`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        vm.dismissStylePanel()
        vm.onTap(PointF(300f, 330f))
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(vm.items).hasSize(1)
        vm.onTap(PointF(300f, 330f))
        assertThat(vm.state.value.textPlacement?.center).isEqualTo(PointF(300f, 330f))
    }

    @Test fun `tapping the selected text again opens it for editing`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        vm.dismissStylePanel()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        vm.onTap(vm.text.center())
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        assertThat(vm.state.value.textFocus).isTrue()
        assertThat(vm.state.value.selectedText).isNotNull()
        vm.textFocusTaken()
        assertThat(vm.state.value.textFocus).isFalse()
    }

    /** 不拿着文本时点一下照旧：选中的文本再点一下也是改字，点别处只是取消选中，不会开始写字。 */
    @Test fun `without the text tool a tap never starts writing`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        vm.dismissStylePanel()
        vm.setStyle(MaskStyle.PEN)
        vm.onTap(PointF(300f, 330f))
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        vm.onTap(vm.text.center())                      // 选中它
        vm.onTap(vm.text.center())                      // 再点一下：改字
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        assertThat(vm.state.value.barLook.style).isEqualTo(MaskStyle.TEXT)
    }

    /** 拿着文本点到识别出的码上：照旧拿掉它，不在码上写字。 */
    @Test fun `with the text tool a tap on a detected mask still removes it`() = runTest(dispatcher) {
        val vm = opened()
        val detected = MaskItem("rule-1", Quad.fromRect(RectF(200f, 200f, 300f, 240f)), SensitiveKind.PHONE, DetectorSource.RULE)
        vm.replacePlanForTest(MaskPlan(listOf(detected)))
        vm.setStyle(MaskStyle.TEXT)
        vm.onTap(PointF(250f, 220f))
        assertThat(vm.items).isEmpty()
        assertThat(vm.state.value.textPlacement).isNull()
    }

    @Test fun `typing into the open panel with nothing picked writes in the middle of the image`() = runTest(dispatcher) {
        val vm = opened()
        vm.setStyle(MaskStyle.TEXT)
        vm.onStyleChipClick(MaskStyle.TEXT)
        assertThat(vm.state.value.stylePanelOpen).isTrue()
        vm.setText("Hi")
        assertThat(vm.text.center().x).isWithin(0.01f).of(200f)
        assertThat(vm.text.center().y).isWithin(0.01f).of(200f)
    }

    @Test fun `dragging a corner scales the text evenly and holds the opposite corner`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hello")
        vm.dismissStylePanel()
        val before = vm.text
        val b = before.quad.bounds()
        val dragged = SelectionHandles.resize(before.quad, HandleCorner.BOTTOM_RIGHT, PointF(b.right + 60f, b.bottom), 4f)
        vm.previewSelectedQuad(dragged)
        vm.commitDrag()
        val after = vm.text
        val a = after.quad.bounds()
        assertThat(a.left).isWithin(0.01f).of(b.left)
        assertThat(a.top).isWithin(0.01f).of(b.top)
        assertThat(a.width()).isWithin(0.5f).of(b.width() + 60f)
        assertThat(a.width() / a.height()).isWithin(0.01f).of(b.width() / b.height())   // 字没有变形
        assertThat(after.look.options.textSize).isGreaterThan(before.look.options.textSize)
        // 字号跟着框：照新框画出来的字号就是记下的字号
        assertThat(TextStamp.sizeIn("Hello", after.quad) / 400f).isWithin(0.0005f).of(after.look.options.textSize)

        vm.undo()
        assertThat(vm.text.quad).isEqualTo(before.quad)
    }

    @Test fun `dragging the top left corner holds the bottom right one`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hello", at = PointF(200f, 200f))
        val b = vm.text.quad.bounds()
        vm.previewSelectedQuad(SelectionHandles.resize(vm.text.quad, HandleCorner.TOP_LEFT, PointF(b.left + 10f, b.top + 20f), 4f))
        val a = vm.text.quad.bounds()
        assertThat(a.right).isWithin(0.01f).of(b.right)
        assertThat(a.bottom).isWithin(0.01f).of(b.bottom)
        assertThat(a.height()).isLessThan(b.height())
    }

    @Test fun `moving a text keeps its size`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hello")
        val before = vm.text
        vm.previewSelectedQuad(SelectionHandles.move(before.quad, 30f, 40f))
        vm.commitDrag()
        val a = vm.text.quad.bounds()
        val b = before.quad.bounds()
        assertThat(a.left).isWithin(0.01f).of(b.left + 30f)
        assertThat(a.top).isWithin(0.01f).of(b.top + 40f)
        assertThat(a.width()).isWithin(0.01f).of(b.width())
        assertThat(vm.text.look.options.textSize).isEqualTo(before.look.options.textSize)
    }

    @Test fun `a new size in the panel resizes the selected text around its centre`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi", at = PointF(200f, 200f))
        val before = vm.text
        val look = vm.state.value.barLook
        vm.editLook(look.copy(options = look.options.copy(textSize = before.look.options.textSize * 2f)))
        val after = vm.text
        assertThat(after.quad.bounds().height()).isWithin(0.05f).of(before.quad.bounds().height() * 2f)
        assertThat(after.center().x).isWithin(0.01f).of(before.center().x)
        assertThat(after.center().y).isWithin(0.01f).of(before.center().y)
        // 画笔也是这个字号：下一块文本一样大
        assertThat(vm.state.value.brush.options.textSize).isEqualTo(after.look.options.textSize)
    }

    /** 文本只和文本换：拿起画笔时选中的文本原样留着，选中收起；选着一道线拿起文本也一样。 */
    @Test fun `switching between text and pen never turns one into the other`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        vm.dismissStylePanel()
        vm.setStyle(MaskStyle.PEN)
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.text.text).isEqualTo("Hi")

        val pen = vm.state.value.brush
        vm.editLook(pen.copy(options = pen.options.copy(penShape = StrokeShape.BOX)))
        vm.onManualBox(Quad.fromRect(RectF(20f, 300f, 120f, 380f)))
        val stroke = vm.state.value.selectedItem!!
        assertThat(stroke.look.style).isEqualTo(MaskStyle.PEN)
        vm.setStyle(MaskStyle.TEXT)
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.plan.find(stroke.candidateId)!!.look.style).isEqualTo(MaskStyle.PEN)
    }

    /** 拿着文本拉一个框：在框的中心写字，字号照框的高。 */
    @Test fun `a box drawn with the text tool sets where and how big the text is`() = runTest(dispatcher) {
        val vm = opened()
        vm.setStyle(MaskStyle.TEXT)
        vm.onManualBox(Quad.fromRect(RectF(50f, 50f, 250f, 110f)))
        assertThat(vm.items).isEmpty()
        assertThat(vm.state.value.textPlacement?.center).isEqualTo(PointF(150f, 80f))
        assertThat(vm.state.value.brush.options.textSize).isWithin(0.0005f).of(TextStamp.sizeForHeight(60f) / 400f)
        vm.setText("Hi")
        assertThat(vm.text.quad.bounds().height()).isWithin(0.05f).of(60f)
    }

    @Test fun `undo while a text is waiting for its first character drops the wait`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        vm.dismissStylePanel()
        vm.onTap(PointF(10f, 10f))                      // 取消选中
        vm.onTap(PointF(300f, 300f))                    // 等着写字
        assertThat(vm.state.value.textPlacement).isNotNull()
        vm.undo()
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(vm.items).isEmpty()
    }

    /** 收起面板时一个字也没打：什么也不留，撤销栈里也不多一步。 */
    @Test fun `folding the panel before typing leaves nothing behind`() = runTest(dispatcher) {
        val vm = opened()
        vm.setStyle(MaskStyle.TEXT)
        vm.onTap(PointF(100f, 100f))
        vm.dismissStylePanel()
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(vm.items).isEmpty()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test fun `a pasted line break becomes a space`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("two\nlines")
        assertThat(vm.text.text).isEqualTo("two lines")
    }

    @Test fun `the text is drawn on the exported image`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("WWW", at = PointF(200f, 200f))
        val look = vm.state.value.barLook
        vm.editLook(look.copy(options = look.options.copy(textSize = 0.12f)))
        vm.dismissStylePanel()
        vm.requestExport()
        advanceUntilIdle()
        val out = sink.last!!
        val box = vm.text.quad.scaled(out.width / 400f).bounds()
        var inked = 0
        for (y in box.top.toInt() until box.bottom.toInt()) for (x in box.left.toInt() until box.right.toInt()) {
            if (out.getPixel(x, y) == MaskOptions.DEFAULT_TEXT_COLOR) inked++
        }
        assertThat(inked).isGreaterThan(200)
        assertThat(out.getPixel(5, 5)).isEqualTo(Color.WHITE)
    }

    @Test fun `a text survives a parcel round trip`() {
        val item = MaskItem(
            "manual-t", Quad.fromRect(RectF(1f, 2f, 30f, 12f)), SensitiveKind.MANUAL, DetectorSource.MANUAL,
            MaskLook(MaskStyle.TEXT, MaskOptions(textColor = 0xFF1E88E5.toInt(), textSize = 0.08f, textBackground = true)),
            text = "签收人",
        )
        val parcel = android.os.Parcel.obtain()
        try {
            parcel.writeParcelable(MaskPlan(listOf(item)), 0)
            parcel.setDataPosition(0)
            val back = parcel.readParcelable<MaskPlan>(javaClass.classLoader)
            assertThat(back).isEqualTo(MaskPlan(listOf(item)))
        } finally {
            parcel.recycle()
        }
    }

    // ---------- 边上、叠放、撤销的细处 ----------

    /** 贴着图边写一长串，删短了：回到点的那一处，不留在被挤进来的地方。 */
    @Test fun `a text near the edge comes back to where it was tapped when it gets shorter`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("H", at = PointF(380f, 200f))
        vm.setText("Hello there, this is long")
        assertThat(vm.text.quad.bounds().right).isAtMost(400f)
        vm.setText("Hi")
        assertThat(vm.text.center().x).isWithin(0.01f).of(380f)
    }

    /** 拖角放大到出了图：停在图边上，导出的字一个也不少。 */
    @Test fun `scaling a text up stops at the edge of the image`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hello", at = PointF(330f, 200f))
        val b = vm.text.quad.bounds()
        vm.previewSelectedQuad(SelectionHandles.resize(vm.text.quad, HandleCorner.BOTTOM_RIGHT, PointF(b.right + 300f, b.bottom + 100f), 4f))
        val a = vm.text.quad.bounds()
        assertThat(a.right).isAtMost(400.01f)
        assertThat(a.left).isAtLeast(0f)
        assertThat(a.width()).isGreaterThan(b.width())
    }

    /** 一长行缩到正好写得下，字号比下限还小：拖角往里收一点，不会先被拽到下限、一下子比图还宽。 */
    @Test fun `a line shrunk to fit is not pulled up to the minimum size by a drag`() = runTest(dispatcher) {
        val vm = opened()
        val long = "A very long line of text that cannot possibly fit on this small image at any normal size at all"
        vm.write(long, at = PointF(200f, 200f))
        val b = vm.text.quad.bounds()
        assertThat(TextStamp.sizeIn(long, vm.text.quad)).isLessThan(MaskOptions.TEXT_SIZE_RANGE.start * 400f)
        vm.previewSelectedQuad(SelectionHandles.resize(vm.text.quad, HandleCorner.BOTTOM_RIGHT, PointF(b.right - 10f, b.bottom), 1f))
        assertThat(vm.text.quad.bounds().width()).isAtMost(b.width())
        assertThat(vm.text.quad.bounds().right).isAtMost(400.01f)
    }

    /** 字删光再打：还在原来那一层，不跑到后画的码上面去。 */
    @Test fun `retyping a cleared text keeps its layer`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi", at = PointF(200f, 200f))
        val text = vm.text.candidateId
        vm.dismissStylePanel()
        vm.setStyle(MaskStyle.SOLID)
        vm.onManualBox(Quad.fromRect(RectF(150f, 150f, 250f, 250f)))
        val box = vm.state.value.selectedManualId!!
        vm.onListItemClick(text)
        vm.editSelectedText()
        vm.setText("")
        vm.setText("Yo")
        val order = vm.items.map { it.candidateId }
        assertThat(order.indexOf(text)).isLessThan(order.indexOf(box))
    }

    /** 打完字再换颜色：是另一步。撤销一下回到原来的颜色，字还在。 */
    @Test fun `a colour picked after typing is its own undo step`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        val look = vm.state.value.barLook
        vm.editLook(look.copy(options = look.options.copy(textColor = 0xFF1E88E5.toInt())))
        vm.dismissStylePanel()
        vm.undo()
        assertThat(vm.text.text).isEqualTo("Hi")
        assertThat(vm.text.look.options.textColor).isEqualTo(MaskOptions.DEFAULT_TEXT_COLOR)
        vm.undo()
        assertThat(vm.items).isEmpty()
    }

    /** 撤销之后写了字：重做没得做了，按钮当场灰掉。 */
    @Test fun `writing after an undo greys out redo at once`() = runTest(dispatcher) {
        val vm = opened()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 90f, 90f)))
        vm.undo()
        assertThat(vm.state.value.canRedo).isTrue()
        vm.write("a")
        assertThat(vm.state.value.canRedo).isFalse()
        assertThat(vm.state.value.canUndo).isTrue()
    }

    /** 撤销把选中的文本撤没了：再点一下图就是写字，不用先点一下「取消选中」。 */
    @Test fun `after undoing a text the next tap writes again`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        vm.dismissStylePanel()
        vm.undo()
        assertThat(vm.items).isEmpty()
        vm.onTap(PointF(300f, 300f))
        assertThat(vm.state.value.textPlacement?.center).isEqualTo(PointF(300f, 300f))
    }

    /** 字删光了还等着接着打，这时拿马克笔画了一笔：等着的那一处作罢，不留一道光标。 */
    @Test fun `drawing with another tool drops a text waiting for its first character`() = runTest(dispatcher) {
        val vm = opened()
        vm.write("Hi")
        val text = vm.text.candidateId
        vm.dismissStylePanel()
        vm.setStyle(MaskStyle.MARKER)
        vm.onListItemClick(text)
        vm.editSelectedText()
        vm.setText("")
        assertThat(vm.state.value.textPlacement).isNotNull()
        vm.onManualBox(Quad.fromRect(RectF(10f, 300f, 90f, 380f)))
        assertThat(vm.state.value.textPlacement).isNull()
        assertThat(vm.state.value.selectedItem!!.look.style).isEqualTo(MaskStyle.MARKER)
    }
}
