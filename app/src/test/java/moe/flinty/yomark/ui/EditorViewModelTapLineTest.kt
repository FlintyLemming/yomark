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
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
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
 * 点没打码的字，打码它所在的那一整行（2026-10-14，spec §7.3 修订）：框是文字识别给的这一行，
 * 再点一下取消；画布上不画圈出的，行里认出的圈出项被这一行盖住，导出前也就不再提醒。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelTapLineTest {

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

    /** 每张图都读出同样的几行；[gate] 不为 null 时第二次起要等它放行，好卡在「新图载入了、还在识别」那一下。 */
    private class Recognizer(private val lines: List<TextLine>) : TextRecognizer {
        override val id = "stub"
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun recognize(image: SourceImage): List<TextLine> {
            if (calls++ > 0) gate?.await()
            return lines
        }
    }

    private class StubClassifier(private val out: List<Candidate>) : SensitivityClassifier {
        override val id = "stub"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = out
    }

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    private fun line(l: Float, t: Float, r: Float, b: Float, text: String = "下单时间 2026-10-14 12:00") =
        TextLine(rect(l, t, r, b), text, 0.95f, listOf(TextElement(rect(l, t, r, b), text, text.indices)))

    /** 两行字：上面一行 10..40，下面一行 60..90，中间空着 20 像素。 */
    private val upper = line(10f, 10f, 300f, 40f)
    private val lower = line(10f, 60f, 300f, 90f, text = "订单号 1234567890123456")

    private lateinit var sink: CountingSink
    private lateinit var recognizer: Recognizer

    private fun vm(
        candidates: List<Candidate> = emptyList(),
        saved: SavedStateHandle = SavedStateHandle(),
        settings: SettingsStore = isolatedSettingsStore(context),
    ): EditorViewModel {
        sink = CountingSink()
        recognizer = Recognizer(listOf(upper, lower))
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { RedactionEngine(recognizer, emptyList(), listOf(StubClassifier(candidates)), CandidateMerger()) },
            settings = settings,
            savedState = saved,
            ioDispatcher = dispatcher,
        )
    }

    private fun sampleUri(name: String = "tap-line.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private fun candidate(id: String, quad: Quad, kind: SensitiveKind, masked: Boolean) =
        Candidate(id, quad, kind, DetectorSource.RULE, 0.8f, enabledByDefault = masked)

    private fun EditorViewModel.items() = state.value.plan.items

    @Test fun `tapping unmasked text masks its whole line in the last redact style`() = runTest(dispatcher) {
        val vm = vm()
        vm.setStyle(MaskStyle.PIXELATE)
        vm.setStyle(MaskStyle.PEN)                                       // 手里拿着画笔
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onTap(PointF(100f, 25f))
        val item = vm.items().single()
        assertThat(item.source).isEqualTo(DetectorSource.LINE)
        assertThat(item.quad).isEqualTo(upper.quad)
        assertThat(item.state).isEqualTo(MaskState.MASKED)
        assertThat(item.look.style).isEqualTo(MaskStyle.PIXELATE)        // 画笔只做标记，不拿来打码
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.PEN)
        assertThat(vm.state.value.selectedManualId).isNull()             // 不像手动框那样进选中态
        assertThat(vm.state.value.canUndo).isTrue()
    }

    @Test fun `tapping the masked line again takes it off, and undo brings it back`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onTap(PointF(100f, 25f))
        val id = vm.items().single().candidateId

        vm.onTap(PointF(250f, 30f))
        assertThat(vm.items()).isEmpty()
        vm.undo()
        assertThat(vm.items().single().candidateId).isEqualTo(id)
    }

    /** 宽屏列表上点这一行，和在图上再点一下一样。 */
    @Test fun `a list tap on a tapped line takes it off`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onTap(PointF(100f, 75f))

        vm.onListItemClick(vm.items().single().candidateId)
        assertThat(vm.items()).isEmpty()
    }

    @Test fun `a line over an outlined date covers it, so export does not ask about it`() = runTest(dispatcher) {
        val date = candidate("date", rect(90f, 10f, 250f, 40f), SensitiveKind.DATETIME, masked = false)
        val vm = vm(listOf(date))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.plan.pendingCount).isEqualTo(1)

        vm.onTap(PointF(150f, 25f))                                      // 点在日期上，打码的也是整行
        assertThat(vm.items().single { it.source == DetectorSource.LINE }.quad).isEqualTo(upper.quad)
        assertThat(vm.state.value.plan.find("date")!!.state).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.plan.pendingCount).isEqualTo(0)

        vm.requestExport(); advanceUntilIdle()
        assertThat(vm.state.value.pendingDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(1)
    }

    @Test fun `tapping a detected mask unmasks just that mask, not its line`() = runTest(dispatcher) {
        val phone = candidate("phone", rect(150f, 60f, 290f, 90f), SensitiveKind.PHONE, masked = true)
        val vm = vm(listOf(phone))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onTap(PointF(200f, 75f))
        assertThat(vm.items().map { it.candidateId }).containsExactly("phone")
        assertThat(vm.state.value.plan.find("phone")!!.state).isEqualTo(MaskState.OUTLINED)
    }

    @Test fun `a tap in the gap takes the nearer line within reach, blank space takes nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onManualBox(rect(320f, 300f, 390f, 390f))                     // 画完是选中的

        vm.onTap(PointF(100f, 300f), reach = 10f)                        // 离哪一行都远：点了空白
        assertThat(vm.items()).hasSize(1)
        assertThat(vm.state.value.selectedManualId).isNull()

        vm.onTap(PointF(100f, 53f), reach = 10f)                         // 两行之间，离下面那行 7、上面那行 13
        assertThat(vm.items().last().quad).isEqualTo(lower.quad)
    }

    @Test fun `the last image's lines do not count on the next one while it is recognized`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("tap-line-1.jpg")); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>().also { recognizer.gate = it }

        vm.onImageChosen(sampleUri("tap-line-2.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.analyzing).isTrue()
        vm.onTap(PointF(100f, 25f))
        assertThat(vm.items()).isEmpty()

        gate.complete(Unit); advanceUntilIdle()
        vm.onTap(PointF(100f, 25f))                                      // 这一张自己的行出来了，照样点得上
        assertThat(vm.items().single().quad).isEqualTo(upper.quad)
    }

    @Test fun `lines come back with the plan after recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(saved = saved)
        first.onImageChosen(sampleUri()); advanceUntilIdle()
        first.onTap(PointF(100f, 25f))

        val second = vm(saved = saved); advanceUntilIdle()
        assertThat(second.items().single().source).isEqualTo(DetectorSource.LINE)
        assertThat(recognizer.calls).isEqualTo(0)                        // 没有重跑识别
        second.onTap(PointF(100f, 75f))                                  // 识别出的行也跟着回来了
        assertThat(second.items().map { it.quad }).containsExactly(upper.quad, lower.quad).inOrder()
    }

    @Test fun `tapped lines survive a change of recognition settings`() = runTest(dispatcher) {
        val vm = vm(listOf(candidate("url", rect(10f, 200f, 100f, 230f), SensitiveKind.URL, masked = true)))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        vm.onTap(PointF(100f, 25f))
        val id = vm.items().single { it.source == DetectorSource.LINE }.candidateId

        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.LATIN)); advanceUntilIdle()
        assertThat(vm.items().map { it.candidateId }).contains(id)
    }
}
