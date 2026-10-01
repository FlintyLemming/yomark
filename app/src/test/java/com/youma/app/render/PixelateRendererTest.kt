package com.youma.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskStyle
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
    fun `block size is the short edge over the divisor`() {
        val q = Quad.fromRect(RectF(0f, 0f, 320f, 160f))
        assertThat(renderer.blockSizeFor(q, divisor = 8)).isWithin(0.1f).of(20f)
    }

    @Test
    fun `block size never drops below the 12px floor`() {
        val q = Quad.fromRect(RectF(0f, 0f, 200f, 40f))     // 短边 40 / 8 = 5 → 抬到 12
        assertThat(renderer.blockSizeFor(q, divisor = 8)).isWithin(0.1f).of(PixelateRenderer.MIN_BLOCK_PX)
    }

    @Test
    fun `a region too small for even one block falls back instead of faking a mosaic`() {
        val tiny = Quad.fromRect(RectF(0f, 0f, 20f, 10f))
        assertThat(renderer.willFallBack(tiny, divisor = 8)).isTrue()

        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, tiny, MaskOptions())
        // 降级成实色块 → 区域内是实色块的颜色
        assertThat(bmp.getPixel(10, 5)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `pixelating a checkerboard makes neighbouring pixels equal`() {
        val bmp = checkerboard()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())

        // 块边长 = 120/8 = 15px，块内任意两点必然同色
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

    @Test
    fun `a larger divisor makes smaller blocks but never below the floor`() {
        val q = Quad.fromRect(RectF(0f, 0f, 400f, 400f))
        assertThat(renderer.blockSizeFor(q, divisor = 4)).isWithin(0.1f).of(100f)
        assertThat(renderer.blockSizeFor(q, divisor = 100)).isWithin(0.1f).of(PixelateRenderer.MIN_BLOCK_PX)
    }
}
