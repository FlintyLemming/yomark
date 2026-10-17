package moe.flinty.yomark.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 交给别的应用的图：经自己的 FileProvider 读得到，重名不覆盖，下一次来要图时清掉。分享出去的在自己的目录里，各清各的。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FileProviderSinkTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** 不经 FileProvider，直接给出文件本身，看写到了哪个文件。 */
    private val sink = FileProviderSink(context) { Uri.fromFile(it) }

    @After fun tearDown() = sink.clear()

    private fun bitmap() = Bitmap.createBitmap(20, 10, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }

    private suspend fun FileProviderSink.write(name: String) =
        write(bitmap(), Bitmap.CompressFormat.JPEG, 95, name, "image/jpeg")

    @Test
    fun `the image is readable through the app's own provider`() = runTest {
        assumeTrue("FileProvider 按 / 拼路径，Windows 上的 Robolectric 对不上", File.separatorChar == '/')
        val uri = FileProviderSink(context).write("YOMARK_20261013_232500.jpg")

        assertThat(uri.scheme).isEqualTo("content")
        assertThat(uri.authority).isEqualTo("${context.packageName}.files")
        assertThat(uri.lastPathSegment).isEqualTo("YOMARK_20261013_232500.jpg")
        val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        assertThat(decoded.width).isEqualTo(20)
        assertThat(decoded.height).isEqualTo(10)
    }

    @Test
    fun `the image lands in the cache, not the gallery`() = runTest {
        val file = File(sink.write("YOMARK_20261013_232500.jpg").path!!)

        assertThat(file.parentFile).isEqualTo(File(context.cacheDir, "handover"))
        assertThat(BitmapFactory.decodeFile(file.path).width).isEqualTo(20)
    }

    @Test
    fun `images exported within the same second do not overwrite each other`() = runTest {
        val names = List(3) { File(sink.write("YOMARK_20261013_232500.jpg").path!!).name }

        assertThat(names).containsExactly(
            "YOMARK_20261013_232500.jpg",
            "YOMARK_20261013_232500_2.jpg",
            "YOMARK_20261013_232500_3.jpg",
        ).inOrder()
    }

    @Test
    fun `clearing removes what was handed over before`() = runTest {
        val file = File(sink.write("YOMARK_20261013_232500.jpg").path!!)

        sink.clear()

        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `a shared image is readable through the provider too`() = runTest {
        assumeTrue("FileProvider 按 / 拼路径，Windows 上的 Robolectric 对不上", File.separatorChar == '/')
        val shared = FileProviderSink(context, FileProviderSink.SHARE)
        val uri = shared.write("YOMARK_20261017_120000.jpg")

        val decoded = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
        assertThat(decoded.width).isEqualTo(20)
        shared.clear()
    }

    @Test
    fun `shared images keep to their own folder, cleared on their own`() = runTest {
        val shared = FileProviderSink(context, FileProviderSink.SHARE) { Uri.fromFile(it) }
        val sharedFile = File(shared.write("YOMARK_20261017_120000.jpg").path!!)
        assertThat(sharedFile.parentFile).isEqualTo(File(context.cacheDir, "share"))

        // 有应用来要图时只清交回去的，分享出去的还在；下一次分享也只清分享出去的
        sink.clear()
        assertThat(sharedFile.exists()).isTrue()
        val handedOver = File(sink.write("YOMARK_20261017_120000.jpg").path!!)
        shared.clear()
        assertThat(sharedFile.exists()).isFalse()
        assertThat(handedOver.exists()).isTrue()
    }
}
