package moe.flinty.yomark.core.image

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageIntakeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun writeTempPng(name: String): Uri {
        val f = File(context.cacheDir, name)
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).use { bmp ->
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return f.toUri()
    }

    private inline fun <R> Bitmap.use(block: (Bitmap) -> R): R = try { block(this) } finally { recycle() }

    @Test
    fun copies_source_into_app_private_cache() = runTest {
        val intake = ImageIntake(context)
        val result = intake.copyToPrivate(writeTempPng("src.png"))

        assertThat(result.file.exists()).isTrue()
        assertThat(result.file.length()).isGreaterThan(0L)
        // 必须落在应用私有目录里
        assertThat(result.file.canonicalPath).startsWith(context.cacheDir.canonicalPath)
        assertThat(result.file.canonicalPath).contains("intake")
    }

    @Test
    fun copy_survives_deletion_of_the_original() = runTest {
        val uri = writeTempPng("gone.png")
        val intake = ImageIntake(context)
        val result = intake.copyToPrivate(uri)

        File(uri.path!!).delete()
        assertThat(result.file.exists()).isTrue()
        assertThat(result.file.length()).isGreaterThan(0L)
    }

    @Test
    fun two_intakes_do_not_collide() = runTest {
        val intake = ImageIntake(context)
        val a = intake.copyToPrivate(writeTempPng("a.png"))
        val b = intake.copyToPrivate(writeTempPng("b.png"))
        assertThat(a.file.canonicalPath).isNotEqualTo(b.file.canonicalPath)
    }

    @Test
    fun clear_removes_every_copy() = runTest {
        val intake = ImageIntake(context)
        val a = intake.copyToPrivate(writeTempPng("c.png"))
        intake.clear()
        assertThat(a.file.exists()).isFalse()
    }

    @Test
    fun unreadable_uri_throws_rather_than_returning_an_empty_file() = runTest {
        val intake = ImageIntake(context)
        try {
            intake.copyToPrivate(Uri.parse("content://com.yomark.nonexistent/1"))
            throw AssertionError("应该抛异常")
        } catch (e: Exception) {
            assertThat(e).isNotInstanceOf(AssertionError::class.java)
        }
    }
}
