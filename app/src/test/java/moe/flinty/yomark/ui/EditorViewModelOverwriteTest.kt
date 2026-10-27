package moe.flinty.yomark.ui

import android.app.PendingIntent
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.content.IntentSender
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import moe.flinty.yomark.R
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.ImageIntake
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.data.isolatedSettingsStore
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.SensitivityClassifier
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.export.Exporter
import moe.flinty.yomark.export.FileProviderSink
import moe.flinty.yomark.export.Fingerprint
import moe.flinty.yomark.export.ImageSink
import moe.flinty.yomark.export.OriginalUnavailable
import moe.flinty.yomark.export.Originals
import moe.flinty.yomark.export.WatermarkDrawer
import moe.flinty.yomark.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream

/**
 * 覆盖原图（顶栏导出右边的小箭头里，2026-10-27）：打好码的图写回原图，相册里不多一张。写不回去的图那一项灰着、说为什么；
 * 要许可时先弹系统的确认框，点了拒绝什么也不写；原图变了、没了就照常另存一份；导出、分享都不动原图。
 * 系统那一头（找原图、要许可、写文件）换成 [FakeOriginals]，见 MediaOriginalsTest。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorViewModelOverwriteTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        Dispatchers.resetMain()
        FileProviderSink(context, FileProviderSink.SHARE).clear()
    }

    /** 相册：另存的写到这儿。 */
    private class GallerySink : ImageSink {
        var writes = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri { writes++; return Uri.parse("content://gallery/$writes") }
    }

    /** 系统的确认框。测试里不弹，只看它有没有挂到 state 上。 */
    private val dialog: IntentSender =
        PendingIntent.getActivity(context, 0, Intent(), PendingIntent.FLAG_IMMUTABLE).intentSender

    /**
     * 原图就是测试造的那几个文件（file:// 的 URI 本身）。[locked] 里的要用户点头才写得了，[foreign] 里的认不出是哪一张。
     * 写的时候和 MediaOriginals 一样先核对指纹。
     */
    private inner class FakeOriginals : Originals {
        val locked = HashSet<Uri>()
        val foreign = HashSet<Uri>()
        var requests = 0
        var writes = 0

        override suspend fun locate(source: Uri): Uri? = source.takeIf { it !in foreign }

        override suspend fun writeRequest(targets: List<Uri>): IntentSender? =
            if (targets.any { it in locked }) dialog.also { requests++ } else null

        override suspend fun overwrite(target: Uri, expected: Fingerprint, encode: (OutputStream) -> Unit): Fingerprint {
            if (target in locked) throw OriginalUnavailable("没有许可")
            val file = File(target.path!!)
            if (!file.exists() || Fingerprint.of(file) != expected) throw OriginalUnavailable("原图变了")
            file.writeBytes(ByteArrayOutputStream().also(encode).toByteArray())
            writes++
            return Fingerprint.of(file)
        }

        /** 用户在确认框上点了允许。 */
        fun allow() = locked.clear()
    }

    /** 别的应用给的图（content://pictures/<文件名>），类型由它说了算：看 PNG、JPEG 以外的格式。 */
    class Pictures : ContentProvider() {
        val types = HashMap<String, String>()
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = File(context!!.cacheDir, uri.lastPathSegment!!)
            if (!file.exists()) throw FileNotFoundException(uri.toString())
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri): String? = types[uri.lastPathSegment]
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private lateinit var gallery: GallerySink
    private lateinit var originals: FakeOriginals
    private lateinit var settings: SettingsStore

    private fun TestScope.vm(
        saved: SavedStateHandle = SavedStateHandle(),
        share: FileProviderSink? = null,
    ): EditorViewModel {
        gallery = GallerySink()
        originals = FakeOriginals()
        settings = isolatedSettingsStore(context)
        return EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), gallery),
            engineProvider = { emptyEngine() },
            settings = settings,
            savedState = saved,
            ioDispatcher = dispatcher,
            shareSink = share,
            originals = originals,
        ).also { advanceUntilIdle() }
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

    /** 一张白图，当作相册里的原图。 */
    private fun original(name: String): Uri {
        val f = File(context.cacheDir, name)
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawColor(Color.WHITE)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        return f.toUri()
    }

    private val box = Quad.fromRect(RectF(10f, 10f, 90f, 90f))

    /** 原图上打码那一块的中心还是不是白的。 */
    private fun Uri.whiteUnderBox(): Boolean {
        val bmp = BitmapFactory.decodeFile(path!!)
        val pixel = bmp.getPixel(50, 50)
        return Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240
    }

    @Test
    fun `export still saves to the gallery and leaves the original alone`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o1.jpg")
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)

        vm.requestExport(); advanceUntilIdle()

        assertThat(gallery.writes).isEqualTo(1)
        assertThat(originals.writes).isEqualTo(0)
        assertThat(uri.whiteUnderBox()).isTrue()
        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Exported::class.java)
    }

    @Test
    fun `overwriting writes the masks into the original`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o2.jpg")
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)

        assertThat(vm.canOverwrite).isTrue()
        assertThat(vm.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Ready())
        vm.requestOverwrite(); advanceUntilIdle()

        assertThat(uri.whiteUnderBox()).isFalse()
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.Overwritten)
        assertThat(vm.state.value.exporting).isFalse()
        assertThat(vm.state.value.overwriting).isFalse()
    }

    /**
     * 设置里默认导出选了覆盖原图：这张写得回去时导出就是覆盖（顶栏写「覆盖」，另存在小箭头里），写不回去的导出照常另存。
     * 改了设置，开着的这张马上跟着变。
     */
    @Test
    fun `overwriting as the default export follows the setting and the image`() = runTest(dispatcher) {
        val vm = vm()
        vm.onImageChosen(original("d1.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.exportOverwrites).isFalse()

        settings.setOverwriteByDefault(true); advanceUntilIdle()
        assertThat(vm.state.value.exportOverwrites).isTrue()

        val foreign = original("d2.jpg").also { originals.foreign += it }
        vm.onImageChosen(foreign); advanceUntilIdle()
        assertThat(vm.state.value.exportOverwrites).isFalse()

        vm.onImageChosen(original("d3.jpg")); advanceUntilIdle()
        assertThat(vm.state.value.exportOverwrites).isTrue()
        settings.setOverwriteByDefault(false); advanceUntilIdle()
        assertThat(vm.state.value.exportOverwrites).isFalse()
    }

    /** 比如聊天应用分享来的：它自己的文件，不知道是相册里哪一张。那一项灰着，点了也什么都不做。 */
    @Test
    fun `an image that cannot be traced back cannot be overwritten`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o3.jpg")
        originals.foreign += uri
        vm.onImageChosen(uri); advanceUntilIdle()

        assertThat(vm.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Unavailable(R.string.overwrite_not_found))
        vm.requestOverwrite(); advanceUntilIdle()

        assertThat(originals.writes).isEqualTo(0)
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.message).isNull()
    }

    /** 导出只编 PNG、JPEG：WebP、HEIC 的原图写成 JPEG，文件里的东西就和它的类型对不上了。 */
    @Test
    fun `formats export cannot write are not overwritten`() = runTest(dispatcher) {
        val pictures = Robolectric.setupContentProvider(Pictures::class.java, "pictures")
        original("o4.webp")
        pictures.types["o4.webp"] = "image/webp"
        val vm = vm()

        vm.onImageChosen(Uri.parse("content://pictures/o4.webp")); advanceUntilIdle()

        assertThat(vm.state.value.image).isNotNull()
        assertThat(vm.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Unavailable(R.string.overwrite_format))
    }

    @Test
    fun `the system dialog comes first and allowing it writes the original`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o5.jpg")
        originals.locked += uri
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)

        vm.requestOverwrite(); advanceUntilIdle()
        assertThat(vm.state.value.writeRequest).isSameInstanceAs(dialog)
        assertThat(vm.state.value.exporting).isTrue()                  // 等用户点的时候按钮转着圈
        assertThat(vm.state.value.overwriting).isTrue()                // 读屏念「覆盖中…」
        assertThat(uri.whiteUnderBox()).isTrue()

        vm.writeRequestShown()
        assertThat(vm.state.value.writeRequest).isNull()               // 弹出去了，界面重建不再弹
        originals.allow()
        vm.onWriteRequestResult(true); advanceUntilIdle()

        assertThat(uri.whiteUnderBox()).isFalse()
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.Overwritten)
        assertThat(vm.state.value.exporting).isFalse()
    }

    @Test
    fun `declining the dialog writes nothing anywhere`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o6.jpg")
        originals.locked += uri
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)

        vm.requestOverwrite(); advanceUntilIdle()
        vm.writeRequestShown()
        vm.onWriteRequestResult(false); advanceUntilIdle()

        assertThat(uri.whiteUnderBox()).isTrue()
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.message).isNull()
        assertThat(vm.state.value.exporting).isFalse()                 // 还能再点一次
        assertThat(vm.state.value.overwriting).isFalse()
    }

    /** 打开之后原图在别处被改过（或者认错了是哪一张）：原图不动，另存一份，说一声。 */
    @Test
    fun `an original that changed since it was opened gets a copy instead`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o7.jpg")
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)
        val changed = File(uri.path!!).apply { appendBytes(byteArrayOf(1, 2, 3)) }.readBytes()

        vm.requestOverwrite(); advanceUntilIdle()

        assertThat(File(uri.path!!).readBytes()).isEqualTo(changed)
        assertThat(gallery.writes).isEqualTo(1)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.SavedCopyInstead)
    }

    /** 覆盖过一次，原图就不是打开时那一张了：再覆盖时拿写进去的那一份核对，不当它变了。 */
    @Test
    fun `overwriting twice checks against what was written the first time`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o8.jpg")
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)
        vm.requestOverwrite(); advanceUntilIdle()
        vm.consumeMessage()

        vm.onManualBox(Quad.fromRect(RectF(150f, 150f, 250f, 250f)))
        vm.requestOverwrite(); advanceUntilIdle()

        assertThat(vm.state.value.message).isEqualTo(EditorMessage.Overwritten)
        assertThat(originals.writes).isEqualTo(2)
        assertThat(gallery.writes).isEqualTo(0)
    }

    @Test
    fun `sharing never touches the original`() = runTest(dispatcher) {
        val vm = vm(share = FileProviderSink(context, FileProviderSink.SHARE, dispatcher) { Uri.fromFile(it) })
        val uri = original("o9.jpg")
        vm.onImageChosen(uri); advanceUntilIdle()
        vm.onManualBox(box)

        vm.requestExport(share = true); advanceUntilIdle()

        assertThat(vm.state.value.message).isInstanceOf(EditorMessage.Shared::class.java)
        assertThat(uri.whiteUnderBox()).isTrue()
        assertThat(originals.writes).isEqualTo(0)
    }

    @Test
    fun `a write that fails outright says so and saves nothing`() = runTest(dispatcher) {
        val vm = vm()
        val uri = original("o10.jpg")
        vm.onImageChosen(uri); advanceUntilIdle()
        // 打开时复制下来的副本没了：拿不到核对用的指纹，也不另存
        ImageIntake(context).clear()

        vm.requestOverwrite(); advanceUntilIdle()

        assertThat(vm.state.value.message).isEqualTo(EditorMessage.Error(R.string.error_overwrite))
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(originals.writes).isEqualTo(0)
    }

    /** 别的应用来要图时导出就是交回去：没有覆盖原图，顶栏上也就没有那个小箭头。 */
    @Test
    fun `a caller's editor has no overwrite`() = runTest(dispatcher) {
        val vm = EditorViewModel(
            intake = ImageIntake(context),
            exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), GallerySink()),
            engineProvider = { emptyEngine() },
            settings = isolatedSettingsStore(context),
            savedState = SavedStateHandle(),
            ioDispatcher = dispatcher,
            forCaller = true,
        )
        advanceUntilIdle()
        assertThat(vm.canOverwrite).isFalse()
    }

    @Test
    fun `a batch asks once and overwrites every opened image`() = runTest(dispatcher) {
        val vm = vm()
        val uris = listOf(original("b1.jpg"), original("b2.jpg"))
        originals.locked += uris
        vm.onImagesChosen(uris); advanceUntilIdle()
        vm.onManualBox(box)
        vm.selectImage(1); advanceUntilIdle()
        vm.onManualBox(box)
        assertThat(vm.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Ready())

        vm.overwriteBatch(); advanceUntilIdle()
        assertThat(vm.state.value.writeRequest).isSameInstanceAs(dialog)
        vm.writeRequestShown()
        originals.allow()
        vm.onWriteRequestResult(true); advanceUntilIdle()

        assertThat(originals.requests).isEqualTo(1)
        assertThat(uris.map { it.whiteUnderBox() }).containsExactly(false, false)
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.BatchOverwritten(overwritten = 2, copies = 0))
    }

    /** 写不回去的那几张照常另存：菜单上先说一声几张会另存，做完再说一声。 */
    @Test
    fun `in a batch the images that cannot be overwritten are saved as copies`() = runTest(dispatcher) {
        val vm = vm()
        val uris = listOf(original("b3.jpg"), original("b4.jpg"))
        originals.foreign += uris[1]
        vm.onImagesChosen(uris); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()
        assertThat(vm.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Ready(copies = 1))

        vm.overwriteBatch(); advanceUntilIdle()

        assertThat(originals.writes).isEqualTo(1)
        assertThat(gallery.writes).isEqualTo(1)
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.BatchOverwritten(overwritten = 1, copies = 1))
    }

    @Test
    fun `a batch where nothing can be overwritten offers no overwrite`() = runTest(dispatcher) {
        val vm = vm()
        val uris = listOf(original("b5.jpg"), original("b6.jpg"))
        originals.foreign += uris
        vm.onImagesChosen(uris); advanceUntilIdle()
        vm.selectImage(1); advanceUntilIdle()

        assertThat(vm.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Unavailable(R.string.overwrite_none_in_batch))
        vm.overwriteBatch(); advanceUntilIdle()

        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.exporting).isFalse()
        assertThat(vm.state.value.message).isNull()
    }

    @Test
    fun `declining the dialog for a batch writes none of it`() = runTest(dispatcher) {
        val vm = vm()
        val uris = listOf(original("b7.jpg"), original("b8.jpg"))
        originals.locked += uris[0]
        vm.onImagesChosen(uris); advanceUntilIdle()
        vm.onManualBox(box)
        vm.selectImage(1); advanceUntilIdle()
        vm.onManualBox(box)

        vm.overwriteBatch(); advanceUntilIdle()
        vm.writeRequestShown()
        vm.onWriteRequestResult(false); advanceUntilIdle()

        assertThat(uris.map { it.whiteUnderBox() }).containsExactly(true, true)
        assertThat(gallery.writes).isEqualTo(0)
        assertThat(vm.state.value.exporting).isFalse()
    }

    /** 还有没看过的图时和导出一样先问一句；选了「只覆盖看过的」，看过的写回原图，没看过的不动。 */
    @Test
    fun `unopened images in a batch are asked about first`() = runTest(dispatcher) {
        val vm = vm()
        val uris = listOf(original("b9.jpg"), original("b10.jpg"), original("b11.jpg"))
        vm.onImagesChosen(uris); advanceUntilIdle()
        vm.onManualBox(box)

        vm.overwriteBatch(); advanceUntilIdle()
        assertThat(vm.state.value.unopenedDialogVisible).isTrue()
        assertThat(vm.state.value.overwriting).isTrue()               // 按钮上写「只覆盖看过的」
        assertThat(uris[0].whiteUnderBox()).isTrue()

        vm.exportOpenedOnly(); advanceUntilIdle()

        assertThat(uris.map { it.whiteUnderBox() }).containsExactly(false, true, true).inOrder()
        assertThat(vm.state.value.message).isEqualTo(EditorMessage.BatchOverwritten(overwritten = 1, copies = 0))
        assertThat(vm.state.value.overwriting).isFalse()
    }

    /** Activity 重建后（见 EditorViewModelRestoreTest）还记得原图在哪儿，照样覆盖得了。 */
    @Test
    fun `a restored session still overwrites the original`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = vm(saved = saved)
        val uri = original("o11.jpg")
        first.onImageChosen(uri); advanceUntilIdle()
        first.onManualBox(box)

        val second = vm(saved = saved)
        advanceUntilIdle()
        assertThat(second.state.value.image).isNotNull()
        assertThat(second.state.value.overwriteOffer).isEqualTo(OverwriteOffer.Ready())

        second.requestOverwrite(); advanceUntilIdle()
        assertThat(uri.whiteUnderBox()).isFalse()
        assertThat(second.state.value.message).isEqualTo(EditorMessage.Overwritten)
    }
}
