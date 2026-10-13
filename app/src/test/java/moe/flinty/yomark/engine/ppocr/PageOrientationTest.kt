package moe.flinty.yomark.engine.ppocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** 版面方向的纯算法部分：从检测框估走向（TextAxis），转过的图（Turned）。不需要模型。 */
class PageOrientationTest {

    // ---------- 文字行的走向 ----------

    /** 中心 (cx, cy)、长 [length]、高 [height] 的框，沿 [degrees] 度方向（顺时针为正）。角按 DB 后处理的口径排。 */
    private fun box(cx: Float, cy: Float, length: Float, height: Float, degrees: Double): Box {
        val r = Math.toRadians(degrees)
        val u = Pt(cos(r).toFloat(), sin(r).toFloat()) * (length / 2)
        val v = Pt(-sin(r).toFloat(), cos(r).toFloat()) * (height / 2)
        val c = Pt(cx, cy)
        return DbPostProcess.order(listOf(c - u - v, c + u - v, c + u + v, c - u + v))
    }

    private fun page(degrees: Double, lines: Int = 6) = (0 until lines).map { box(500f, 100f + it * 60f, 400f - it * 20f, 30f, degrees) }

    @Test fun `horizontal lines are level`() {
        assertThat(TextAxis.of(page(0.0))).isEqualTo(0f)
    }

    /** 竖着的行，不论往上读还是往下读，都记作 90。 */
    @Test fun `vertical lines are ninety degrees either way`() {
        assertThat(TextAxis.of(page(90.0))).isEqualTo(90f)
        assertThat(TextAxis.of(page(-90.0))).isEqualTo(90f)
        assertThat(TextAxis.of(page(89.0))).isEqualTo(90f)
    }

    /** 竖着拍歪了 4°：转 90° + 4° 的卡片，行的轴在 −86°。 */
    @Test fun `a tilted vertical page keeps its exact angle`() {
        assertThat(TextAxis.of(page(94.0))!!.toDouble()).isWithin(0.1).of(-86.0)
        assertThat(TextAxis.of(page(-3.0))!!.toDouble()).isWithin(0.1).of(-3.0)
    }

    /** 离横平不到 2° 的当作横平：照常读，一个像素都不转。 */
    @Test fun `a slight slant snaps to level`() {
        assertThat(TextAxis.of(page(1.5))).isEqualTo(0f)
    }

    /** 单个的字是方的，看不出方向。 */
    @Test fun `glyph boxes alone say nothing`() {
        assertThat(TextAxis.of((0 until 6).map { box(100f + it * 50f, 100f, 30f, 30f, 0.0) })).isNull()
        assertThat(TextAxis.of(emptyList())).isNull()
    }

    /** 横的竖的一样多：竖着的那组转过来读，横着的在原图上照常读（PpOcrEngine）。 */
    @Test fun `half across and half down turns the vertical half`() {
        assertThat(TextAxis.of(page(0.0, 3) + page(90.0, 3))).isEqualTo(90f)
    }

    /**
     * 聊天截图里一张转了九十度的证件照：聊天的字比证件上的多，竖着的那组照样转过来读。
     * 零星一两行竖着的字（侧边栏的标签）不转。
     */
    @Test fun `a sideways card in a long chat is turned`() {
        val chat = (0 until 12).map { box(400f, 100f + it * 60f, 600f, 30f, 0.0) }
        assertThat(TextAxis.of(chat + page(90.0, 4))).isEqualTo(90f)
        assertThat(TextAxis.of(chat + page(90.0, 2))).isEqualTo(0f)
    }

    /** 斜着的行也是各个方向都有：看不出来，照常读。 */
    @Test fun `slanted lines every which way are undecided`() {
        assertThat(TextAxis.of(page(20.0, 3) + page(-40.0, 3))).isNull()
    }

    /** 护照样张上斜压的水印「SPECIMEN」：框很长，但它不该把整页拉歪。 */
    @Test fun `a long slanted watermark does not tilt the page`() {
        assertThat(TextAxis.of(page(0.0) + box(500f, 300f, 600f, 80f, -22.0))).isEqualTo(0f)
    }

    /** 证件复印件上平铺的「仅供办理银行开户使用」：比证件上的字还长，整页仍是横平的。 */
    @Test fun `a tiled watermark longer than the text does not tilt the page`() {
        val watermark = (0 until 6).map { box(500f, 50f + it * 100f, 900f, 40f, -35.0) }
        assertThat(TextAxis.of(page(0.0) + watermark)).isEqualTo(0f)
        val shallow = (0 until 6).map { box(500f, 50f + it * 100f, 900f, 40f, -15.0) }
        assertThat(TextAxis.of(page(0.0) + shallow)).isEqualTo(0f)
        assertThat(TextAxis.of(page(90.0) + watermark)).isEqualTo(90f)
    }

