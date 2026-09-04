package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EraseRendererTest {

    /** 纯色背景上有一块深色文字区 —— 截图里最常见的情形。 */
    private fun flatBackground(bg: Int = Color.rgb(245, 245, 247)): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(bg)
            drawRect(RectF(70f, 90f, 130f, 110f), Paint().apply { color = Color.rgb(20, 20, 20) })
        }
        return bmp
    }

    /** 高频彩色噪点 —— 照片类复杂背景。 */
    private fun noisyBackground(): Bitmap {
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        for (x in 0 until 200 step 2) for (y in 0 until 200 step 2) {
            p.color = Color.rgb((x * 7) % 256, (y * 13) % 256, ((x + y) * 5) % 256)
            c.drawRect(x.toFloat(), y.toFloat(), x + 2f, y + 2f, p)
        }
        return bmp
    }

    private val renderer = EraseRenderer()

    @Test fun `style is ERASE`() {
        assertThat(renderer.style).isEqualTo(MaskStyle.ERASE)
    }

    @Test fun `on a flat background the region is filled with the surrounding colour`() {
        val bmp = flatBackground()
        val quad = Quad.fromRect(RectF(70f, 90f, 130f, 110f))
        assertThat(renderer.willDegrade(bmp, quad)).isFalse()

        renderer.render(Canvas(bmp), bmp, quad, MaskOptions())
        val filled = bmp.getPixel(100, 100)
        assertThat(abs(Color.red(filled) - 245)).isAtMost(8)
        assertThat(abs(Color.green(filled) - 245)).isAtMost(8)
        assertThat(abs(Color.blue(filled) - 247)).isAtMost(8)
    }

    @Test fun `the erased region no longer contains the original dark text`() {
        val bmp = flatBackground()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(70f, 90f, 130f, 110f)), MaskOptions())
        for (x in 72..128 step 4) for (y in 92..108 step 4) {
            assertThat(Color.red(bmp.getPixel(x, y))).isGreaterThan(100)
        }
    }

    @Test fun `a noisy background degrades to a solid block`() {
        val bmp = noisyBackground()
        val quad = Quad.fromRect(RectF(70f, 90f, 130f, 110f))
        assertThat(renderer.willDegrade(bmp, quad)).isTrue()

        renderer.render(Canvas(bmp), bmp, quad, MaskOptions())
        assertThat(bmp.getPixel(100, 100)).isEqualTo(Color.BLACK)
    }

    @Test fun `pixels outside the quad are untouched`() {
        val bmp = flatBackground()
        val before = bmp.getPixel(10, 10)
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(70f, 90f, 130f, 110f)), MaskOptions())
        assertThat(bmp.getPixel(10, 10)).isEqualTo(before)
    }

    @Test fun `a quad at the image edge does not crash`() {
        val bmp = flatBackground()
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(0f, 0f, 20f, 20f)), MaskOptions())
        renderer.render(Canvas(bmp), bmp, Quad.fromRect(RectF(180f, 180f, 200f, 200f)), MaskOptions())
    }

    @Test fun `a quad covering the whole image degrades rather than sampling nothing`() {
        val bmp = flatBackground()
        val whole = Quad.fromRect(RectF(0f, 0f, 200f, 200f))
        renderer.render(Canvas(bmp), bmp, whole, MaskOptions())
        assertThat(bmp.getPixel(100, 100)).isEqualTo(Color.BLACK)
    }
}
