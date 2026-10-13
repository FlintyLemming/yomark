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

    /** 证件复印件上平铺的斜水印，加起来比证件上的字还长：证件的字横平，就是正着的。竖着的证件同理。 */
    @Test fun `a tiled slanted watermark does not tilt the page`() {
        val watermark = (0 until 5).map { line(0f, 100f + it * 100f, 600f, -35.0) }
        assertThat(ReadingFrame.of(page(0.0) + watermark).isUpright).isTrue()
        assertThat(ReadingFrame.of(page(90.0) + watermark).degrees).isEqualTo(90f)
    }

    /**
     * 竖着、倒着拍的证件上压着满页的中文水印，水印的字比证件上的多得多：按证件的方向看，不按水印的。
     * 证件正着的，照正着的看。
     */
    @Test fun `a dense watermark over a sideways card`() {
        val slanted = (0 until 6).map { line(0f, 100f + it * 100f, 1500f, 65.0) }
        assertThat(ReadingFrame.of(page(90.0) + slanted).degrees).isEqualTo(90f)
        val flipped = (0 until 6).map { line(0f, 100f + it * 100f, 1500f, -25.0) }
        assertThat(ReadingFrame.of(page(180.0) + flipped).degrees).isEqualTo(180f)
        assertThat(ReadingFrame.of(page(0.0) + flipped).isUpright).isTrue()
    }

    /** 歪着的页：只在众数附近平均，差十五度的那行不掺进来；另一组占得不少（歪了的证件压着水印），当作正着的。 */
    @Test fun `a second group on a tilted page is not blended in`() {
        assertThat(ReadingFrame.of(page(8.0) + line(0f, 500f, 200f, -7.0)).degrees.toDouble()).isWithin(0.01).of(8.0)
        assertThat(ReadingFrame.of(page(7.0) + line(0f, 500f, 500f, -18.0)).isUpright).isTrue()
    }

    /** 截图里一张竖着放、拍歪了的证件照：它最长就按它，旁边横着的界面文字占得再多也一样。 */
    @Test fun `a sideways card in an upright screenshot sets the frame`() {
        val ui = (0 until 3).map { line(0f, 900f + it * 40f, 300f, 0.0) }
        assertThat(ReadingFrame.of(page(94.0) + ui).degrees.toDouble()).isWithin(0.01).of(94.0)
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

    private fun turns(quads: List<Quad>) = ReadingFrame.of(quads).quarterTurns

    @Test fun `quarter turns snap within thirty degrees`() {
        assertThat(turns(page(0.0))).isEqualTo(0)
        assertThat(turns(page(90.0))).isEqualTo(1)
        assertThat(turns(page(94.0))).isEqualTo(1)
        assertThat(turns(page(180.0))).isEqualTo(2)
        assertThat(turns(page(-90.0))).isEqualTo(3)
        assertThat(turns(page(-179.0))).isEqualTo(2)
        assertThat(turns(page(115.0))).isEqualTo(1)
        // 斜四十五度：转直角也转不正
        assertThat(turns(page(45.0))).isEqualTo(0)
        assertThat(turns(page(125.0))).isEqualTo(0)
        assertThat(turns(emptyList())).isEqualTo(0)
    }
}
