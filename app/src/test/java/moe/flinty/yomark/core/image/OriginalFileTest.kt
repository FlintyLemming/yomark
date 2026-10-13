package moe.flinty.yomark.core.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 原图分块读出来的像素没转正，块上找到的点换到转正后的坐标，要和解码时真的转出来的位图（SourceImageLoader.applyOrientation，
 * Skia，同手机上）对得上：八种 EXIF 方向都要。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OriginalFileTest {

    /** 60×40 的白图，(10, 5)–(20, 25) 涂红。 */
    private fun raw() = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).also { bmp ->
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(RectF(10f, 5f, 20f, 25f), Paint().apply { color = Color.RED })
        }
    }

    /** 红色像素的外接框（像素边的口径）。 */
    private fun Bitmap.red(): RectF {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until height) for (x in 0 until width) if (getPixel(x, y) == Color.RED) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
        }
        return RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    }

    @Test fun `points found on the unrotated file land where the upright image has them`() {
        for (orientation in ExifInterface.ORIENTATION_NORMAL..ExifInterface.ORIENTATION_ROTATE_270) {
            val upright = SourceImageLoader.applyOrientation(raw(), orientation)
            val original = OriginalFile(File("unused"), orientation, 60, 40)
            val corners = listOf(10f to 5f, 20f to 5f, 20f to 25f, 10f to 25f).map { (x, y) -> original.toUpright(x, y) }
            val mapped = RectF(corners.minOf { it.x }, corners.minOf { it.y }, corners.maxOf { it.x }, corners.maxOf { it.y })
            assertWithMessage("orientation $orientation").that(mapped).isEqualTo(upright.red())
        }
    }
}
