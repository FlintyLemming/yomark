package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** 文本：框正好包住一行字，字照框的大小画在框里；垫底色时铺满框，字取黑白里对得开的那个。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class TextRendererTest {

    private fun white(w: Int = 400, h: Int = 200) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun Bitmap.count(inside: RectF? = null, outside: RectF? = null, test: (Int) -> Boolean): Int {
        var n = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (inside != null && !inside.contains(x + 0.5f, y + 0.5f)) continue
            if (outside != null && outside.contains(x + 0.5f, y + 0.5f)) continue
            if (test(getPixel(x, y))) n++
        }
        return n
    }

    @Test fun `the box is sized for the text and gives the size back`() {
        val box = TextStamp.boxSize("Hello", 40f)
        val quad = Quad.fromRect(RectF(10f, 10f, 10f + box.width, 10f + box.height))
        assertThat(TextStamp.sizeIn("Hello", quad)).isWithin(0.01f).of(40f)
        // 字号翻倍，框也翻倍：导出时框按比例放大，字跟着放大
        val twice = TextStamp.boxSize("Hello", 80f)
        assertThat(twice.width).isWithin(0.5f).of(box.width * 2f)
        assertThat(twice.height).isWithin(0.01f).of(box.height * 2f)
        assertThat(TextStamp.sizeForHeight(box.height)).isWithin(0.01f).of(40f)
    }

    @Test fun `the text is drawn inside its box in its colour and nowhere else`() {
        val bmp = white()
        val quad = TextStamp.boxAt("WWWW", 60f, PointF(200f, 100f), bmp.width, bmp.height)
        TextRenderer().render(Canvas(bmp), bmp, quad, MaskOptions(), text = "WWWW")
        val box = quad.bounds()
        assertThat(bmp.count(inside = box) { it == MaskOptions.DEFAULT_TEXT_COLOR }).isGreaterThan(200)
        assertThat(bmp.count(outside = RectF(box).apply { inset(-1f, -1f) }) { it != Color.WHITE }).isEqualTo(0)
        // 不垫底色：字缝里还是原来的图
        assertThat(bmp.count(inside = box) { it == Color.WHITE }).isGreaterThan(200)
    }

    @Test fun `a background fills the box and the text takes black or white against it`() {
        val bmp = white()
        val quad = TextStamp.boxAt("WWWW", 60f, PointF(200f, 100f), bmp.width, bmp.height)
        val black = 0xFF000000.toInt()
        TextRenderer().render(Canvas(bmp), bmp, quad, MaskOptions(textColor = black, textBackground = true), text = "WWWW")
        val box = quad.bounds()
        assertThat(bmp.getPixel(box.left.toInt() + 4, box.centerY().toInt())).isEqualTo(black)
        assertThat(bmp.count(inside = box) { it == Color.WHITE }).isGreaterThan(200)   // 白字
        assertThat(bmp.count(outside = RectF(box).apply { inset(-1f, -1f) }) { it != Color.WHITE }).isEqualTo(0)
    }

    @Test fun `text on a background is black on light colours and white on dark ones`() {
        assertThat(TextStamp.onColor(0xFFFDD835.toInt())).isEqualTo(Color.BLACK)     // 黄
        assertThat(TextStamp.onColor(0xFFFFFFFF.toInt())).isEqualTo(Color.BLACK)
        assertThat(TextStamp.onColor(MaskOptions.DEFAULT_TEXT_COLOR)).isEqualTo(Color.WHITE)
        assertThat(TextStamp.onColor(0xFF1E88E5.toInt())).isEqualTo(Color.WHITE)     // 蓝
    }

    /** 中文和英文一样量、一样画：框按字的宽窄来，字都落在框里。 */
    @Test fun `chinese text is measured and drawn inside its box`() {
        val bmp = white()
        assertThat(TextStamp.boxSize("签收人", 40f).width).isGreaterThan(TextStamp.boxSize("签", 40f).width * 2f)
        val quad = TextStamp.boxAt("签收人", 60f, PointF(200f, 100f), bmp.width, bmp.height)
        TextRenderer().render(Canvas(bmp), bmp, quad, MaskOptions(), text = "签收人")
        val box = quad.bounds()
        assertThat(bmp.count(inside = box) { it != Color.WHITE }).isGreaterThan(200)
        assertThat(bmp.count(outside = RectF(box).apply { inset(-1f, -1f) }) { it != Color.WHITE }).isEqualTo(0)
    }

    @Test fun `blank text draws nothing`() {
        val bmp = white()
        TextRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 10f, 200f, 100f)), MaskOptions(textBackground = true), text = " ")
        TextRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 10f, 200f, 100f)), MaskOptions(textBackground = true), text = null)
        assertThat(bmp.count { it != Color.WHITE }).isEqualTo(0)
    }

    @Test fun `a box near the edge is moved into the image`() {
        val quad = TextStamp.boxAt("Hello", 40f, PointF(395f, 5f), 400, 200).bounds()
        assertThat(quad.right).isAtMost(400f)
        assertThat(quad.top).isAtLeast(0f)
        assertThat(quad.width()).isWithin(0.01f).of(TextStamp.boxSize("Hello", 40f).width)
    }

    /** 一行比整张图还宽：字号缩到正好写得下，导出时一个字也不出图。 */
    @Test fun `a line wider than the image shrinks to fit it`() {
        val long = "This line is far too long for a small image"
        val quad = TextStamp.boxAt(long, 60f, PointF(100f, 100f), 400, 200).bounds()
        assertThat(quad.left).isAtLeast(0f)
        assertThat(quad.right).isWithin(0.5f).of(400f)
        assertThat(TextStamp.sizeIn(long, Quad.fromRect(quad))).isLessThan(60f)
    }

    /** 预览画在分析图上、导出画在原图上：框按比例放大，画出来的字也按比例放大，落在同样的位置。 */
    @Test fun `the export draws the same text scaled with its box`() {
        val small = white(200, 100)
        val large = white(400, 200)
        val quad = TextStamp.boxAt("Hi", 30f, PointF(100f, 50f), small.width, small.height)
        TextRenderer().render(Canvas(small), small, quad, MaskOptions(), text = "Hi")
        TextRenderer().render(Canvas(large), large, quad.scaled(2f), MaskOptions(), text = "Hi")
        val inked = small.count { it != Color.WHITE }
        assertThat(large.count { it != Color.WHITE }.toFloat() / inked).isWithin(0.5f).of(4f)
        // 字的位置一样：两边各取有字的范围，换算到同一个比例后差不到一两个像素
        fun Bitmap.inkBounds(): RectF {
            var l = width; var t = height; var r = -1; var b = -1
            for (y in 0 until height) for (x in 0 until width) if (getPixel(x, y) != Color.WHITE) {
                l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x); b = maxOf(b, y)
            }
            return RectF(l.toFloat(), t.toFloat(), r + 1f, b + 1f)
        }
        val s = small.inkBounds()
        val g = large.inkBounds()
        assertThat(g.left / 2f).isWithin(1.5f).of(s.left)
        assertThat(g.right / 2f).isWithin(1.5f).of(s.right)
        assertThat(g.top / 2f).isWithin(1.5f).of(s.top)
        assertThat(g.bottom / 2f).isWithin(1.5f).of(s.bottom)
    }
}
