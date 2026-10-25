package moe.flinty.yomark.engine.ppocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 字母文字的框下沿挪到下伸的笔画下面（Descenders）：在画出来的像素上量，不需要模型。
 * 一行字是一排竖笔（y 30..56），检测的框是 y 20..62（高 42），下沿离字底 6 像素；下伸的笔画是从字里往下伸的一根竖笔。
 */
class DescendersTest {

    /** 白底上画几块深色的矩形（[l], [t]）..（[r], [b]），像素格子 [x, x+1)。 */
    private class Page(override val width: Int, override val height: Int, val dark: List<FloatArray>) : Pixels {
        override fun sample(x: Float, y: Float): Color {
            val px = (x + 0.5f).toInt()
            val py = (y + 0.5f).toInt()
            val inked = dark.any { (l, t, r, b) -> px >= l && px < r && py >= t && py < b }
            return if (inked) Color(30f, 30f, 30f) else Color(255f, 255f, 255f)
        }
    }

    private fun rect(l: Int, t: Int, r: Int, b: Int) = floatArrayOf(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())

    /** 一行字：竖笔每 12 像素一根、4 像素宽。 */
    private val body = (20 until 380 step 12).map { rect(it, 30, it + 4, 56) }

    private val detected = Box(Pt(10f, 20f), Pt(390f, 20f), Pt(390f, 62f), Pt(10f, 62f))

    /** 同 PpOcrEngine.read 切图识别的灰度图：框里 48 排。 */
    private fun gray(image: Pixels, box: Box): FloatArray {
        val w = 400
        return FloatArray(48 * w) { i ->
            val p = box.at((i % w + 0.5f) / w, (i / w + 0.5f) / 48)
            image.sample(p.x, p.y).luma / 255f
        }
    }

    private fun cover(image: Pixels, box: Box = detected, text: String = "pygmy") = Descenders.cover(image, box, text, gray(image, box))

    @Test fun `a descender crossing the lower edge is covered with a margin`() {
        val page = Page(400, 120, body + rect(100, 30, 106, 70))
        val box = cover(page)
        val bottom = 70 + Descenders.MARGIN * 42
        assertThat(box.tl).isEqualTo(detected.tl)
        assertThat(box.tr).isEqualTo(detected.tr)
        assertThat(box.bl.x).isWithin(1e-3f).of(10f)
        assertThat(box.bl.y).isWithin(1e-3f).of(bottom)
        assertThat(box.br.y).isWithin(1e-3f).of(bottom)
    }

    /** g j y 的尾巴：竖笔伸出框以后往旁边拐，拐出去老远再往下弯的那一截更低。 */
    @Test fun `a tail that turns sideways below the box is followed to its end`() {
        val page = Page(400, 120, body + rect(100, 30, 106, 66) + rect(70, 66, 106, 68) + rect(70, 68, 74, 72))
        assertThat(cover(page).bl.y).isWithin(1e-3f).of(72 + Descenders.MARGIN * 42)
    }

    /** 框的下沿贴着字底（没有下伸的笔画）：也留出边来。 */
    @Test fun `a box that ends at the baseline gets the margin too`() {
        val page = Page(400, 120, body.map { (l, _, r, _) -> floatArrayOf(l, 30f, r, 61f) })
        assertThat(cover(page).bl.y).isWithin(1e-3f).of(61 + Descenders.MARGIN * 42)
    }

    @Test fun `glyphs well inside the box leave it as it is`() {
        val page = Page(400, 120, body + rect(100, 30, 106, 57))
        assertThat(cover(page)).isSameInstanceAs(detected)
    }

    @Test fun `square scripts keep the detected box`() {
        val page = Page(400, 120, body + rect(100, 30, 106, 70))
        assertThat(cover(page, text = "长江路88号，302室")).isSameInstanceAs(detected)
        assertThat(cover(page, text = "13800138000")).isSameInstanceAs(detected)
        assertThat(Descenders.alphabetic("取件码 5-1-0307")).isFalse()
        assertThat(Descenders.alphabetic("wangxm@example.com")).isTrue()
        assertThat(Descenders.alphabetic("Москва")).isTrue()
    }

    /** 下一行的字顶离这一行的框只有两个像素，和下伸的笔画并排但不挨着：连不上，不算。 */
    @Test fun `the line below is not taken for a descender`() {
        val page = Page(400, 160, body + rect(100, 30, 106, 66) + rect(120, 64, 300, 100))
        assertThat(cover(page).bl.y).isWithin(1e-3f).of(66 + Descenders.MARGIN * 42)
    }

    /** 字压在一块深色上，墨一直连到出框半个字高以外：不是下伸的笔画，不挪。 */
    @Test fun `ink that runs on past the reach is left alone`() {
        val page = Page(400, 160, body + rect(100, 30, 106, 70) + rect(60, 70, 200, 120))
        assertThat(cover(page)).isSameInstanceAs(detected)
    }

    /** 转了 90° 的页（竖着读的一行）：沿框自己的下沿挪，转回去和正着的一样。 */
    @Test fun `a turned line grows along its own lower edge`() {
        val page = Page(400, 120, body + rect(100, 30, 106, 70))
        val upright = cover(page)
        val canvas = Turned(page, 90f)
        val turned = Box(canvas.fromSource(detected.tl), canvas.fromSource(detected.tr), canvas.fromSource(detected.br), canvas.fromSource(detected.bl))
        val back = cover(canvas, turned).corners().map(canvas::toSource)
        back.zip(upright.corners()).forEach { (a, b) ->
            assertThat(a.x).isWithin(1e-3f).of(b.x)
            assertThat(a.y).isWithin(1e-3f).of(b.y)
        }
    }
}
