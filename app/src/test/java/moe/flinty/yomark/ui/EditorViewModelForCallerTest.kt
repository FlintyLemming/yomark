package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import moe.flinty.yomark.R
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
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
import java.io.IOException

/**
 * 别的应用来要图（PickImageActivity → EditorForCaller）：导出的图交回给它，不提示「已保存到相册」。
 * 导出前的待打码拦截照旧；批量里有一张没导出成，就一张都不交。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelForCallerTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    /** 第 [failOn] 次写出时失败（从 1 数），其余照常给出 URI。 */
    private class RecordingSink(private val failOn: Int? = null) : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri {
            writes++
            if (writes == failOn) throw IOException("写不进去")
            return Uri.parse("content://fake/$writes")
        }
    }

    private lateinit var sink: RecordingSink

    private fun vm(failOn: Int? = null): EditorViewModel {
        sink = RecordingSink(failOn)
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink),
            engineProvider = { emptyEngine() },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
            forCaller = true,
        )
    }

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

    private fun sampleUri(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private val box = Quad.fromRect(RectF(10f, 10f, 90f, 90f))

    @Test
    fun `the exported image goes back to the caller instead of a gallery notice`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("c1.jpg")); advanceUntilIdle()
        vm.onManualBox(box)

        vm.requestExport(); advanceUntilIdle()

        assertThat(sink.writes).isEqualTo(1)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.HandedOver(listOf(Uri.parse("content://fake/1"))))
        // 编辑器马上就关，按钮不再放开
        assertThat(vm.state.value.exporting).isTrue()
    }

    @Test
    fun `a batch goes back whole, in order`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("c3.jpg"), sampleUri("c4.jpg"))); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()

        vm.exportBatch(); advanceUntilIdle()

        assertThat(vm.state.value.message).isEqualTo(
            EditorMessage.HandedOver(listOf(Uri.parse("content://fake/1"), Uri.parse("content://fake/2"))),
        )
    }

    @Test
    fun `a batch with an image that failed to export hands back nothing`() = runTest(dispatcher) {
        val vm = vm(failOn = 2)
        vm.onImagesChosen(listOf(sampleUri("c5.jpg"), sampleUri("c6.jpg"))); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()

        vm.exportBatch(); advanceUntilIdle()

        assertThat(vm.state.value.message).isEqualTo(EditorMessage.Error(R.string.error_export))
        assertThat(vm.state.value.exporting).isFalse()               // 还能再点一次
    }
}
