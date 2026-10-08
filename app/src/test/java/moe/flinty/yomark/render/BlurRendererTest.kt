package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BlurRendererTest {

    /** 左半黑右半白：模糊后交界处会出现中间灰。 */
    private fun halfAndHalf(size: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(0f, 0f, size / 2f, size.toFloat(), Paint().apply { color = Color.BLACK })
        }
        return bmp
    }

    private val renderer = BlurRenderer()

    @Test
    fun `style is BLUR`() {
        assertThat(renderer.style).isEqualTo(MaskStyle.BLUR)
    }

    @Test
    fun `blurring a hard edge produces intermediate greys`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(20f, 20f, 180f, 180f)), MaskOptions())

        var greyFound = false
        for (x in 85..115) {
            val v = Color.red(bmp.getPixel(x, 100))
            if (v in 40..215) greyFound = true
        }
        assertThat(greyFound).isTrue()
    }

    @Test
    fun `pixels outside the quad keep their hard edge`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(20f, 20f, 180f, 60f)), MaskOptions())
        // y=150 在遮罩之外，交界仍然是硬的
        assertThat(Color.red(bmp.getPixel(99, 150))).isEqualTo(0)
        assertThat(Color.red(bmp.getPixel(101, 150))).isEqualTo(255)
    }

    @Test
    fun `a larger radius ratio blurs more`() {
        fun spread(ratio: Float): Int {
            val bmp = halfAndHalf()
            renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 10f, 190f, 190f)),
                MaskOptions(blurRadiusRatio = ratio))
            return (10..190).count { x -> Color.red(bmp.getPixel(x, 100)) in 40..215 }
        }
        assertThat(spread(0.16f)).isAtLeast(spread(0.04f))
    }

    @Test
    fun `a tiny region does not crash`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(0f, 0f, 3f, 3f)), MaskOptions())
    }

    @Test
    fun `a quad partly outside the bitmap does not crash`() {
        val bmp = halfAndHalf()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(-40f, -40f, 40f, 40f)), MaskOptions())
    }
}
