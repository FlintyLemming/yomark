package moe.flinty.yomark.ui.batch

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 胶片条的排法：停在中间的那张展开、居中，左右对称地让开；滑到一半两张各展开一半；整条滑动没有跳变。
 * 视差那一块露出哪里见后半。
 */
class FilmstripGeometryTest {

    private val g = FilmstripGeometry(collapsedWidth = 22f, gap = 2f, margin = 6f)
    private val count = 6
    private val expanded = 40f
    private fun offset(i: Int, p: Float, widths: (Int) -> Float = { expanded }) = g.centerOffset(i, p, count, widths)

    @Test fun `the image at rest sits in the middle, opened to its own width`() {
        assertThat(offset(2, 2f)).isWithin(EPS).of(0f)
        assertThat(g.width(2, 2f, expanded)).isWithin(EPS).of(expanded)
        assertThat(g.width(3, 2f, expanded)).isWithin(EPS).of(g.collapsedWidth)
    }

    @Test fun `its neighbours step aside by the same amount on both sides`() {
        val step = g.collapsedWidth / 2 + g.gap + expanded / 2 + g.margin
        assertThat(offset(3, 2f)).isWithin(EPS).of(step)
        assertThat(offset(1, 2f)).isWithin(EPS).of(-step)
    }

    @Test fun `farther images keep the collapsed pitch`() {
        assertThat(offset(5, 2f) - offset(4, 2f)).isWithin(EPS).of(g.pitch)
        assertThat(offset(1, 2f) - offset(0, 2f)).isWithin(EPS).of(g.pitch)
    }

    @Test fun `halfway between two images each is half open and the middle falls between them`() {
        assertThat(g.expansion(2, 2.5f)).isWithin(EPS).of(0.5f)
        assertThat(g.expansion(3, 2.5f)).isWithin(EPS).of(0.5f)
        assertThat(offset(2, 2.5f)).isWithin(EPS).of(-offset(3, 2.5f))
    }

    @Test fun `sliding never jumps`() {
        val widths = { i: Int -> 30f + 7f * i }              // 各张展开后宽窄不一
        for (i in 0 until count) {
            for (whole in 1 until count) {
                val before = g.centerOffset(i, whole - 0.0001f, count, widths)
                val after = g.centerOffset(i, whole.toFloat(), count, widths)
                assertThat(after).isWithin(0.05f).of(before)
            }
        }
    }

    @Test fun `the strip runs from the first image to the last`() {
        assertThat(offset(0, 0f)).isWithin(EPS).of(0f)
        assertThat(offset(count - 1, (count - 1).toFloat())).isWithin(EPS).of(0f)
        assertThat(offset(count - 2, (count - 1).toFloat())).isLessThan(0f)
    }

    @Test fun `with no parallax a cell shows the middle of the image`() {
        // 4:3 的照片放进 40×40 的格子：铺满高，横向居中裁
        val w = FilmstripGeometry.window(400, 300, 40f, 40f, parallax = 0f, shift = 0f)
        assertThat(w.height).isWithin(EPS).of(300f)
        assertThat(w.width).isWithin(EPS).of(300f)
        assertThat(w.left).isWithin(EPS).of(50f)
    }

    @Test fun `the window slides across the image from one end of the strip to the other`() {
        val left = FilmstripGeometry.window(400, 300, 22f, 40f, parallax = 12f, shift = -1f)
        val middle = FilmstripGeometry.window(400, 300, 22f, 40f, parallax = 12f, shift = 0f)
        val right = FilmstripGeometry.window(400, 300, 22f, 40f, parallax = 12f, shift = 1f)
        assertThat(left.left).isWithin(EPS).of(0f)
        assertThat(right.right).isWithin(EPS).of(400f)
        assertThat(middle.left).isGreaterThan(left.left)
        assertThat(middle.left).isLessThan(right.left)
        assertThat(left.width).isWithin(EPS).of(right.width)       // 只挪不缩
    }

    @Test fun `a tall screenshot still has room to slide`() {
        // 9:19.5 的截图比 22 宽的窄条还窄：不加视差的话铺满宽，横向一点余地都没有
        val flat = FilmstripGeometry.window(900, 1950, 22f, 40f, parallax = 0f, shift = 1f)
        val slid = FilmstripGeometry.window(900, 1950, 22f, 40f, parallax = 12f, shift = 1f)
        assertThat(flat.left).isWithin(EPS).of(0f)
        assertThat(slid.left).isGreaterThan(0f)
        assertThat(slid.right).isWithin(0.01f).of(900f)
    }

    private companion object {
        const val EPS = 1e-3f
    }
}
