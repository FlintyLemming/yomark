package moe.flinty.yomark.export

import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.os.BundleCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * 写回相册里的原图：先核对原图还是打开时那一张，写坏了把原来的放回去；要许可时给出系统的确认框，点过允许的不再问。
 * 相册换成 [FakeMedia]，跑的是 Android 11 起的那一套（createWriteRequest）。
 */
@RunWith(RobolectricTestRunner::class)
class MediaOriginalsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /**
     * 相册（content://media）：每一张是 cacheDir 里的一个文件。[locked] 里的没给写的许可，[unreadable] 里的读不了，
     * 接下来 [brokenWrites] 次写到一半就坏（文件已经截断了，写不进去）。createWriteRequest 问的记在 [requests] 里。
     */
    class FakeMedia : ContentProvider() {
        val files = HashMap<Uri, File>()
        val locked = HashSet<Uri>()
        val unreadable = HashSet<Uri>()
        var brokenWrites = 0
        val requests = ArrayList<List<Uri>>()
        lateinit var dialog: PendingIntent

        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            val file = files[uri] ?: throw FileNotFoundException("没有这张：$uri")
            val writing = 'w' in mode
            if (writing && uri in locked) throw SecurityException("没有写的许可：$uri")
            if (!writing && uri in unreadable) throw SecurityException("没有读的许可：$uri")
            // 和系统一样：带 t 才截断，光 w 不截断
            if (writing && 't' in mode) file.writeBytes(ByteArray(0))
            if (writing && brokenWrites > 0) {
                brokenWrites--
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
            return ParcelFileDescriptor.open(
                file,
                if (writing) ParcelFileDescriptor.MODE_READ_WRITE else ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }

        /** MediaStore.createWriteRequest 走的是这里：要写的几张放在 clip_data 里，交回 result 里的确认框。 */
        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method != "create_write_request") return null
            val clip = BundleCompat.getParcelable(extras!!, "clip_data", ClipData::class.java)!!
            requests += (0 until clip.itemCount).map { clip.getItemAt(it).uri }
            return Bundle().apply { putParcelable("result", dialog) }
        }

        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private lateinit var media: FakeMedia

    /** checkUriPermission 认的授权（系统文件选择器给的、确认框上点了允许的）。 */
    private val grants = HashSet<Uri>()

    private val originals by lazy { MediaOriginals(context, Dispatchers.Unconfined) { it in grants } }

    private val dialog by lazy { PendingIntent.getActivity(context, 0, Intent("write"), PendingIntent.FLAG_IMMUTABLE) }

    @Before fun setUp() {
        media = Robolectric.setupContentProvider(FakeMedia::class.java, MediaStore.AUTHORITY)
        media.dialog = dialog
    }

    /** 相册里的一张图，内容是 [bytes]。 */
    private fun photo(id: Long, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://media/external/images/media/$id")
        media.files[uri] = File(context.cacheDir, "photo-$id.jpg").apply { writeBytes(bytes) }
        return uri
    }

    private fun Uri.bytes() = media.files[this]!!.readBytes()

    private val old = ByteArray(200_000) { (it % 251).toByte() }
    private val new = ByteArray(1000) { 7 }

    private fun printOf(bytes: ByteArray) = Fingerprint.read(bytes.inputStream())

    private suspend fun MediaOriginals.overwrite(target: Uri, expected: Fingerprint = printOf(old)) =
        overwrite(target, expected) { it.write(new) }

    private fun leftovers() = File(context.cacheDir, "overwrite").listFiles().orEmpty().toList()

    // ---------- 找原图 ----------

    @Test
    fun `an image we may already write is its own original`() = runTest {
        val picked = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADCIM%2Fa.jpg")
        grants += picked
        assertThat(originals.locate(picked)).isEqualTo(picked)
    }

    @Test
    fun `a photo picker image traces back to its gallery item`() = runTest {
        val picked = Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/33")
        assertThat(originals.locate(picked)).isEqualTo(Uri.parse("content://media/external/images/media/33"))
        assertThat(originals.locate(Uri.parse("content://com.example.chat.files/a.jpg"))).isNull()
    }

    // ---------- 要许可 ----------

    @Test
    fun `gallery items without a grant are asked for in one dialog`() = runTest {
        val a = photo(1, old).also { media.locked += it }
        val b = photo(2, old).also { media.locked += it }

        assertThat(originals.writeRequest(listOf(a, b, a))).isEqualTo(dialog.intentSender)
        assertThat(media.requests).containsExactly(listOf(a, b))
    }

    @Test
    fun `only the ones still lacking a grant are asked for`() = runTest {
        val a = photo(1, old).also { media.locked += it }
        val b = photo(2, old).also { media.locked += it }
        grants += a

        originals.writeRequest(listOf(a, b))
        assertThat(media.requests).containsExactly(listOf(b))
    }

    @Test
    fun `nothing is asked once every image may be written`() = runTest {
        val a = photo(1, old).also { media.locked += it }
        grants += a
        assertThat(originals.writeRequest(listOf(a))).isNull()
        assertThat(media.requests).isEmpty()
    }

    /** 自己导出的图，或者确认框上点过允许、checkUriPermission 却没认出来的：写得了就不问。 */
    @Test
    fun `an image that opens for writing is not asked for`() = runTest {
        assertThat(originals.writeRequest(listOf(photo(1, old)))).isNull()
        assertThat(media.requests).isEmpty()
    }

    /** 只有相册里的图问得了；别处的授权没了，写的时候另存。 */
    @Test
    fun `images outside the gallery are never asked for`() = runTest {
        assertThat(originals.writeRequest(listOf(Uri.parse("content://com.example.files/a.jpg")))).isNull()
        assertThat(media.requests).isEmpty()
    }

    // ---------- 写 ----------

    @Test
    fun `the new image replaces the original whole`() = runTest {
        val uri = photo(1, old)

        val written = originals.overwrite(uri)

        assertThat(uri.bytes()).isEqualTo(new)                  // 新图短得多，原来的尾巴一点不剩
        assertThat(written).isEqualTo(printOf(new))
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun `an original that is no longer the opened one is left alone`() = runTest {
        val uri = photo(1, old)

        val thrown = runCatching { originals.overwrite(uri, expected = printOf(old + byteArrayOf(1))) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(OriginalUnavailable::class.java)
        assertThat(uri.bytes()).isEqualTo(old)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun `a deleted original is left alone`() = runTest {
        val uri = photo(1, old).also { media.files.remove(it) }
        assertThat(runCatching { originals.overwrite(uri) }.exceptionOrNull()).isInstanceOf(OriginalUnavailable::class.java)
    }

    @Test
    fun `without permission to read or write nothing is touched`() = runTest {
        val unreadable = photo(1, old).also { media.unreadable += it }
        val locked = photo(2, old).also { media.locked += it }

        assertThat(runCatching { originals.overwrite(unreadable) }.exceptionOrNull()).isInstanceOf(OriginalUnavailable::class.java)
        assertThat(runCatching { originals.overwrite(locked) }.exceptionOrNull()).isInstanceOf(OriginalUnavailable::class.java)
        assertThat(unreadable.bytes()).isEqualTo(old)
        assertThat(locked.bytes()).isEqualTo(old)
    }

    /** 原图已经截断了才写坏：把打开前留的备份写回去，原图还是原来那一张。不当成「原图不在」，不另存。 */
    @Test
    fun `a write that breaks halfway puts the original back`() = runTest {
        val uri = photo(1, old)
        media.brokenWrites = 1

        val thrown = runCatching { originals.overwrite(uri) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(IOException::class.java)
        assertThat(thrown).isNotInstanceOf(OriginalUnavailable::class.java)
        assertThat(uri.bytes()).isEqualTo(old)
        assertThat(leftovers()).isEmpty()
    }

    @Test
    fun `an image that fails to encode never reaches the original`() = runTest {
        val uri = photo(1, old)

        val thrown = runCatching { originals.overwrite(uri, printOf(old)) { throw IOException("编码失败") } }.exceptionOrNull()

        assertThat(thrown).hasMessageThat().isEqualTo("编码失败")
        assertThat(uri.bytes()).isEqualTo(old)
        assertThat(leftovers()).isEmpty()
    }

    // ---------- 格式 ----------

    /** 导出只编 PNG、JPEG：别的格式写回去，文件里的东西就和它的类型对不上了。 */
    @Test
    fun `only png and jpeg originals keep their format`() {
        assertThat(Originals.keepsFormat("image/jpeg")).isTrue()
        assertThat(Originals.keepsFormat("image/PNG")).isTrue()
        assertThat(Originals.keepsFormat("image/heic")).isFalse()
        assertThat(Originals.keepsFormat("image/webp")).isFalse()
        assertThat(Originals.keepsFormat("image/gif")).isFalse()
    }
}
