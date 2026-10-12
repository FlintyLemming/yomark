package moe.flinty.yomark.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrandGlyphTest {

    private fun n(v: Float) = if (v % 1f == 0f) v.toInt().toString() else v.toString()

    /** 照 docs/icon/src/icons.mjs 的 rrPath 拼圆角矩形的路径 */
    private fun rr(r: RectF, radius: Float): String {
        val (x, y, w, h, k) = listOf(r.left, r.top, r.width(), r.height(), radius)
        return "M${n(x + k)} ${n(y)}H${n(x + w - k)}A${n(k)} ${n(k)} 0 0 1 ${n(x + w)} ${n(y + k)}" +
            "V${n(y + h - k)}A${n(k)} ${n(k)} 0 0 1 ${n(x + w - k)} ${n(y + h)}" +
            "H${n(x + k)}A${n(k)} ${n(k)} 0 0 1 ${n(x)} ${n(y + h - k)}V${n(y + k)}A${n(k)} ${n(k)} 0 0 1 ${n(x + k)} ${n(y)}Z"
    }

    @Test
    fun `the glyph is drawn from the same geometry as the icon`() {
        // 单元测试的工作目录是 app/
        val svg = File("../docs/icon/icon.svg").readText()
        for (d in listOf(BrandGlyph.TAG_PATH, BrandGlyph.MARQUEE_PATH, BrandGlyph.TAPE_PATH)) {
            assertThat(svg).contains("d=\"$d\"")
        }
        assertThat(svg).contains("d=\"${rr(BrandGlyph.PAPER, BrandGlyph.PAPER_RADIUS)}\"")
        assertThat(svg).contains("d=\"${rr(BrandGlyph.TITLE, BrandGlyph.LINE_RADIUS)}\"")
        assertThat(svg).contains("d=\"${BrandGlyph.LINES.joinToString("") { rr(it, BrandGlyph.LINE_RADIUS) }}\"")
        assertThat(svg).contains("stroke-width=\"${n(BrandGlyph.MARQUEE_WIDTH)}\"")
        assertThat(svg).contains(
            "rotate(${n(BrandGlyph.TAPE_ANGLE)} ${n(BrandGlyph.TAPE_PIVOT_X)} ${n(BrandGlyph.TAPE_PIVOT_Y)})",
        )
    }

    @Test
    fun `the glyph stays inside its box`() {
        val bmp = Bitmap.createBitmap(240, 160, Bitmap.Config.ARGB_8888)
        val box = RectF(80f, 40f, 160f, 120f)
        BrandGlyph.draw(Canvas(bmp), box, edge = Color.BLACK, outline = null, outlineWidth = 0f)

        var inked = 0
        for (x in 0 until bmp.width) for (y in 0 until bmp.height) {
            if (Color.alpha(bmp.getPixel(x, y)) == 0) continue
            inked++
            assertThat(x.toFloat()).isIn(Range.closed(box.left - 1, box.right))
            assertThat(y.toFloat()).isIn(Range.closed(box.top - 1, box.bottom))
        }
        // 截图占了这一格的大半
        assertThat(inked).isGreaterThan((box.width() * box.height() / 2).toInt())
    }
}