    /**
     * 竖着、倒着拍的证件上压着满页的中文水印，水印的字比证件上的多得多：转到证件的方向，不转到水印的方向（以前转到了水印的
     * 方向，证件上的字一个都没读出来）。证件横着的（正着、倒着）照常读，倒没倒着由 PpOcrEngine 再试。
     */
    @Test fun `a dense watermark over a sideways card`() {
        val slanted = (0 until 6).map { box(500f, 50f + it * 100f, 1500f, 40f, 65.0) }
        assertThat(TextAxis.of(page(90.0) + slanted)).isEqualTo(90f)
        val level = (0 until 6).map { box(500f, 50f + it * 100f, 1500f, 40f, -25.0) }
        assertThat(TextAxis.of(page(0.0) + level)).isEqualTo(0f)
    }

    /** 歪着拍的页上差十几度的一行长字：只在众数附近平均，不会混出一个谁都不是的角度。 */
    @Test fun `a line a dozen degrees off is not averaged in`() {
        assertThat(TextAxis.of(page(8.0) + box(500f, 300f, 900f, 40f, -5.0))!!.toDouble()).isWithin(0.1).of(8.0)
    }

    /** 歪了 7° 的证件上压着 −18° 的水印：转到哪边都有一组是歪的，照常读。竖着的页照转，另一组在原图上读。 */
    @Test fun `a tilted page with a strong second group is read as it is`() {
        assertThat(TextAxis.of(page(7.0) + box(500f, 300f, 900f, 40f, -18.0))).isEqualTo(0f)
        assertThat(TextAxis.of(page(94.0) + box(500f, 300f, 900f, 40f, 60.0))!!.toDouble()).isWithin(0.1).of(-86.0)
    }

    /** 截图里一张竖着放的证件照：竖着的那组最长就转，旁边横着的界面文字再多也一样（它们在原图上照常读）。 */
    @Test fun `a sideways card in an upright screenshot is turned`() {
        val ui = (0 until 3).map { box(200f, 900f + it * 50f, 500f, 30f, 0.0) }
        assertThat(TextAxis.of(page(94.0) + ui)!!.toDouble()).isWithin(0.1).of(-86.0)
        assertThat(TextAxis.of(page(90.0) + ui)).isEqualTo(90f)
    }

    @Test fun `the sample is the longest lines along the axis`() {
        val lines = page(90.0, 8)
        val sample = TextAxis.sample(lines + box(100f, 100f, 900f, 30f, 0.0), 90f)
        assertThat(sample).containsExactlyElementsIn(0 until TextAxis.SAMPLE).inOrder()
    }

    /** 分组读：顺着走向的在画布上读，垂直的、斜着的在原图上读，方的看边的方向。两个字的词（一倍半长）也看得出走向。 */
    @Test fun `a box runs along the axis, across it, slanted, or cannot tell`() {
        assertThat(TextAxis.fit(box(100f, 100f, 400f, 30f, 88.0), 90f)).isEqualTo(TextAxis.Fit.ALONG)
        assertThat(TextAxis.fit(box(100f, 100f, 400f, 30f, -86.0), 90f)).isEqualTo(TextAxis.Fit.ALONG)
        assertThat(TextAxis.fit(box(100f, 100f, 400f, 30f, 0.0), 90f)).isEqualTo(TextAxis.Fit.CROSS)
        assertThat(TextAxis.fit(box(100f, 100f, 60f, 40f, 0.0), 90f)).isEqualTo(TextAxis.Fit.CROSS)
        assertThat(TextAxis.fit(box(100f, 100f, 400f, 30f, -35.0), 90f)).isEqualTo(TextAxis.Fit.OTHER)
        assertThat(TextAxis.fit(box(100f, 100f, 40f, 36f, 0.0), 90f)).isEqualTo(TextAxis.Fit.SQUARE)
        assertThat(TextAxis.fit(box(100f, 100f, 40f, 36f, 6.0), -17f)).isEqualTo(TextAxis.Fit.OTHER)
    }

    // ---------- 转过的图 ----------

    /** 4×3 的图，每个像素的颜色编码它自己的坐标：r = x，g = y。 */
    private val grid = Argb(IntArray(12) { (it % 4 shl 16) or (it / 4 shl 8) }, 4, 3)

    private fun Color.at() = r.toInt() to g.toInt()

