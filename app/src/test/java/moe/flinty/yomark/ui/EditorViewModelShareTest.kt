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
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.FileProviderSink
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
 * 顶栏的分享：和导出走同一条管线，图写进缓存里分享用的那个目录交给分享面板（[EditorMessage.Shared]），不进相册。
 * 每次分享先清掉上一次的；批量照样先等识别、有没看过的先问；一批里有一张没写成就一张都不分享。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelShareTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        Dispatchers.resetMain()
        FileProviderSink(context, FileProviderSink.SHARE).clear()
    }

    /** 相册：分享时一张也不该写进来。 */
    private class GallerySink : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { writes++; return Uri.parse("content://gallery/$writes") }
    }

    private lateinit var gallery: GallerySink

    /** 第 [failOn] 次写出时失败（从 1 数）。不经 FileProvider，直接给出文件本身，看写到了哪儿。 */
    private fun shareSink(failOn: Int? = null): FileProviderSink {
        var writes = 0
        return FileProviderSink(context, FileProviderSink.SHARE, dispatcher) { file ->
            writes++
            if (writes == failOn) throw IOException("写不进去")
            Uri.fromFile(file)
        }
    }

    private fun vm(share: FileProviderSink? = shareSink()): EditorViewModel {
        gallery = GallerySink()
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), gallery),
            engineProvider = { emptyEngine() },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
            shareSink = share,
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

    private val shareDir get() = File(context.cacheDir, FileProviderSink.SHARE)

    private fun EditorViewModel.shared() = state.value.message as EditorMessage.Shared

    @Test
    fun `sharing writes into the share folder, not the gallery`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("s1.jpg")); advanceUntilIdle()
        vm.onManualBox(box)

        vm.requestExport(share = true); advanceUntilIdle()

        val shared = vm.shared()
        assertThat(shared.uris).hasSize(1)
        assertThat(File(shared.uris.single().path!!).parentFile).isEqualTo(shareDir)
        assertThat(shared.mimeType).isEqualTo("image/jpeg")
        assertThat(gallery.writes).isEqualTo(0)
        // 编辑器留着，两颗都还能再点
        assertThat(vm.state.value.exporting).isFalse()
        assertThat(vm.state.value.sharing).isFalse()
    }

    @Test
    fun `while sharing the share button is the busy one`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("s2.jpg")); advanceUntilIdle()

        vm.requestExport(share = true)

        assertThat(vm.state.value.exporting).isTrue()
        assertThat(vm.state.value.sharing).isTrue()
    }

    @Test
    fun `the next share clears what was shared before`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("s3.jpg")); advanceUntilIdle()
        vm.requestExport(share = true); advanceUntilIdle()
        vm.consumeMessage()

        vm.requestExport(share = true); advanceUntilIdle()

        // 目录里只剩这一次的。没清的话上一张还在，这一张的名字也得往后加序号
        val second = File(vm.shared().uris.single().path!!)
        assertThat(shareDir.listFiles()!!.map { it.name }).containsExactly(second.name)
    }

    @Test
    fun `tapping export while a share is still running starts nothing`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(sampleUri("s4.jpg")); advanceUntilIdle()

        vm.requestExport(share = true)
        vm.requestExport(); advanceUntilIdle()

        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Shared::class.java)
    }

    @Test
    fun `without a share folder there is no share`() = runTest(dispatcher) {
        val vm = vm(share = null)
        vm.onImageChosen(sampleUri("s5.jpg")); advanceUntilIdle()

        vm.requestExport(share = true); advanceUntilIdle()

        assertThat(vm.canShare).isFalse()
        assertThat(vm.state.value.exporting).isFalse()
        assertThat(vm.state.value.message).isNull()
        assertThat(gallery.writes).isEqualTo(0)
    }

    @Test
    fun `a batch shares every opened image at once, in order`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("s6.jpg"), sampleUri("s7.jpg"))); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()

        vm.exportBatch(share = true); advanceUntilIdle()

        val shared = vm.shared()
        assertThat(shared.uris).hasSize(2)
        assertThat(shared.uris.map { File(it.path!!).parentFile }).containsExactly(shareDir, shareDir)
        assertThat(shared.uris.distinct()).hasSize(2)
        assertThat(shared.mimeType).isEqualTo("image/jpeg")
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.sharing).isFalse()
    }

    @Test
    fun `sharing a batch with unopened images asks first, then shares only what was seen`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("s8.jpg"), sampleUri("s9.jpg"), sampleUri("s10.jpg"))); advanceUntilIdle()
        vm.selectImage(2); advanceUntilIdle()

        vm.exportBatch(share = true); advanceUntilIdle()
        // 问的时候还记着是分享：按钮上写「只分享看过的」
        assertThat(vm.state.value.unopenedDialogVisible).isTrue()
        assertThat(vm.state.value.sharing).isTrue()
        assertThat(vm.state.value.exporting).isFalse()

        vm.exportOpenedOnly(); advanceUntilIdle()

        assertThat(vm.shared().uris).hasSize(2)
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.sharing).isFalse()
    }

    @Test
    fun `after dismissing the question, export saves to the gallery again`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImagesChosen(listOf(sampleUri("s11.jpg"), sampleUri("s12.jpg"), sampleUri("s13.jpg"))); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()
        vm.exportBatch(share = true); advanceUntilIdle()

        vm.dismissDialog()
        assertThat(vm.state.value.sharing).isFalse()
        vm.exportBatch(); advanceUntilIdle()
        vm.exportOpenedOnly(); advanceUntilIdle()

        assertThat(gallery.writes).isEqualTo(2)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.BatchExported(2))
    }

    @Test
    fun `a batch where one image fails to be written shares nothing`() = runTest(dispatcher) {
        val vm = vm(share = shareSink(failOn = 2))
        vm.onImagesChosen(listOf(sampleUri("s14.jpg"), sampleUri("s15.jpg"))); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()

        vm.exportBatch(share = true); advanceUntilIdle()

        assertThat(vm.state.value.message).isEqualTo(EditorMessage.Error(R.string.error_share))
        assertThat(vm.state.value.exporting).isFalse()               // 还能再点一次
        assertThat(vm.state.value.sharing).isFalse()
    }
}
