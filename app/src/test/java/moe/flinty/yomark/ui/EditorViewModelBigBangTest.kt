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
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
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
 * 大爆炸（spec §7.3，2026-10-16 增补）：长按字打开，点字、划字当场打码、拿掉，挨着的字打成一块，
 * 收起时整次是一步撤销。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelBigBangTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private object NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://fake/1")
    }

    private class Recognizer(private val lines: List<TextLine>) : TextRecognizer {
        override val id = "stub"
        override suspend fun recognize(image: SourceImage): List<TextLine> = lines
    }

    private class StubClassifier(private val out: List<Candidate>) : SensitivityClassifier {
        override val id = "stub"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = out
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    /** 像 PP-OCR 那样一字一个 element，字宽 20、字高 20，从 [left] 起。 */
    private fun ppLine(text: String, left: Float, top: Float): TextLine {
        val els = text.indices.filter { text[it] != ' ' }.map { i ->
            TextElement(rect(left + i * 20f, top, left + (i + 1) * 20f, top + 20f), text[i].toString(), i..i)
        }
        return TextLine(rect(left, top, left + text.length * 20f, top + 20f), text, 0.95f, els)
    }

    /** 两行：「收货人张三」在 40..140 × 100..120，「电话13812345678」在 40..300 × 150..170。 */
    private val name = ppLine("收货人张三", 40f, 100f)
    private val phone = ppLine("电话13812345678", 40f, 150f)

    private fun vm(lines: List<TextLine> = listOf(name, phone), candidates: List<Candidate> = emptyList(), saved: SavedStateHandle = SavedStateHandle()) =
        EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink),
            engineProvider = { RedactionEngine(Recognizer(lines), emptyList(), listOf(StubClassifier(candidates)), CandidateMerger()) },
            settings = isolatedSettingsStore(context),
            savedState = saved,
            ioDispatcher = dispatcher,
        )

    private fun sampleUri(name: String = "big-bang.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private fun EditorViewModel.bang() = state.value.bigBang

    private fun EditorViewModel.boxes() = state.value.plan.items.map { it.quad.bounds() }

    @Test fun `a long press on a character opens big bang and masks that character right away`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(vm.onLongPress(PointF(110f, 110f), 4f)).isTrue()
        val bang = vm.bang()!!
        assertThat(bang.selected).containsExactly(3)                          // 「张」
        assertThat(bang.focusLine).isEqualTo(0)
        assertThat(bang.page.glyphs).hasSize(18)
        assertThat(vm.boxes()).containsExactly(RectF(100f, 100f, 120f, 120f))  // 点字就是打码
    }

    /** 面板等长按的手指抬起来再出来：一出来画布就变矮，手指底下就不是刚才那几个字了。 */
    @Test fun `the panel waits until the long-press finger lifts`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        assertThat(vm.bang()!!.holding).isTrue()
        vm.sweepBigBang(listOf(4))                                              // 手指没抬，接着划
        vm.releaseBigBang()
        assertThat(vm.bang()!!.holding).isFalse()
        assertThat(vm.bang()!!.selected).containsExactly(3, 4)
    }

    @Test fun `a long press away from text does nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.onLongPress(PointF(300f, 350f), 4f)).isFalse()
        assertThat(vm.bang()).isNull()
        assertThat(vm.state.value.plan.items).isEmpty()
    }

    @Test fun `consecutive characters are one mask per run, and the whole session is one undo step`() = runTest(dispatcher) {
        val vm = vm()
        vm.setStyle(MaskStyle.PIXELATE)
        vm.setStyle(MaskStyle.PEN)                                              // 手里拿着画笔
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onLongPress(PointF(110f, 110f), 4f)                                  // 「张」
        vm.sweepBigBang(listOf(4))                                              // 接着划到「三」
        vm.releaseBigBang()
        // 号码中间四位：「13812345678」里的 1234，是这一行第 2+3..2+6 个字
        vm.toggleBigBangGlyph(5 + 2 + 3)
        vm.startBigBangSweep(5 + 2 + 4)
        vm.sweepBigBang(listOf(5 + 2 + 5, 5 + 2 + 6))

        val items = vm.state.value.plan.items
        assertThat(vm.boxes()).containsExactly(RectF(100f, 100f, 140f, 120f), RectF(140f, 150f, 220f, 170f)).inOrder()
        assertThat(items.map { it.source }.toSet()).containsExactly(DetectorSource.LINE)
        assertThat(items.map { it.kind }.toSet()).containsExactly(SensitiveKind.MANUAL)
        assertThat(items.map { it.look.style }.toSet()).containsExactly(MaskStyle.PIXELATE)   // 「打码」里的样式，不是画笔
        assertThat(vm.state.value.canUndo).isFalse()                            // 开着时一下一下都不进撤销栈

        vm.closeBigBang()
        assertThat(vm.bang()).isNull()
        assertThat(vm.boxes()).hasSize(2)                                       // 收起来，码留着
        assertThat(vm.state.value.canUndo).isTrue()
        vm.undo()
        assertThat(vm.state.value.plan.items).isEmpty()                          // 一下撤销整次
    }

    /** 块往后长、往前长还是同一块码（id 不变）：划字时画布上不会每多一个字就重新抹一遍。 */
    @Test fun `a growing run keeps its mask`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)                                  // 「张」
        val id = vm.state.value.plan.items.single().candidateId
        vm.sweepBigBang(listOf(4))                                              // 往后
        vm.startBigBangSweep(2)                                                 // 往前
        assertThat(vm.state.value.plan.items.single().candidateId).isEqualTo(id)
        assertThat(vm.boxes()).containsExactly(RectF(80f, 100f, 140f, 120f))
    }

    @Test fun `a sweep that starts on a selected character takes the masks off`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(50f, 110f), 4f)                                   // 「收」
        vm.sweepBigBang(listOf(1, 2, 3))
        assertThat(vm.bang()!!.selected).containsExactly(0, 1, 2, 3)

        vm.startBigBangSweep(2)
        vm.sweepBigBang(listOf(3))
        assertThat(vm.bang()!!.selected).containsExactly(0, 1)
        assertThat(vm.boxes()).containsExactly(RectF(40f, 100f, 80f, 120f))
    }

    @Test fun `taps on the canvas mask and unmask characters while big bang is open`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)

        vm.onTap(PointF(130f, 110f), 4f)                                         // 「三」打上
        assertThat(vm.bang()!!.selected).containsExactly(3, 4)
        assertThat(vm.boxes()).containsExactly(RectF(100f, 100f, 140f, 120f))
        vm.onTap(PointF(110f, 110f), 4f)                                         // 「张」拿掉
        assertThat(vm.bang()!!.selected).containsExactly(4)
        assertThat(vm.boxes()).containsExactly(RectF(120f, 100f, 140f, 120f))
        vm.onTap(PointF(300f, 350f), 4f)                                         // 空白：什么也不做
        assertThat(vm.bang()!!.selected).containsExactly(4)
    }

    /** 一指划着字、第二根手指落下来变成了缩放平移：这一划作罢，回到起手前。 */
    @Test fun `a sweep turned into a pinch is rolled back`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.releaseBigBang()
        val before = vm.bang()!!.selected
        val boxes = vm.boxes()
        vm.startBigBangSweep(0)
        vm.sweepBigBang(listOf(1, 2))
        vm.restoreBigBangSelection(before)
        assertThat(vm.bang()!!.selected).isEqualTo(before)
        assertThat(vm.boxes()).isEqualTo(boxes)
    }

    /** 开着时用途水印的面板开不出来：面板的位置归大爆炸，开出来也看不见。 */
    @Test fun `the purpose sheet does not open underneath big bang`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.togglePurposeSheet()
        assertThat(vm.state.value.purposeSheetVisible).isFalse()
        assertThat(vm.bang()).isNotNull()
    }

    /** 开着时按撤销：先收起，撤销的就是这一次打的码。 */
    @Test fun `undo while open closes big bang and takes back the session`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(rect(200f, 300f, 300f, 380f))                            // 之前的一步
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.sweepBigBang(listOf(4))
        vm.undo()
        assertThat(vm.bang()).isNull()
        assertThat(vm.boxes()).containsExactly(RectF(200f, 300f, 300f, 380f))   // 手动框还在
        vm.redo()
        assertThat(vm.boxes()).hasSize(2)
    }

    /** 收起来什么也没打（长按的那个字又点掉了）：不留一步空的撤销。 */
    @Test fun `a session that ends with nothing masked leaves no undo step`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.toggleBigBangGlyph(3)
        vm.closeBigBang()
        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test fun `a run already covered by a mask is not masked twice`() = runTest(dispatcher) {
        val covered = Candidate("rule-name", rect(98f, 98f, 142f, 122f), SensitiveKind.PERSON_NAME, DetectorSource.RULE, 0.9f)
        val vm = vm(candidates = listOf(covered))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.plan.items).hasSize(1)

        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.sweepBigBang(listOf(4))
        assertThat(vm.state.value.plan.items).hasSize(1)
        vm.closeBigBang()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test fun `opening big bang clears the selected box and the open style panel`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(rect(200f, 300f, 300f, 380f))
        vm.onStyleChipClick(vm.state.value.barLook.style)                       // 面板开着
        assertThat(vm.state.value.selectedManualId).isNotNull()
        assertThat(vm.state.value.stylePanelOpen).isTrue()

        vm.onLongPress(PointF(110f, 110f), 4f)
        assertThat(vm.state.value.selectedManualId).isNull()
        assertThat(vm.state.value.stylePanelOpen).isFalse()
        // 开着时一指拉的框不算数（画布上一指划的是字）
        vm.onManualBox(rect(10f, 300f, 100f, 380f))
        assertThat(vm.state.value.plan.items).hasSize(2)                        // 手动框和「张」那一块
    }

    /** 重跑识别：大爆炸收起（摆的字对不上了），打上的码和点字打的一样留着。 */
    @Test fun `a re-run of recognition closes big bang and keeps its masks`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.sweepBigBang(listOf(4))
        vm.rerunRecognition()
        assertThat(vm.bang()).isNull()
        advanceUntilIdle()
        assertThat(vm.bang()).isNull()
        assertThat(vm.boxes()).containsExactly(RectF(100f, 100f, 140f, 120f))
    }

    /** 重建之后只恢复了每一段的框，没有字：长按照旧，不打开；底栏的大爆炸灰着。 */
    @Test fun `after a restore there is no text to explode`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        vm(saved = saved).apply { onImageChosen(sampleUri()) }
        advanceUntilIdle()
        val restored = vm(saved = saved)
        advanceUntilIdle()
        assertThat(restored.state.value.image).isNotNull()
        assertThat(restored.onLongPress(PointF(110f, 110f), 4f)).isFalse()
        assertThat(restored.hasText.value).isFalse()
        restored.openBigBang()
        assertThat(restored.bang()).isNull()
    }

    /** 底栏「工具」里点开（2026-10-16）：面板马上出来，从第一行摆起，一个字也没打；打的码照样整次一步撤销。 */
    @Test fun `the toolbar opens big bang with nothing picked`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri())
        assertThat(vm.hasText.value).isFalse()                                  // 还没识别完
        advanceUntilIdle()
        assertThat(vm.hasText.value).isTrue()

        vm.openBigBang()
        val bang = vm.bang()!!
        assertThat(bang.holding).isFalse()
        assertThat(bang.selected).isEmpty()
        assertThat(bang.focusLine).isEqualTo(0)
        assertThat(vm.state.value.plan.items).isEmpty()

        vm.toggleBigBangGlyph(3)                                                // 「张」
        vm.closeBigBang()
        assertThat(vm.boxes()).containsExactly(RectF(100f, 100f, 120f, 120f))
        vm.undo()
        assertThat(vm.state.value.plan.items).isEmpty()
    }

    @Test fun `the toolbar does not open big bang on a page without text`() = runTest(dispatcher) {
        val vm = vm(lines = emptyList())
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.hasText.value).isFalse()
        vm.openBigBang()
        assertThat(vm.bang()).isNull()
    }
}