    /** 整数倍的直角：画布上的每个像素正好是原图的一个像素，不插值。 */
    @Test fun `quarter turns sample exact source pixels`() {
        val cw = Turned(grid, 90f)
        assertThat(cw.width to cw.height).isEqualTo(3 to 4)
        // 顺时针转 90°：画布左上角是原图左下角，画布右上角是原图左上角
        assertThat(cw.sample(0f, 0f).at()).isEqualTo(0 to 2)
        assertThat(cw.sample(2f, 0f).at()).isEqualTo(0 to 0)
        assertThat(cw.sample(2f, 3f).at()).isEqualTo(3 to 0)

        val half = Turned(grid, 180f)
        assertThat(half.sample(0f, 0f).at()).isEqualTo(3 to 2)
        assertThat(half.sample(3f, 2f).at()).isEqualTo(0 to 0)

        val ccw = Turned(grid, -90f)
        assertThat(ccw.width to ccw.height).isEqualTo(3 to 4)
        assertThat(ccw.sample(0f, 0f).at()).isEqualTo(3 to 0)
        assertThat(ccw.sample(0f, 3f).at()).isEqualTo(0 to 0)
        assertThat(Turned(grid, 270f).sample(0f, 3f).at()).isEqualTo(0 to 0)
    }

    @Test fun `no turn is the source itself`() {
        val same = Turned(grid, 0f)
        for (y in 0 until 3) for (x in 0 until 4) assertThat(same.sample(x.toFloat(), y.toFloat()).at()).isEqualTo(x to y)
        assertThat(same.toSource(Pt(1.25f, 2.5f))).isEqualTo(Pt(1.25f, 2.5f))
    }

    /** 原图的四个角在画布上落在画布的四个角上，一点误差都没有。 */
    @Test fun `quarter turns map corners exactly`() {
        val image = Argb(IntArray(1000 * 630), 1000, 630)
        val cw = Turned(image, 90f)
        assertThat(cw.fromSource(Pt(0f, 0f))).isEqualTo(Pt(630f, 0f))
        assertThat(cw.fromSource(Pt(0f, 630f))).isEqualTo(Pt(0f, 0f))
        assertThat(cw.fromSource(Pt(1000f, 630f))).isEqualTo(Pt(0f, 1000f))
        assertThat(Turned(image, 180f).fromSource(Pt(0f, 0f))).isEqualTo(Pt(1000f, 630f))
        assertThat(Turned(image, -90f).fromSource(Pt(0f, 0f))).isEqualTo(Pt(0f, 1000f))
    }

    @Test fun `points go there and back`() {
        val image = Argb(IntArray(900 * 1200), 900, 1200)
        val points = listOf(Pt(0f, 0f), Pt(900f, 1200f), Pt(123.5f, 777.25f), Pt(450f, 600f))
        for (degrees in listOf(0f, 90f, 180f, 270f, -90f, 4f, -93.5f, 137f)) {
            val view = Turned(image, degrees)
            points.forEach { p ->
                val back = view.toSource(view.fromSource(p))
                assertThat(back.x).isWithin(1e-2f).of(p.x)
                assertThat(back.y).isWithin(1e-2f).of(p.y)
            }
        }
    }

    /** 转任意角度时画布是转后的外接矩形：原图的四个角都落在画布里，原图中心落在画布中心。 */
    @Test fun `an arbitrary turn fits the whole image on the canvas`() {
        val image = Argb(IntArray(900 * 1200), 900, 1200)
        val view = Turned(image, -93.5f)
        listOf(Pt(0f, 0f), Pt(900f, 0f), Pt(900f, 1200f), Pt(0f, 1200f)).map(view::fromSource).forEach { p ->
            assertThat(p.x).isIn(com.google.common.collect.Range.closed(-0.01f, view.width + 0.01f))
            assertThat(p.y).isIn(com.google.common.collect.Range.closed(-0.01f, view.height + 0.01f))
        }
        val c = view.fromSource(Pt(450f, 600f))
        assertThat(c.x).isWithin(1e-3f).of(view.width / 2f)
        assertThat(c.y).isWithin(1e-3f).of(view.height / 2f)
    }

    /** 转的方向：顺时针转 90° 之后，原图上往下的一行字在画布上往右。 */
    @Test fun `a line reading down reads right after a clockwise turn back`() {
        val image = Argb(IntArray(630 * 1000), 630, 1000)
        val view = Turned(image, -90f)
        val a = view.fromSource(Pt(550f, 66f))
        val b = view.fromSource(Pt(550f, 293f))
        assertThat(b.x - a.x).isWithin(1e-3f).of(227f)
        assertThat(b.y - a.y).isWithin(1e-3f).of(0f)
    }
}
