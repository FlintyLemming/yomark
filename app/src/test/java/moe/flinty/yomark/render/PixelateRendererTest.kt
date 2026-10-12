package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
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
class PixelateRendererTest {

    /** 每 4px 一格的红绿棋盘：像素化之后必然变成大块单色。 */
    private fun checkerboard(size: Int = 200): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (x in 0 until size step 4) for (y in 0 until size step 4) {
            p.color = if ((x / 4 + y / 4) % 2 == 0) Color.RED else Color.GREEN
            c.drawRect(x.toFloat(), y.toFloat(), x + 4f, y + 4f, p)
        }
        return bmp
    }

    private val renderer = PixelateRenderer()

    @Test
    fun `style is PIXELATE`() {
        assertThat(renderer.style).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun `block size does not depend on the size of the box`() {
        val bmp = checkerboard(400)
        val small = Quad.fromRect(RectF(0f, 0f, 120f, 40f))
        val large = Quad.fromRect(RectF(0f, 0f, 400f, 400f))
        // 同一张图，大框小框的颗粒一样粗——这是用户报的 bug
        assertThat(renderer.willFallBack(small, 400f, divisor = 8)).isFalse()
        assertThat(renderer.willFallBack(large, 400f, divisor = 8)).isFalse()
        renderer.render(Canvas(bmp), bmp, small, MaskOptions())
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(0f, 100f, 400f, 400f)), MaskOptions())
        // 两个区域里都是 12px 一块：块边界在 12 的倍数上
        assertThat(bmp.getPixel(13, 20)).isEqualTo(bmp.getPixel(23, 20))
        assertThat(bmp.getPixel(13, 120)).isEqualTo(bmp.getPixel(23, 120))
    }

    @Test
    fun `block size scales with the image, not the box`() {
        assertThat(renderer.blockSizeFor(1080f, divisor = 8)).isWithin(0.1f).of(12f)
        assertThat(renderer.blockSizeFor(1800f, divisor = 8)).isWithin(0.1f).of(20f)
    }

    @Test
    fun `block size never drops below the 12px floor`() {
        assertThat(renderer.blockSizeFor(200f, divisor = 8)).isWithin(0.1f).of(PixelateRenderer.MIN_BLOCK_PX)
    }

    @Test
    fun `a region too small for even one block falls back instead of faking a mosaic`() {
        val tiny = Quad.fromRect(RectF(0f, 0f, 20f, 10f))
        assertThat(renderer.willFallBack(tiny, 200f, divisor = 8)).isTrue()

        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, tiny, MaskOptions())
        // 降级成实色块 → 区域内是实色块的颜色
        assertThat(bmp.getPixel(10, 5)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `pixelating a checkerboard makes neighbouring pixels equal`() {
        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())

        // 200px 的图块边长取 12px 下限，(40..51) 是一块，块内任意两点必然同色
        val a = bmp.getPixel(60, 60)
        assertThat(bmp.getPixel(61, 60)).isEqualTo(a)
        assertThat(bmp.getPixel(60, 61)).isEqualTo(a)
    }

    @Test
    fun `pixels outside the quad are untouched`() {
        val bmp = checkerboard()
        val before = bmp.getPixel(5, 5)
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        assertThat(bmp.getPixel(5, 5)).isEqualTo(before)
    }

    @Test
    fun `a rotated quad does not pixelate its axis-aligned corners`() {
        val bmp = checkerboard()
        val before = bmp.getPixel(12, 12)
        val diamond = Quad(PointF(100f, 20f), PointF(180f, 100f), PointF(100f, 180f), PointF(20f, 100f))
        renderer.render(Canvas(bmp), bmp, diamond, MaskOptions())
        assertThat(bmp.getPixel(12, 12)).isEqualTo(before)
    }

    @Test
    fun `a quad partly outside the bitmap does not crash`() {
        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(-50f, -50f, 60f, 60f)), MaskOptions())
    }

    /** 「颗粒」往粗调，块按 8 / divisor 放大；文字行上也拖得出变化。 */
    @Test
    fun `a coarser setting makes bigger blocks`() {
        assertThat(renderer.blockSizeFor(1080f, divisor = 8)).isWithin(0.1f).of(12f)
        assertThat(renderer.blockSizeFor(1080f, divisor = 4)).isWithin(0.1f).of(24f)
        assertThat(renderer.blockSizeFor(1080f, divisor = 3)).isWithin(0.1f).of(32f)
        val line = Quad.fromRect(RectF(0f, 0f, 400f, 40f))
        assertThat(renderer.willFallBack(line, 1080f, divisor = 3)).isFalse()
    }

    @Test
    fun `a larger divisor never goes below the floor`() {
        assertThat(renderer.blockSizeFor(200f, divisor = 100)).isWithin(0.1f).of(PixelateRenderer.MIN_BLOCK_PX)
    }
}
