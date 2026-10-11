package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 色块的近似色（原先的「抹除」）：每块取四周一圈的中位色填上。背景再杂也不降级成固定颜色。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class NearColorTest {

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

    private val text = Quad.fromRect(RectF(70f, 90f, 130f, 110f))
    private val near = MaskOptions(solidNear = true)

    private fun assertClose(actual: Int, r: Int, g: Int, b: Int) {
        assertThat(abs(Color.red(actual) - r)).isAtMost(8)
        assertThat(abs(Color.green(actual) - g)).isAtMost(8)
        assertThat(abs(Color.blue(actual) - b)).isAtMost(8)
    }

    @Test fun `on a flat background the block is filled with the surrounding colour`() {
        val bmp = flatBackground()
        SolidRenderer().render(Canvas(bmp), bmp, text, near)
        assertClose(bmp.getPixel(100, 100), 245, 245, 247)
    }

    @Test fun `the block no longer contains the original dark text`() {
        val bmp = flatBackground()
        SolidRenderer().render(Canvas(bmp), bmp, text, near)
        for (x in 72..128 step 4) for (y in 92..108 step 4) {
            assertThat(Color.red(bmp.getPixel(x, y))).isGreaterThan(100)
        }
    }

    @Test fun `the block is opaque whatever the surroundings`() {
        val bmp = flatBackground()
        SolidRenderer().render(Canvas(bmp), bmp, text, near)
        assertThat(Color.alpha(bmp.getPixel(100, 100))).isEqualTo(255)
    }

    /** 原先的抹除在这里降级成天蓝色块；近似色照样取四周的中位色，只是看得出是一块。 */
    @Test fun `a noisy background still gets its near colour, not the fixed one`() {
        val bmp = noisyBackground()
        val expected = NearColor.around(bmp, text)
        assertThat(expected).isNotNull()
        SolidRenderer().render(Canvas(bmp), bmp, text, near)
        assertThat(bmp.getPixel(100, 100)).isEqualTo(expected)
        assertThat(bmp.getPixel(100, 100)).isNotEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test fun `with near colour off the fixed colour is used`() {
        val bmp = flatBackground()
        SolidRenderer().render(Canvas(bmp), bmp, text, MaskOptions(solidNear = false))
        assertThat(bmp.getPixel(100, 100)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test fun `pixels outside the quad are untouched`() {
        val bmp = flatBackground()
        val before = bmp.getPixel(10, 10)
        SolidRenderer().render(Canvas(bmp), bmp, text, near)
        assertThat(bmp.getPixel(10, 10)).isEqualTo(before)
    }

    @Test fun `a quad at the image edge does not crash`() {
        val bmp = flatBackground()
        SolidRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(0f, 0f, 20f, 20f)), near)
        SolidRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(180f, 180f, 200f, 200f)), near)
    }

    /** 盖满整张图，四周没有像素可取：退回固定的颜色，而不是取一个谁也说不清的颜色。 */
    @Test fun `a quad covering the whole image falls back to the fixed colour`() {
        val bmp = flatBackground()
        val whole = Quad.fromRect(RectF(0f, 0f, 200f, 200f))
        assertThat(NearColor.around(bmp, whole)).isNull()
        SolidRenderer().render(Canvas(bmp), bmp, whole, near.copy(solidColor = Color.BLACK))
        assertThat(bmp.getPixel(100, 100)).isEqualTo(Color.BLACK)
    }

    /** 白和差几个数的白是同一种；白和聊天截图那种浅灰底（差三十八）是两种。多的排前面。 */
    @Test fun `heaps merge noise but keep white apart from light grey`() {
        val grey = Color.rgb(240, 242, 245)
        val heaps = NearColor.heaps(listOf(Color.WHITE, grey, Color.rgb(252, 253, 251), grey, grey))
        assertThat(heaps.map { it.count }).containsExactly(3, 2).inOrder()
        assertThat(heaps.first().color).isEqualTo(grey)
    }

    @Test fun `the dominant colour is the one covering most of the image`() {
        val bmp = flatBackground()
        assertClose(NearColor.dominant(bmp)!!, 245, 245, 247)
    }

    /** 采样环宽度按 renderScale 折算：分析图上与原图上取到的是同一种颜色。 */
    @Test fun `the ring scales with the render scale`() {
        val bmp = flatBackground(bg = Color.rgb(30, 120, 200))
        assertThat(NearColor.around(bmp, text, renderScale = 0.5f)).isEqualTo(NearColor.around(bmp, text))
    }
}
