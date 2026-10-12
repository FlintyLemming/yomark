package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
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
import kotlinx.coroutines.test.TestScope
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
 * 批量（spec §7.6）：胶片条上哪张都能直接打开，第一次打开时才识别；切走时还没识别完的接着在后台跑，
 * 结果落回它自己的 plan；每张有自己的编辑和撤销栈；导出先等识别跑完，还有没打开过的先问一句。
 */
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

    /** 每识别一张算一次。[hold] 为 true 时识别卡住，等测试按次序放行：第几次识别就是 scans 里的第几个。 */
    private var hold = false
    private val scans = mutableListOf<CompletableDeferred<Unit>>()

    /** 每张图都识别出一处，直接打码（不触发导出前的待打码拦截），id 带上是哪套方案识别的。 */
    private fun engine(config: RecognitionConfig) = RedactionEngine(
        object : TextRecognizer {
            override val id = "gated"
            override suspend fun recognize(image: SourceImage): List<TextLine> {
                val gate = CompletableDeferred<Unit>().also { scans += it }
                if (hold) gate.await()
                return emptyList()
            }
        },
        emptyList(),
        listOf(object : SensitivityClassifier {
            override val id = "one"
            override suspend fun isAvailable() = true
            override suspend fun classify(lines: List<TextLine>) = listOf(
                Candidate(
                    "found-${config.textEngine}", Quad.fromRect(RectF(100f, 100f, 200f, 130f)),
                    SensitiveKind.PHONE, DetectorSource.RULE, 0.9f, enabledByDefault = true,
                ),
            )
        }),
        CandidateMerger(),
    )

    private fun vm(): EditorViewModel {
        sink = CountingSink()
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = ::engine,
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

    private fun uris(prefix: String, count: Int) = (1..count).map { sampleUri("$prefix$it.jpg") }

    private fun TestScope.open(prefix: String, count: Int): EditorViewModel = vm().also {
        it.onImagesChosen(uris(prefix, count))
        advanceUntilIdle()
    }

    private fun ids(plan: MaskPlan?) = plan!!.items.map { it.candidateId }
    private fun EditorViewModel.found() = ids(state.value.plan).filter { it.startsWith("found-") }
    private fun EditorViewModel.manualIn(plan: MaskPlan?) = plan!!.items.count { it.kind == SensitiveKind.MANUAL }
    private val box = Quad.fromRect(RectF(10f, 10f, 60f, 60f))

    private fun TestScope.release(scan: Int) {
        scans[scan].complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `a batch opens on the first image and scans it`() = runTest(dispatcher) {
        val vm = open("a", 3)

        val batch = vm.state.value.batch!!
        assertThat(batch.total).isEqualTo(3)
        assertThat(batch.index).isEqualTo(0)
        assertThat(vm.state.value.image).isNotNull()
        assertThat(vm.found()).hasSize(1)
        assertThat(batch.items.map { it.opened }).containsExactly(true, false, false).inOrder()
        assertThat(scans).hasSize(1)
    }

    @Test
    fun `any image can be opened next, not just the following one`() = runTest(dispatcher) {
        val vm = open("b", 3)
        vm.selectImage(2); advanceUntilIdle()

        val batch = vm.state.value.batch!!
        assertThat(batch.index).isEqualTo(2)
        assertThat(vm.state.value.image).isNotNull()
        assertThat(vm.found()).hasSize(1)                         // 第一次打开时识别了
        assertThat(batch.items[1].opened).isFalse()               // 跳过的那张没动
        assertThat(scans).hasSize(2)
    }

    @Test
    fun `only the image landed on is scanned, not the ones passed over`() = runTest(dispatcher) {
        val vm = open("c", 3)
        vm.selectImage(1)
        vm.selectImage(2)                                          // 1 还没载入就又滑走了
        advanceUntilIdle()

        assertThat(vm.state.value.batch!!.index).isEqualTo(2)
        assertThat(vm.state.value.batch!!.items[1].opened).isFalse()
        assertThat(scans).hasSize(2)                               // 0 和 2
    }

    @Test
    fun `coming back to an image brings back its edits and its own undo history`() = runTest(dispatcher) {
        val vm = open("d", 2)
        vm.onManualBox(box)
        vm.selectImage(1); advanceUntilIdle()

        assertThat(vm.manualIn(vm.state.value.plan)).isEqualTo(0)   // 新图是干净的
        assertThat(vm.state.value.canUndo).isFalse()                 // 撤销不到上一张去

        vm.selectImage(0); advanceUntilIdle()
        assertThat(vm.manualIn(vm.state.value.plan)).isEqualTo(1)
        assertThat(vm.found()).hasSize(1)
        assertThat(vm.state.value.canUndo).isTrue()
        assertThat(scans).hasSize(2)                                 // 回来不再识别一遍

        vm.undo()
        assertThat(vm.manualIn(vm.state.value.plan)).isEqualTo(0)
    }

    @Test
    fun `a scan still running when you move on lands on its own image`() = runTest(dispatcher) {
        hold = true
        val vm = open("e", 2)
        vm.selectImage(1); advanceUntilIdle()
        assertThat(scans).hasSize(2)

        release(0)
        assertThat(ids(vm.state.value.batch!!.items[0].plan)).contains("found-PADDLE")
        assertThat(vm.found()).isEmpty()                             // 画布上这张（1）还没识别完
        assertThat(vm.state.value.analyzing).isTrue()

        release(1)
        assertThat(vm.found()).hasSize(1)
        assertThat(vm.state.value.analyzing).isFalse()
    }

    @Test
    fun `boxes drawn before moving on survive that image's scan finishing in the background`() = runTest(dispatcher) {
        hold = true
        val vm = open("f", 2)
        vm.onManualBox(box)                                          // 识别还没回来，先画了一个框
        vm.selectImage(1); advanceUntilIdle()

        release(0)
        val first = vm.state.value.batch!!.items[0].plan
        assertThat(vm.manualIn(first)).isEqualTo(1)
        assertThat(ids(first)).contains("found-PADDLE")
        release(1)
    }

    @Test
    fun `coming back to an image still being scanned waits for that scan instead of starting another`() =
        runTest(dispatcher) {
            hold = true
            val vm = open("g", 2)
            vm.selectImage(1); advanceUntilIdle()
            vm.selectImage(0); advanceUntilIdle()

            assertThat(vm.state.value.analyzing).isTrue()
            assertThat(scans).hasSize(2)

            release(0)
            assertThat(vm.found()).hasSize(1)
            assertThat(vm.state.value.analyzing).isFalse()
            assertThat(scans).hasSize(2)
            release(1)
        }

    @Test
    fun `an image scanned before the settings changed is scanned again when you come back`() = runTest(dispatcher) {
        val vm = open("h", 2)
        vm.selectImage(1); advanceUntilIdle()
        vm.setRecognitionConfig(RecognitionConfig(textEngine = TextEngineOption.LATIN)); advanceUntilIdle()
        assertThat(vm.found()).containsExactly("found-LATIN")       // 画布上这张当场重跑

        vm.selectImage(0); advanceUntilIdle()
        assertThat(vm.found()).containsExactly("found-LATIN")       // 切回来的那张也按新方案
        assertThat(vm.state.value.canUndo).isTrue()                 // 和单张一样，撤销一下回到换方案之前
    }

    @Test
    fun `exporting a batch writes one file per image`() = runTest(dispatcher) {
        val vm = open("i", 2)
        vm.onManualBox(box)
        vm.selectImage(1); advanceUntilIdle()

        vm.exportBatch(); advanceUntilIdle()

        assertThat(sink.writes).isEqualTo(2)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.BatchExported(2))
    }

    @Test
    fun `export waits for scans still running in the background`() = runTest(dispatcher) {
        hold = true
        val vm = open("j", 2)
        vm.selectImage(1); advanceUntilIdle()

        vm.exportBatch(); advanceUntilIdle()
        assertThat(sink.writes).isEqualTo(0)
        assertThat(vm.state.value.exporting).isTrue()

        release(0)
        assertThat(sink.writes).isEqualTo(0)                         // 还有一张在识别
        release(1)
        assertThat(sink.writes).isEqualTo(2)
        assertThat(ids(vm.state.value.batch!!.items[0].plan)).contains("found-PADDLE")
    }

    @Test
    fun `exporting with images never opened asks first and writes nothing`() = runTest(dispatcher) {
        val vm = open("k", 3)
        vm.exportBatch(); advanceUntilIdle()

        assertThat(vm.state.value.unopenedDialogVisible).isTrue()
        assertThat(vm.state.value.exporting).isFalse()
        assertThat(sink.writes).isEqualTo(0)
    }

    @Test
    fun `going to look opens the first image not seen yet`() = runTest(dispatcher) {
        val vm = open("l", 3)
        vm.selectImage(2); advanceUntilIdle()
        vm.exportBatch(); advanceUntilIdle()

        vm.reviewUnopened(); advanceUntilIdle()

        assertThat(vm.state.value.unopenedDialogVisible).isFalse()
        assertThat(vm.state.value.batch!!.index).isEqualTo(1)
        assertThat(vm.state.value.batch!!.unopened).isEqualTo(0)
        assertThat(vm.found()).hasSize(1)
    }

    @Test
    fun `exporting only what was seen leaves the rest out`() = runTest(dispatcher) {
        val vm = open("m", 3)
        vm.selectImage(2); advanceUntilIdle()
        vm.exportBatch(); advanceUntilIdle()

        vm.exportOpenedOnly(); advanceUntilIdle()

        assertThat(sink.writes).isEqualTo(2)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.BatchExported(2))
    }

    @Test
    fun `dismissing the question exports nothing`() = runTest(dispatcher) {
        val vm = open("n", 2)
        vm.exportBatch(); advanceUntilIdle()
        vm.dismissDialog(); advanceUntilIdle()

        assertThat(vm.state.value.unopenedDialogVisible).isFalse()
        assertThat(sink.writes).isEqualTo(0)
    }

    @Test
    fun `a single shared image does not start a batch`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("o1.jpg")))
        advanceUntilIdle()
        assertThat(vm.state.value.batch).isNull()
    }

    @Test
    fun `a new batch drops the old one, scans still running included`() = runTest(dispatcher) {
        hold = true
        val vm = open("p", 2)
        vm.onManualBox(box)
        val next = uris("q", 2)
        vm.onImagesChosen(next); advanceUntilIdle()
        scans.forEach { it.complete(Unit) }; advanceUntilIdle()

        val batch = vm.state.value.batch!!
        assertThat(batch.items.map { it.uri }).containsExactlyElementsIn(next).inOrder()
        assertThat(vm.manualIn(vm.state.value.plan)).isEqualTo(0)
        assertThat(vm.found()).hasSize(1)                              // 只有新这一批第一张的结果
    }
}
