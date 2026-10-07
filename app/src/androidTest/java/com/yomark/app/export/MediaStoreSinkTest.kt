package com.yomark.app.export

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaStoreSinkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun writes_a_visible_non_pending_image_without_any_permission_prompt() = runTest {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val uri = MediaStoreSink(context).write(bmp, Bitmap.CompressFormat.JPEG, 95, "yomark-test.jpg", "image/jpeg")

        context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)!!.use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getInt(0)).isEqualTo(0)
        }
        context.contentResolver.delete(uri, null, null)
    }

    @Test
    fun written_file_has_no_exif_metadata() = runTest {
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        val uri = MediaStoreSink(context).write(bmp, Bitmap.CompressFormat.JPEG, 95, "yomark-exif.jpg", "image/jpeg")

        context.contentResolver.openInputStream(uri)!!.use { input ->
            val exif = ExifInterface(input)
            assertThat(exif.latLong).isNull()
            assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
            assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME)).isNull()
        }
        context.contentResolver.delete(uri, null, null)
    }
}
