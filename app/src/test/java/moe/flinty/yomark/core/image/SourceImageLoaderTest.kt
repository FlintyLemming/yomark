package moe.flinty.yomark.core.image

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

    /** 截图、长截图按原图识别：按长边缩，1080 宽的截图缩到 540、长截图缩到 270，聊天里的小字和头像就认不出了。 */
    @Test
    fun `screenshots keep their full width`() {
        fun sample(w: Int, h: Int) = SourceImageLoader.sampleSizeForAnalysis(w, h)
        assertThat(sample(1080, 1920)).isEqualTo(1)
        assertThat(sample(1080, 2400)).isEqualTo(1)
        assertThat(sample(1170, 2532)).isEqualTo(1)
        assertThat(sample(1440, 3200)).isEqualTo(1)
        assertThat(sample(1080, 5148)).isEqualTo(1)
        assertThat(sample(720, 5000)).isEqualTo(1)
        assertThat(sample(2400, 1080)).isEqualTo(1)                // 横着存的截图（EXIF 转正之前）一样
        assertThat(sample(1812, 2176)).isEqualTo(1)                // 折叠屏内屏
        assertThat(sample(2048, 2732)).isEqualTo(1)                // iPad
    }

    /** 再长的长截图：长边不过 8192、像素不过一条 1080×8192，超了就缩一半，还是比按长边缩到 2048 清楚得多。 */
    @Test
    fun `very long screenshots are halved, not squeezed to 2048`() {
        fun sample(w: Int, h: Int) = SourceImageLoader.sampleSizeForAnalysis(w, h)
        assertThat(sample(1080, 8192)).isEqualTo(1)
        assertThat(sample(1080, 10000)).isEqualTo(2)
        assertThat(sample(1440, 6000)).isEqualTo(1)
        assertThat(sample(1440, 8000)).isEqualTo(2)
        assertThat(sample(1080, 20000)).isEqualTo(4)
    }

    /** 照片（短边 2160 以上）照旧按长边缩到 2048 以内。 */
    @Test
    fun `photos are still downsampled by their long edge`() {
        fun sample(w: Int, h: Int) = SourceImageLoader.sampleSizeForAnalysis(w, h)
        assertThat(sample(4000, 3000)).isEqualTo(2)
        assertThat(sample(3000, 4000)).isEqualTo(2)
        assertThat(sample(4032, 3024)).isEqualTo(2)
        assertThat(sample(4032, 2268)).isEqualTo(2)
        assertThat(sample(3840, 2160)).isEqualTo(2)
        assertThat(sample(4624, 3472)).isEqualTo(4)
        assertThat(sample(6000, 4000)).isEqualTo(4)
        assertThat(sample(8160, 6120)).isEqualTo(4)
        assertThat(sample(800, 600)).isEqualTo(1)
    }

    /** 截图按原图识别，像素再多也不会缩得比照片的缩法还小。 */
    @Test
    fun `a screenshot is never shrunk more than a photo of its size`() {
        for (w in 320..2159 step 37) for (h in w..30000 step 211) {
            val photo = SourceImageLoader.sampleSizeForEdge(h, SourceImageLoader.ANALYSIS_MAX_EDGE)
            assertThat(SourceImageLoader.sampleSizeForAnalysis(w, h)).isAtMost(photo)
        }
    }

    @Test
    fun `a long screenshot is analysed at full size`() = runTest {
        val f = writeJpeg("long.jpg", 1080, 5148)
        val img = SourceImageLoader.loadForAnalysis(f, "image/jpeg")
        assertThat(img.bitmap.width).isEqualTo(1080)
        assertThat(img.bitmap.height).isEqualTo(5148)
        assertThat(img.scale).isWithin(1e-4f).of(1f)
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
        val q = moe.flinty.yomark.core.geometry.Quad.fromRect(android.graphics.RectF(100f, 50f, 200f, 100f))
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
