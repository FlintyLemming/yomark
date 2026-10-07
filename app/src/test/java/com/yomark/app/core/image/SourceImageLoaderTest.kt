package com.yomark.app.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.max

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceImageLoaderTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 左上角红、其余白的 JPEG，用来验证旋转确实发生在像素上。 */
    private fun writeJpeg(name: String, w: Int, h: Int, orientation: Int? = null): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, w / 4f, h / 4f, Paint().apply { color = Color.RED })
        }
        val f = tmp.newFile(name)
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
        if (orientation != null) {
            ExifInterface(f.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        return f
    }

    @Test
    fun `small image is not downsampled and scale is one`() = runTest {
        val f = writeJpeg("small.jpg", 800, 600)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(img.bitmap.width).isEqualTo(800)
        assertThat(img.bitmap.height).isEqualTo(600)
        assertThat(img.scale).isWithin(1e-4f).of(1f)
        assertThat(img.originalWidth).isEqualTo(800)
    }

    @Test
    fun `long edge above 2048 is downsampled by a power of two`() = runTest {
        val f = writeJpeg("big.jpg", 4800, 2400)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(max(img.bitmap.width, img.bitmap.height)).isAtMost(SourceImageLoader.ANALYSIS_MAX_EDGE)
        // inSampleSize=4 → 1200x600
        assertThat(img.bitmap.width).isEqualTo(1200)
        assertThat(img.scale).isWithin(1e-3f).of(0.25f)
        assertThat(img.originalWidth).isEqualTo(4800)
        assertThat(img.originalHeight).isEqualTo(2400)
    }

    @Test
    fun `rotate 90 exif swaps width and height`() = runTest {
        val f = writeJpeg("rot.jpg", 400, 200, ExifInterface.ORIENTATION_ROTATE_90)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(img.bitmap.width).isEqualTo(200)
        assertThat(img.bitmap.height).isEqualTo(400)
        // originalWidth/Height 也是转正后的尺寸——全流程只有一个坐标系
        assertThat(img.originalWidth).isEqualTo(200)
        assertThat(img.originalHeight).isEqualTo(400)
    }

    @Test
    fun `rotate 90 moves the red corner from top-left to top-right`() = runTest {
        val f = writeJpeg("rotpixel.jpg", 400, 200, ExifInterface.ORIENTATION_ROTATE_90)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        fun isRed(c: Int) = Color.red(c) > 180 && Color.green(c) < 90
        assertThat(isRed(img.bitmap.getPixel(img.bitmap.width - 5, 5))).isTrue()
        assertThat(isRed(img.bitmap.getPixel(5, 5))).isFalse()
    }

    @Test
    fun `toOriginal maps a downsampled quad back to full resolution`() = runTest {
        val f = writeJpeg("map.jpg", 4800, 2400)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        val q = com.yomark.app.core.geometry.Quad.fromRect(android.graphics.RectF(100f, 50f, 200f, 100f))
        assertThat(img.toOriginal(q).bounds())
            .isEqualTo(android.graphics.RectF(400f, 200f, 800f, 400f))
    }

    @Test
    fun `export decode keeps full resolution below the pixel cap`() = runTest {
        val f = writeJpeg("exp.jpg", 3000, 2000)
        val out = SourceImageLoader.loadForExport(f)
        assertThat(out.bitmap.width).isEqualTo(3000)
        assertThat(out.downscaled).isFalse()
    }

    @Test
    fun `export decode downsamples beyond 32 megapixels`() = runTest {
        // 8000x5000 = 40 MP > 32 MP → inSampleSize=2 → 4000x2500
        val f = writeJpeg("huge.jpg", 8000, 5000)
        val out = SourceImageLoader.loadForExport(f)
        assertThat(out.bitmap.width.toLong() * out.bitmap.height).isAtMost(SourceImageLoader.EXPORT_MAX_PIXELS.toLong())
        assertThat(out.downscaled).isTrue()
    }
}
