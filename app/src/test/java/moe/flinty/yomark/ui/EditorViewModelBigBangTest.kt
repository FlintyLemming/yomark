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
 * 大爆炸（spec §7.3，2026-10-16 增补）：长按字打开，点、划选字，打码时挨着的字打成一块，整批是一步撤销。
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

    @Test fun `a long press on a character opens big bang with that character selected`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(vm.onLongPress(PointF(110f, 110f), 4f)).isTrue()
        val bang = vm.bang()!!
        assertThat(bang.selected).containsExactly(3)                          // 「张」
        assertThat(bang.focusLine).isEqualTo(0)
        assertThat(bang.page.glyphs).hasSize(18)
        assertThat(vm.state.value.plan.items).isEmpty()                         // 长按本身不打码
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
    }

    @Test fun `masking merges consecutive characters into one box per run, as one undo step`() = runTest(dispatcher) {
        val vm = vm()
        vm.setStyle(MaskStyle.PIXELATE)
        vm.setStyle(MaskStyle.PEN)                                              // 手里拿着画笔
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onLongPress(PointF(110f, 110f), 4f)                                  // 「张」
        vm.sweepBigBang(listOf(4))                                              // 接着划到「三」
        // 号码中间四位：「1381234 5678」里的 1234，是第 2+3..2+6 个字
        vm.toggleBigBangGlyph(5 + 2 + 3)
        vm.startBigBangSweep(5 + 2 + 4)
        vm.sweepBigBang(listOf(5 + 2 + 5, 5 + 2 + 6))
        vm.maskBigBang()

        val items = vm.state.value.plan.items
        assertThat(items.map { it.quad.bounds() })
            .containsExactly(RectF(100f, 100f, 140f, 120f), RectF(140f, 150f, 220f, 170f)).inOrder()
        assertThat(items.map { it.source }.toSet()).containsExactly(DetectorSource.LINE)
        assertThat(items.map { it.kind }.toSet()).containsExactly(SensitiveKind.MANUAL)
        assertThat(items.map { it.look.style }.toSet()).containsExactly(MaskStyle.PIXELATE)   // 「打码」里的样式，不是画笔
        assertThat(vm.bang()).isNull()

        vm.undo()
        assertThat(vm.state.value.plan.items).isEmpty()                          // 一下撤销整批
    }

    @Test fun `a sweep that starts on a selected character deselects`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(50f, 110f), 4f)                                   // 「收」
        vm.sweepBigBang(listOf(1, 2, 3))
        assertThat(vm.bang()!!.selected).containsExactly(0, 1, 2, 3)

        vm.startBigBangSweep(2)
        vm.sweepBigBang(listOf(3))
        assertThat(vm.bang()!!.selected).containsExactly(0, 1)
    }

    @Test fun `taps on the canvas toggle characters while big bang is open, and never mask`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)

        vm.onTap(PointF(130f, 110f), 4f)                                         // 「三」选上
        assertThat(vm.bang()!!.selected).containsExactly(3, 4)
        vm.onTap(PointF(110f, 110f), 4f)                                         // 「张」取消
        assertThat(vm.bang()!!.selected).containsExactly(4)
        vm.onTap(PointF(300f, 350f), 4f)                                         // 空白：什么也不做
        assertThat(vm.bang()!!.selected).containsExactly(4)
        assertThat(vm.state.value.plan.items).isEmpty()
    }

    /** 一指划着字、第二根手指落下来变成了缩放平移：这一划作罢，回到起手前。 */
    @Test fun `a sweep turned into a pinch is rolled back`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.releaseBigBang()
        val before = vm.bang()!!.selected
        vm.startBigBangSweep(0)
        vm.sweepBigBang(listOf(1, 2))
        vm.restoreBigBangSelection(before)
        assertThat(vm.bang()!!.selected).isEqualTo(before)
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

    @Test fun `closing adds nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.closeBigBang()
        assertThat(vm.bang()).isNull()
        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test fun `select all then mask gives one box per line`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.selectAllBigBang(true)
        vm.maskBigBang()
        assertThat(vm.state.value.plan.items.map { it.quad.bounds() })
            .containsExactly(RectF(40f, 100f, 140f, 120f), RectF(40f, 150f, 300f, 170f)).inOrder()
    }

    @Test fun `a run already covered by a mask is not masked twice`() = runTest(dispatcher) {
        val covered = Candidate("rule-name", rect(98f, 98f, 142f, 122f), SensitiveKind.PERSON_NAME, DetectorSource.RULE, 0.9f)
        val vm = vm(candidates = listOf(covered))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.plan.items).hasSize(1)

        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.sweepBigBang(listOf(4))
        vm.maskBigBang()
        assertThat(vm.state.value.plan.items).hasSize(1)
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
        assertThat(vm.state.value.plan.items).hasSize(1)
    }

    @Test fun `a re-run of recognition closes big bang`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onLongPress(PointF(110f, 110f), 4f)
        vm.rerunRecognition()
        assertThat(vm.bang()).isNull()
        advanceUntilIdle()
        assertThat(vm.bang()).isNull()
    }

    /** 重建之后只恢复了每一段的框，没有字：长按照旧，不打开。 */
    @Test fun `after a restore there is no text to explode`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        vm(saved = saved).apply { onImageChosen(sampleUri()) }
        advanceUntilIdle()
        val restored = vm(saved = saved)
        advanceUntilIdle()
        assertThat(restored.state.value.image).isNotNull()
        assertThat(restored.onLongPress(PointF(110f, 110f), 4f)).isFalse()
    }
}
