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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelAnalysisTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class NoSink : ImageSink {
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri = Uri.parse("content://fake/1")
    }

    private class StubClassifier(private val out: List<Candidate>) : SensitivityClassifier {
        override val id = "stub"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>) = out
    }

    private class DeadRecognizer : TextRecognizer {
        override val id = "dead"
        override suspend fun recognize(image: SourceImage): List<TextLine> = error("no ocr here")
    }

    private fun candidate(id: String, enabled: Boolean, l: Float, t: Float, r: Float, b: Float) =
        Candidate(id, Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.URL, DetectorSource.RULE, 0.8f, enabled)

    private fun vm(candidates: List<Candidate>) = EditorViewModel(
        intake = ImageIntake(context),
        exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
        engineProvider = { RedactionEngine(DeadRecognizer(), emptyList(), listOf(StubClassifier(candidates)), CandidateMerger()) },
        settings = isolatedSettingsStore(context),
        savedState = SavedStateHandle(),
        ioDispatcher = dispatcher,
    )

    private fun sampleUri(name: String = "an.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    /** 2026-10-14 起没有「圈出」：要打码的进 plan，不打码的认出来了也不进。 */
    @Test
    fun `analysis masks what should be masked and leaves out the rest`() = runTest(dispatcher) {
        val vm = vm(listOf(
            candidate("hi", enabled = true, 10f, 10f, 60f, 30f),
            candidate("lo", enabled = false, 100f, 10f, 160f, 30f),
        ))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("hi")
    }

    /** 手里拿着画笔打开一张图：识别出来直接打的码用「打码」里上次选的样式，画笔只做标记。 */
    @Test
    fun `detected masks take the last redact style even with the pen in hand`() = runTest(dispatcher) {
        val vm = vm(listOf(candidate("hi", enabled = true, 10f, 10f, 60f, 30f)))
        vm.setStyle(MaskStyle.PIXELATE)
        vm.setStyle(MaskStyle.PEN)
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(vm.state.value.plan.find("hi")!!.look.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(vm.state.value.brush.style).isEqualTo(MaskStyle.PEN)
    }

    @Test
    fun `analyzing flag is cleared when analysis finishes`() = runTest(dispatcher) {
        val vm = vm(emptyList())
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.analyzing).isFalse()
    }

    @Test
    fun `analysis results are not undoable`() = runTest(dispatcher) {
        // 初始状态不是用户动作，撤销栈必须是空的
        val vm = vm(listOf(candidate("x", true, 10f, 10f, 60f, 30f)))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(vm.state.value.canUndo).isFalse()
    }

    @Test
    fun `the first user tap becomes the first undoable action`() = runTest(dispatcher) {
        val vm = vm(listOf(candidate("x", true, 10f, 10f, 60f, 30f)))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        vm.onTap(PointF(30f, 20f))
        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.canUndo).isTrue()

        vm.undo()
        assertThat(vm.state.value.plan.items.single().candidateId).isEqualTo("x")
    }

    @Test
    fun `manual boxes drawn during analysis survive the merge`() = runTest(dispatcher) {
        // 分类器卡在 gate 上，模拟「图已载入、分析还没回来」这个窗口。
        // 不能只是 onImageChosen 之后立刻画框：那时载入协程一步都还没跑，
        // 框会落在载入前的空 plan 上，再被载入时的状态重置抹掉——
        // 而那个场景在 UI 上根本走不到（image == null 时 ImageCanvas 直接 return）。
        val gate = CompletableDeferred<Unit>()
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
            engineProvider = { RedactionEngine(
                DeadRecognizer(), emptyList(),
                listOf(object : SensitivityClassifier {
                    override val id = "gated"
                    override suspend fun isAvailable() = true
                    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
                        gate.await()
                        return listOf(candidate("late", true, 200f, 200f, 260f, 230f))
                    }
                }),
                CandidateMerger(),
            ) },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )

        vm.onImageChosen(sampleUri())
        advanceUntilIdle()
        assertThat(vm.state.value.analyzing).isTrue()          // 分析确实还在飞

        // 分析还没回来，用户已经画了一个框
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        gate.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId })
            .comparingElementsUsing(com.google.common.truth.Correspondence.from<String, String>(
                { actual, expected -> actual!!.startsWith(expected!!) }, "starts with"
            ))
            .containsAtLeast("manual-", "late")
    }

    @Test
    fun `a failing engine leaves the editor usable`() = runTest(dispatcher) {
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
            engineProvider = { RedactionEngine(
                DeadRecognizer(), emptyList(),
                listOf(
                    object : SensitivityClassifier {
                        override val id = "boom"
                        override suspend fun isAvailable() = true
                        override suspend fun classify(lines: List<TextLine>): List<Candidate> = error("boom")
                    }
                ),
                CandidateMerger(),
            ) },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        assertThat(vm.state.value.analyzing).isFalse()
        assertThat(vm.state.value.image).isNotNull()
        vm.onManualBox(Quad.fromRect(RectF(10f, 10f, 60f, 60f)))
        assertThat(vm.state.value.plan.items).hasSize(1)     // 手动打码仍然可用
    }

    // ---------- AI 增强识别（端侧大模型，用户点了才跑） ----------

    private class Nano(
        var available: Boolean = true,
        var boom: Boolean = false,
        private val out: List<Candidate> = listOf(
            Candidate("llm", Quad.fromRect(RectF(100f, 100f, 160f, 120f)),
                SensitiveKind.PERSON_NAME, DetectorSource.LLM, 0.5f)
        ),
    ) : SensitivityClassifier {
        override val id = "nano"
        var calls = 0
        var gate = CompletableDeferred<Unit>().apply { complete(Unit) }
        override suspend fun isAvailable() = available
        override suspend fun classify(lines: List<TextLine>): List<Candidate> {
            calls++
            gate.await()
            if (boom) error("aicore busy")
            return out
        }
    }

    private fun vmWithNano(
        nano: Nano,
        rules: List<Candidate> = listOf(candidate("rule", true, 10f, 10f, 60f, 30f)),
        settings: SettingsStore = isolatedSettingsStore(context),
    ) =
        EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
            engineProvider = {
                RedactionEngine(DeadRecognizer(), emptyList(), listOf(StubClassifier(rules)),
                    CandidateMerger(), refiners = listOf(nano))
            },
            settings = settings,
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )

    /**
     * 识别跑完之后才在实验室里配好外部模型：不重跑识别，按钮重新看一次能不能用就出来。
     * 跑过一遍的（Done）换了模型也重新可点；关掉了、又没有别的模型，按钮收起来。
     */
    @Test
    fun `setting up the external model after analysis offers the review without rerunning it`() = runTest(dispatcher) {
        val settings = isolatedSettingsStore(context)
        val nano = Nano(available = false)
        val vm = vmWithNano(nano, settings = settings)
        vm.onImageChosen(sampleUri("external-later.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Hidden)

        nano.available = true                                  // 外部模型配好了，后端变得可用
        settings.updateExternalModel { it.copy(enabled = true) }; advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Ready)
        assertThat(vm.state.value.analyzing).isFalse()         // 没有重跑识别

        vm.runAiReview(); advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Done(found = 1))
        settings.updateExternalModel { it.copy(model = "another") }; advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Ready)

        nano.available = false
        settings.updateExternalModel { it.copy(enabled = false) }; advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Hidden)
    }

    @Test
    fun `the model does not run by itself after analysis`() = runTest(dispatcher) {
        val nano = Nano()
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-idle.jpg")); advanceUntilIdle()

        // 规则的结果就是全部；模型只探测在不在，一次都没推理
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule")
        assertThat(nano.calls).isEqualTo(0)
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Ready)
    }

    @Test
    fun `asking for an ai review masks what it finds and one undo removes them`() = runTest(dispatcher) {
        val nano = Nano()
        val vm = vmWithNano(nano)
        vm.setStyle(MaskStyle.BLUR)
        vm.onImageChosen(sampleUri("nano-run.jpg")); advanceUntilIdle()

        vm.runAiReview(); advanceUntilIdle()

        val items = vm.state.value.plan.items.associateBy { it.candidateId }
        assertThat(nano.calls).isEqualTo(1)
        assertThat(items.keys).containsExactly("rule", "llm")
        assertThat(items.getValue("llm").look.style).isEqualTo(MaskStyle.BLUR)    // 直接打码，用「打码」里的样式
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Done(found = 1))
        assertThat(vm.state.value.message).isNull()                            // 结果写在按钮上，不弹提示

        // 是用户点出来的：不想要这批框，撤销一下整批拿掉
        vm.undo()
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule")
    }

    @Test
    fun `the review shows as running until the model answers, and an empty answer adds nothing`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val nano = Nano(out = emptyList()).also { it.gate = gate }
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-gated.jpg")); advanceUntilIdle()

        vm.runAiReview(); advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Running)

        gate.complete(Unit); advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Done(found = 0))
        assertThat(vm.state.value.message).isNull()
        assertThat(vm.state.value.canUndo).isFalse()          // 什么都没加，撤销栈不该多一格
    }

    @Test
    fun `changing the image throws away a review that is still running`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val nano = Nano().also { it.gate = gate }
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-first.jpg")); advanceUntilIdle()
        vm.runAiReview(); advanceUntilIdle()

        vm.onImageChosen(sampleUri("nano-second.jpg"))
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Hidden)   // 新图还在载入，旧图的按钮先收起
        advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId }).doesNotContain("llm")
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Ready)   // 新图上可以重新点
    }

    @Test
    fun `no review button when the model is not on this device`() = runTest(dispatcher) {
        val nano = Nano(available = false)
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-none.jpg")); advanceUntilIdle()

        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Hidden)
        vm.runAiReview(); advanceUntilIdle()                  // 按钮不在，调了也什么都不发生
        assertThat(nano.calls).isEqualTo(0)
    }

    @Test
    fun `a failed review says so instead of claiming the page is clean, and can be retried`() = runTest(dispatcher) {
        val nano = Nano(boom = true)
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-boom.jpg")); advanceUntilIdle()

        vm.runAiReview(); advanceUntilIdle()

        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Failed)   // 不能说成「没有新发现」
        assertThat(vm.state.value.message).isNull()                      // 写在按钮上，不弹提示
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule")
        assertThat(vm.state.value.aiReviewsFinished).isEqualTo(1)        // 没跑成也算跑完一次：手机上要说一声

        nano.boom = false
        vm.runAiReview(); advanceUntilIdle()                             // 可以再点一次
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Done(found = 1))
        assertThat(vm.state.value.aiReviewsFinished).isEqualTo(2)
    }

    /** 点掉的多半是误报：AI 不该在同一处再打回来。 */
    @Test
    fun `the model does not bring back a mask the user tapped away`() = runTest(dispatcher) {
        val same = Candidate("llm", Quad.fromRect(RectF(10f, 10f, 60f, 30f)), SensitiveKind.PERSON_NAME, DetectorSource.LLM, 0.5f)
        val vm = vmWithNano(Nano(out = listOf(same)))
        vm.onImageChosen(sampleUri("nano-dismissed.jpg")); advanceUntilIdle()

        vm.onTap(PointF(30f, 20f))                                      // 点掉规则打上的那块
        assertThat(vm.state.value.plan.items).isEmpty()
        vm.runAiReview(); advanceUntilIdle()

        assertThat(vm.state.value.plan.items).isEmpty()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Done(found = 0))
    }

    @Test
    fun `the model does not stack a box on a manual box`() = runTest(dispatcher) {
        val vm = vmWithNano(Nano())
        vm.onImageChosen(sampleUri("nano-manual.jpg")); advanceUntilIdle()

        // 用户已经自己把模型要指的那块（100,100)-(160,120) 盖住了
        vm.onManualBox(Quad.fromRect(RectF(95f, 95f, 165f, 125f)))
        vm.runAiReview(); advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId }).doesNotContain("llm")
    }

    // ---------- 文本和识别、AI 增强识别 ----------

    /** 文本只做标记，底下的字照样看得见：模型在文本底下找到的照样打上码，不当成已经盖住了。 */
    @Test
    fun `a text written over what the model finds does not hide it from the review`() = runTest(dispatcher) {
        val vm = vmWithNano(Nano())
        vm.onImageChosen(sampleUri("nano-under-text.jpg")); advanceUntilIdle()
        vm.setStyle(MaskStyle.TEXT)
        vm.onTap(PointF(130f, 110f))
        vm.setText("看这里")                                      // 正压在模型要报的那一处（100..160 × 100..120）上
        vm.dismissStylePanel()

        vm.runAiReview(); advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId }).contains("llm")
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Done(found = 1))
    }

    /** 增强识别跑着的时候写了字：字和它找到的码各是一步，撤销一下只拿掉找到的码，字还在。 */
    @Test
    fun `typing while the review runs keeps the text and the findings as separate undo steps`() = runTest(dispatcher) {
        val nano = Nano()
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-while-typing.jpg")); advanceUntilIdle()
        nano.gate = CompletableDeferred()
        vm.runAiReview(); advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.Running)

        vm.setStyle(MaskStyle.TEXT)
        vm.onTap(PointF(300f, 300f))
        vm.setText("Hi")
        nano.gate.complete(Unit); advanceUntilIdle()
        vm.dismissStylePanel()
        fun ids() = vm.state.value.plan.items.map { it.candidateId }
        fun texts() = vm.state.value.plan.items.mapNotNull { it.text }

        vm.undo()
        assertThat(ids()).doesNotContain("llm")
        assertThat(texts()).contains("Hi")
        vm.undo()
        assertThat(texts()).doesNotContain("Hi")
        assertThat(ids()).contains("rule")
    }

    /** 识别还没出结果时就写了字：结果到了照样打上，撤销那几个字不会把识别出的码一起撤掉。 */
    @Test
    fun `typing before the results land does not let undo drop them`() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        val rules = listOf(candidate("rule", true, 10f, 10f, 60f, 30f))
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
            engineProvider = {
                RedactionEngine(DeadRecognizer(), emptyList(), listOf(object : SensitivityClassifier {
                    override val id = "slow"
                    override suspend fun isAvailable() = true
                    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
                        gate.await()
                        return rules
                    }
                }), CandidateMerger())
            },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )
        vm.onImageChosen(sampleUri("slow-rules.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.analyzing).isTrue()

        vm.setStyle(MaskStyle.TEXT)
        vm.onTap(PointF(300f, 300f))
        vm.setText("Hi")
        gate.complete(Unit); advanceUntilIdle()
        assertThat(vm.state.value.plan.items.map { it.candidateId }).contains("rule")
        vm.dismissStylePanel()

        vm.undo()
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule")
    }
}
