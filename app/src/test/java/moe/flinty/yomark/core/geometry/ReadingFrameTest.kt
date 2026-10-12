package moe.flinty.yomark.core.geometry

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/** 一页字的阅读方向：从各行 quad 的 p0→p1 估出来，规则层转到这个方向上看版面。 */
@RunWith(RobolectricTestRunner::class)
class ReadingFrameTest {

    /** 一行：从 (x, y) 起沿 [degrees] 方向读，长 [length]、高 20，角按阅读方向排（p0 左上、p1 右上）。 */
    private fun line(x: Float, y: Float, length: Float, degrees: Double): Quad {
        val r = Math.toRadians(degrees)
        val u = PointF((cos(r) * length).toFloat(), (sin(r) * length).toFloat())
        val v = PointF((-sin(r) * 20).toFloat(), (cos(r) * 20).toFloat())
        return Quad(PointF(x, y), PointF(x + u.x, y + u.y), PointF(x + u.x + v.x, y + u.y + v.y), PointF(x + v.x, y + v.y))
    }

    private fun page(degrees: Double, lines: Int = 5) = (0 until lines).map { line(100f, 100f + it * 40f, 300f - it * 30f, degrees) }

    @Test fun `an upright page is left exactly as it is`() {
        val quads = page(0.0) + line(10f, 10f, 200f, 0.4)
        val frame = ReadingFrame.of(quads)
        assertThat(frame.isUpright).isTrue()
        quads.forEach { assertThat(frame.bounds(it)).isEqualTo(it.bounds()) }
    }

    /** 竖着拍的：阅读方向朝下。转到阅读方向上，原来往下排的字变成往右，往左排的行变成往下。 */
    @Test fun `a page reading downwards turns back by a quarter`() {
        val frame = ReadingFrame.of(page(90.0))
        assertThat(frame.degrees).isEqualTo(90f)
        // 图上 (x, y) 到阅读方向上是 (y, −x)，整数坐标一点误差都没有
        assertThat(frame.bounds(Quad.fromRect(RectF(10f, 20f, 30f, 60f)))).isEqualTo(RectF(20f, -30f, 60f, -10f))
    }

    @Test fun `upside down and the other quarter`() {
        assertThat(ReadingFrame.of(page(180.0)).degrees).isEqualTo(180f)
        assertThat(ReadingFrame.of(page(-90.0)).degrees).isEqualTo(-90f)
        assertThat(ReadingFrame.of(page(180.0)).bounds(Quad.fromRect(RectF(10f, 20f, 30f, 60f))))
            .isEqualTo(RectF(-30f, -60f, -10f, -20f))
        assertThat(ReadingFrame.of(page(-90.0)).bounds(Quad.fromRect(RectF(10f, 20f, 30f, 60f))))
            .isEqualTo(RectF(-60f, 10f, -20f, 30f))
    }

    /** 拍歪了的：按实际的角度转，转过去的行是横平的。 */
    @Test fun `a tilted page turns by its own angle`() {
        val frame = ReadingFrame.of(page(94.0))
        assertThat(frame.degrees.toDouble()).isWithin(0.01).of(94.0)
        val b = frame.bounds(line(500f, 300f, 400f, 94.0))
        assertThat(b.width()).isWithin(0.01f).of(400f)
        assertThat(b.height()).isWithin(0.01f).of(20f)
    }

    /** 横排的页上夹着一两行竖排的小字：照旧是正着的。 */
    @Test fun `a few lines the other way do not tilt the page`() {
        assertThat(ReadingFrame.of(page(0.0) + line(600f, 100f, 300f, 90.0)).isUpright).isTrue()
    }

    /** 横的竖的一样多：看不出来，当作正着的。 */
    @Test fun `half across and half down stays upright`() {
        assertThat(ReadingFrame.of(page(0.0, 2) + page(90.0, 2)).isUpright).isTrue()
        assertThat(ReadingFrame.of(emptyList()).isUpright).isTrue()
    }

    /** 阅读方向上的框转回图上：和 bounds 互逆，角按阅读方向排。 */
    @Test fun `a box in the reading frame maps back onto the image`() {
        for (degrees in listOf(0.0, 90.0, 180.0, -90.0, 94.0, -3.5)) {
            val frame = ReadingFrame.of(page(degrees))
            val q = line(300f, 200f, 120f, degrees)
            frame.toImage(frame.bounds(q)).points().zip(q.points()).forEach { (a, b) ->
                assertThat(a.x).isWithin(1e-2f).of(b.x)
                assertThat(a.y).isWithin(1e-2f).of(b.y)
            }
        }
    }

    @Test fun `quarter turns snap within thirty degrees`() {
        assertThat(ReadingFrame.quarterTurns(page(0.0))).isEqualTo(0)
        assertThat(ReadingFrame.quarterTurns(page(90.0))).isEqualTo(1)
        assertThat(ReadingFrame.quarterTurns(page(94.0))).isEqualTo(1)
        assertThat(ReadingFrame.quarterTurns(page(180.0))).isEqualTo(2)
        assertThat(ReadingFrame.quarterTurns(page(-90.0))).isEqualTo(3)
        assertThat(ReadingFrame.quarterTurns(page(-179.0))).isEqualTo(2)
        assertThat(ReadingFrame.quarterTurns(page(115.0))).isEqualTo(1)
        // 斜四十五度：转直角也转不正
        assertThat(ReadingFrame.quarterTurns(page(45.0))).isEqualTo(0)
        assertThat(ReadingFrame.quarterTurns(page(125.0))).isEqualTo(0)
        assertThat(ReadingFrame.quarterTurns(emptyList())).isEqualTo(0)
    }
}
