package com.yomark.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.ImageIntake
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.Candidate
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.core.model.TextLine
import com.yomark.app.data.isolatedSettingsStore
import com.yomark.app.engine.CandidateMerger
import com.yomark.app.engine.RedactionEngine
import com.yomark.app.engine.SensitivityClassifier
import com.yomark.app.engine.TextRecognizer
import com.yomark.app.export.Exporter
import com.yomark.app.export.ImageSink
import com.yomark.app.export.WatermarkDrawer
import com.yomark.app.render.RendererRegistry
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
 * Activity 重建后的状态恢复（spec §15 第 2 条的实测缺口）。
 *
 * 「不保留活动」下 Activity 被销毁时 ViewModel 一并 onCleared，
 * 之前的实现因此在返回时丢图退回 Photo Picker。私有副本的路径与整棵
 * MaskPlan 现在都进 SavedStateHandle，重建后不重跑识别、直接恢复。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelRestoreTest {

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

    /** 每次调用都记一笔，用来断言重建后没有重跑识别。 */
    private class CountingClassifier(private val out: List<Candidate>) : SensitivityClassifier {
        var calls = 0
        override val id = "counting"
        override suspend fun isAvailable() = true
        override suspend fun classify(lines: List<TextLine>): List<Candidate> {
            calls++
            return out
        }
    }

    private class DeadRecognizer : TextRecognizer {
        override val id = "dead"
        override suspend fun recognize(image: SourceImage): List<TextLine> = emptyList()
    }

    private fun candidate(id: String, enabled: Boolean, l: Float = 10f, t: Float = 10f) = Candidate(
        id, Quad.fromRect(RectF(l, t, l + 50f, t + 20f)),
        SensitiveKind.URL, DetectorSource.RULE, 0.8f, enabled,
    )

    private fun vm(saved: SavedStateHandle, classifier: SensitivityClassifier) = EditorViewModel(
        intake = ImageIntake(context),
        exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), NoSink()),
        engineProvider = { RedactionEngine(DeadRecognizer(), emptyList(), listOf(classifier), CandidateMerger()) },
        settings = isolatedSettingsStore(context),
        savedState = saved,
        ioDispatcher = dispatcher,
    )

    private fun sampleUri(name: String = "restore.jpg"): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    @Test
    fun `the image and the plan come back after recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(saved, CountingClassifier(listOf(candidate("a", true), candidate("b", false, l = 200f, t = 200f))))
        first.onImageChosen(sampleUri()); advanceUntilIdle()
        first.setStyle(MaskStyle.PIXELATE)
        first.applyLookToAll()                       // 打好的码换成马赛克：每块码的样式要跟着 plan 回来
        assertThat(first.state.value.plan.items).hasSize(2)

        // Activity 被销毁：ViewModel 清掉，SavedStateHandle 活下来
        val second = vm(saved, CountingClassifier(emptyList()))
        advanceUntilIdle()

        assertThat(second.state.value.restoring).isFalse()
        assertThat(second.state.value.image).isNotNull()
        assertThat(second.state.value.plan.items.map { it.candidateId })
            .containsExactly("a", "b").inOrder()
        assertThat(second.state.value.plan.find("a")!!.look.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(second.state.value.plan.pendingCount).isEqualTo(1)
    }

    @Test
    fun `recreation does not re-run recognition`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val classifier = CountingClassifier(listOf(candidate("a", true)))
        vm(saved, classifier).onImageChosen(sampleUri()); advanceUntilIdle()
        assertThat(classifier.calls).isEqualTo(1)

        val again = CountingClassifier(listOf(candidate("a", true)))
        vm(saved, again); advanceUntilIdle()
        assertThat(again.calls).isEqualTo(0)          // 恢复的是结果，不是重算
    }

    @Test
    fun `user edits made before recreation survive it`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(saved, CountingClassifier(listOf(candidate("a", true))))
        first.onImageChosen(sampleUri()); advanceUntilIdle()
        first.onManualBox(Quad.fromRect(RectF(100f, 100f, 200f, 200f)))
        assertThat(first.state.value.plan.items).hasSize(2)

        val second = vm(saved, CountingClassifier(emptyList()))
        advanceUntilIdle()
        assertThat(second.state.value.plan.items).hasSize(2)
        assertThat(second.state.value.plan.items.map { it.state })
            .containsExactly(MaskState.MASKED, MaskState.MASKED)
    }

    @Test
    fun `a fresh handle restores nothing so the picker still opens`() = runTest(dispatcher) {
        val second = vm(SavedStateHandle(), CountingClassifier(emptyList()))
        advanceUntilIdle()
        assertThat(second.state.value.image).isNull()
    }

    @Test
    fun `a private copy deleted behind our back does not wedge the editor`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(saved, CountingClassifier(listOf(candidate("a", true))))
        first.onImageChosen(sampleUri()); advanceUntilIdle()
        ImageIntake(context).clear()                 // 副本没了

        val second = vm(saved, CountingClassifier(emptyList()))
        advanceUntilIdle()
        assertThat(second.state.value.image).isNull()
        assertThat(second.state.value.message).isNull()   // 静默退回 Picker，不报错吓人
        assertThat(second.state.value.restoring).isFalse() // Activity 据此决定拉 Picker
    }
}
