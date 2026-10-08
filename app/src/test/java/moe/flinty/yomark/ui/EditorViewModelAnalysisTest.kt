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
import moe.flinty.yomark.core.model.SensitiveKind
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

    @Test
    fun `analysis fills the plan with tri-state items`() = runTest(dispatcher) {
        val vm = vm(listOf(
            candidate("hi", enabled = true, 10f, 10f, 60f, 30f),
            candidate("lo", enabled = false, 100f, 10f, 160f, 30f),
        ))
        vm.onImageChosen(sampleUri()); advanceUntilIdle()

        val states = vm.state.value.plan.items.associate { it.candidateId to it.state }
        assertThat(states["hi"]).isEqualTo(MaskState.MASKED)
        assertThat(states["lo"]).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.plan.pendingCount).isEqualTo(1)
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
        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.OUTLINED)
        assertThat(vm.state.value.canUndo).isTrue()

        vm.undo()
        assertThat(vm.state.value.plan.items.single().state).isEqualTo(MaskState.MASKED)
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

    // ---------- AI 复查（端侧大模型，用户点了才跑） ----------

    private class Nano(
        private val available: Boolean = true,
        private val boom: Boolean = false,
        private val out: List<Candidate> = listOf(
            Candidate("llm", Quad.fromRect(RectF(100f, 100f, 160f, 120f)),
                SensitiveKind.PERSON_NAME, DetectorSource.LLM, 0.5f, enabledByDefault = false)
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

    private fun vmWithNano(nano: Nano, rules: List<Candidate> = listOf(candidate("rule", true, 10f, 10f, 60f, 30f))) =
        EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
            engineProvider = {
                RedactionEngine(DeadRecognizer(), emptyList(), listOf(StubClassifier(rules)),
                    CandidateMerger(), refiners = listOf(nano))
            },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
        )

    @Test
    fun `the model does not run by itself after analysis`() = runTest(dispatcher) {
        val nano = Nano()
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-idle.jpg")); advanceUntilIdle()

        // 规则的结果就是全部；模型只探测在不在，一次都没推理
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule")
        assertThat(nano.calls).isEqualTo(0)
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.READY)
    }

    @Test
    fun `asking for an ai review appends outlined AI items and one undo removes them`() = runTest(dispatcher) {
        val nano = Nano()
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-run.jpg")); advanceUntilIdle()

        vm.runAiReview(); advanceUntilIdle()

        val items = vm.state.value.plan.items.associateBy { it.candidateId }
        assertThat(nano.calls).isEqualTo(1)
        assertThat(items.keys).containsExactly("rule", "llm")
        assertThat(items.getValue("llm").state).isEqualTo(MaskState.OUTLINED)   // 只圈出，不打码
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.DONE)
        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Notice::class.java)

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
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.RUNNING)

        gate.complete(Unit); advanceUntilIdle()
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.DONE)
        assertThat((vm.state.value.message as EditorMessage.Notice).text).contains("没有发现")
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
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.HIDDEN)   // 新图还在载入，旧图的按钮先收起
        advanceUntilIdle()
        gate.complete(Unit); advanceUntilIdle()

        assertThat(vm.state.value.plan.items.map { it.candidateId }).doesNotContain("llm")
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.READY)   // 新图上可以重新点
    }

    @Test
    fun `no review button when the model is not on this device`() = runTest(dispatcher) {
        val nano = Nano(available = false)
        val vm = vmWithNano(nano)
        vm.onImageChosen(sampleUri("nano-none.jpg")); advanceUntilIdle()

        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.HIDDEN)
        vm.runAiReview(); advanceUntilIdle()                  // 按钮不在，调了也什么都不发生
        assertThat(nano.calls).isEqualTo(0)
    }

    @Test
    fun `a failed review says so instead of claiming the page is clean`() = runTest(dispatcher) {
        val vm = vmWithNano(Nano(boom = true))
        vm.onImageChosen(sampleUri("nano-boom.jpg")); advanceUntilIdle()

        vm.runAiReview(); advanceUntilIdle()

        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Error::class.java)
        assertThat(vm.state.value.aiReview).isEqualTo(AiReview.READY)   // 可以再点一次
        assertThat(vm.state.value.plan.items.map { it.candidateId }).containsExactly("rule")
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
}
