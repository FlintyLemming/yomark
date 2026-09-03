package com.dama.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkDrawerTest {

    private val drawer = WatermarkDrawer()

    private fun bitmap(w: Int, h: Int, color: Int = Color.WHITE) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun `default corner is bottom right`() {
        val p = drawer.placement(1000, 2000, emptyList())
        assertThat(p.corner).isEqualTo(WatermarkCorner.BOTTOM_RIGHT)
        assertThat(p.outlined).isFalse()
    }

    @Test
    fun `height is four percent of the short edge with a 24px floor`() {
        assertThat(drawer.placement(1000, 2000, emptyList()).rect.height())
            .isWithin(0.5f).of(40f)                      // 1000 * 0.04
        assertThat(drawer.placement(200, 300, emptyList()).rect.height())
            .isWithin(0.5f).of(24f)                      // 200*0.04 = 8 → 下限 24
    }

    @Test
    fun `margin is three percent of the short edge`() {
        val p = drawer.placement(1000, 2000, emptyList())
        assertThat(1000f - p.rect.right).isWithin(0.5f).of(30f)
        assertThat(2000f - p.rect.bottom).isWithin(0.5f).of(30f)
    }

    @Test
    fun `an occupied bottom right corner moves the watermark to bottom left`() {
        val masked = listOf(RectF(600f, 1700f, 1000f, 2000f))
        val p = drawer.placement(1000, 2000, masked)
        assertThat(p.corner).isEqualTo(WatermarkCorner.BOTTOM_LEFT)
        assertThat(RectF(p.rect).intersect(masked[0])).isFalse()
    }

    @Test
    fun `both bottom corners occupied moves the watermark to top left`() {
        val masked = listOf(RectF(0f, 1700f, 1000f, 2000f))
        assertThat(drawer.placement(1000, 2000, masked).corner).isEqualTo(WatermarkCorner.TOP_LEFT)
    }

    @Test
    fun `three corners occupied moves the watermark to top right`() {
        val masked = listOf(
            RectF(0f, 1700f, 1000f, 2000f),      // 整条底边
            RectF(0f, 0f, 500f, 300f),           // 左上
        )
        assertThat(drawer.placement(1000, 2000, masked).corner).isEqualTo(WatermarkCorner.TOP_RIGHT)
    }

    @Test
    fun `all four corners occupied falls back to bottom right with an outline`() {
        val masked = listOf(RectF(0f, 0f, 1000f, 2000f))
        val p = drawer.placement(1000, 2000, masked)
        assertThat(p.corner).isEqualTo(WatermarkCorner.BOTTOM_RIGHT)
        assertThat(p.outlined).isTrue()
    }

    @Test
    fun `ink turns dark on a bright background`() {
        val bmp = bitmap(400, 400, Color.WHITE)
        assertThat(drawer.draw(Canvas(bmp), bmp, emptyList()).darkInk).isTrue()
    }

    @Test
    fun `ink turns light on a dark background`() {
        val bmp = bitmap(400, 400, Color.rgb(20, 20, 24))
        assertThat(drawer.draw(Canvas(bmp), bmp, emptyList()).darkInk).isFalse()
    }

    @Test
    fun `drawing actually changes pixels inside the placement`() {
        val bmp = bitmap(400, 400, Color.WHITE)
        val p = drawer.draw(Canvas(bmp), bmp, emptyList())
        val cx = p.rect.centerX().toInt()
        val cy = p.rect.centerY().toInt()
        var changed = false
        for (dx in -6..6) for (dy in -6..6) {
            if (bmp.getPixel((cx + dx).coerceIn(0, 399), (cy + dy).coerceIn(0, 399)) != Color.WHITE) changed = true
        }
        assertThat(changed).isTrue()
    }

    @Test
    fun `drawing never touches a masked region`() {
        val bmp = bitmap(600, 600, Color.WHITE)
        val masked = RectF(300f, 300f, 600f, 600f)
        Canvas(bmp).drawRect(masked, Paint().apply { color = Color.BLACK })

        drawer.draw(Canvas(bmp), bmp, listOf(masked))

        // 遮罩区内必须仍是纯黑：水印一个像素都不许压上去
        for (x in 310 until 590 step 20) for (y in 310 until 590 step 20) {
            assertThat(bmp.getPixel(x, y)).isEqualTo(Color.BLACK)
        }
    }
}
