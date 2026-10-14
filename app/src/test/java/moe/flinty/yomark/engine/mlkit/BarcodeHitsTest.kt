package moe.flinty.yomark.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.engine.mlkit.BarcodeHits.Hit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BarcodeHitsTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    /** ML Kit 17.3.0 的疑似条码 rawValue 是空串：以前按 != null 判，每个疑似条码都当成解出来了，默认打码。 */
    @Test fun `an empty raw value is not a decoded code`() {
        assertThat(BarcodeHits.decoded("")).isFalse()
        assertThat(BarcodeHits.decoded(null)).isFalse()
        assertThat(BarcodeHits.decoded("x")).isTrue()
    }

    @Test fun `the same code from the whole frame and a tile is kept once, decoded first`() {
        val fromTile = Hit(box(100f, 100f, 200f, 200f), decoded = true)
        val fromWhole = Hit(box(103f, 98f, 198f, 203f), decoded = false)
        assertThat(BarcodeHits.dedupe(listOf(fromWhole, fromTile))).containsExactly(fromTile)
    }

    /** 码跨在两块之间：一块里只框到半个，IoU 不到一半，也是同一个码。 */
    @Test fun `half a code at a tile edge goes with the decoded one`() {
        val whole = Hit(box(100f, 100f, 200f, 200f), decoded = true)
        val half = Hit(box(150f, 100f, 200f, 200f), decoded = false)
        assertThat(BarcodeHits.dedupe(listOf(half, whole))).containsExactly(whole)
    }

    /** 一大片花纹框里套着一个疑似的码：两个都留着，花纹不能顶掉码。 */
    @Test fun `a likely code inside a large pattern box is kept`() {
        val pattern = Hit(box(0f, 0f, 1000f, 600f), decoded = false)
        val code = Hit(box(100f, 100f, 200f, 200f), decoded = false)
        assertThat(BarcodeHits.dedupe(listOf(pattern, code))).containsExactly(pattern, code)
    }

    @Test fun `codes side by side are both kept`() {
        val a = Hit(box(100f, 100f, 200f, 200f), decoded = true)
        val b = Hit(box(210f, 100f, 310f, 200f), decoded = false)
        assertThat(BarcodeHits.dedupe(listOf(a, b))).containsExactly(a, b)
    }

    /**
     * 照片里 SIM 卡贴纸上的四个码（识别图坐标，真机照片上量的）：左上那个解得出，
     * 右边带 logo 的、下面糊一点的解不出。三个都挨着它、跟它一样大。
     */
    @Test fun `codes on the same sticker as a decoded one are taken`() {
        val decoded = listOf(box(388f, 752f, 476f, 801f))
        assertThat(BarcodeHits.besideDecoded(box(485f, 750f, 567f, 815f), decoded)).isTrue()     // 右
        assertThat(BarcodeHits.besideDecoded(box(354f, 842f, 440f, 890f), decoded)).isTrue()     // 下
        assertThat(BarcodeHits.besideDecoded(box(458f, 848f, 534f, 906f), decoded)).isTrue()     // 右下
    }

    /** 同一张照片里被报成疑似条码的键盘、散热片、一小块锡纸：要么大小差得远，要么离得远。 */
    @Test fun `patterns away from or unlike the decoded code are not taken`() {
        val decoded = listOf(box(411f, 657f, 532f, 735f))
        assertThat(BarcodeHits.besideDecoded(box(786f, 944f, 1760f, 1460f), decoded)).isFalse()  // 键盘
        assertThat(BarcodeHits.besideDecoded(box(1026f, 3f, 1461f, 127f), decoded)).isFalse()    // 散热片
        assertThat(BarcodeHits.besideDecoded(box(1367f, 942f, 1423f, 994f), decoded)).isFalse()  // 锡纸
        assertThat(BarcodeHits.besideDecoded(box(1000f, 657f, 1121f, 735f), decoded)).isFalse()  // 一样大，隔着四个码宽
    }

    @Test fun `nothing is taken without a decoded code`() {
        assertThat(BarcodeHits.besideDecoded(box(0f, 0f, 100f, 100f), emptyList())).isFalse()
    }

    /**
     * 照片上同一个码（原图 x 969–1134）在两块里：x 从 0 起的那块整个装下它，角点规整；x 从 1024 起的那块左边切过它，
     * ML Kit 把缺的角往块外推了 100 px（块坐标里是负的），框歪成一个尖角。
     */
    @Test fun `a code cut by a tile edge is left to the tile that holds all of it`() {
        fun pts(vararg xy: Float) = xy.toList().chunked(2).map { (x, y) -> PointF(x, y) }
        val whole = pts(969f, 1614f, 1000f, 1500f, 1134f, 1515f, 1102f, 1629f)
        assertThat(BarcodeHits.wholeInTile(whole, 2048, 2048, cutLeft = false, cutTop = false, cutRight = true, cutBottom = true)).isTrue()
        val cut = pts(-101f, 1474f, 113f, 1526f, 70f, 1653f, -29f, 1635f)
        assertThat(BarcodeHits.wholeInTile(cut, 2048, 2048, cutLeft = true, cutTop = false, cutRight = true, cutBottom = true)).isFalse()
    }

    /** 原图自己的边切到的码，没有别的块装得下，照留。 */
    @Test fun `a code cut by the edge of the photo itself is kept`() {
        val corners = listOf(PointF(-20f, 100f), PointF(80f, 100f), PointF(80f, 200f), PointF(-20f, 200f))
        assertThat(BarcodeHits.wholeInTile(corners, 2048, 2048, cutLeft = false, cutTop = false, cutRight = true, cutBottom = true)).isTrue()
    }

    @Test fun `tiles cover the edge evenly with the overlap`() {
        for (length in listOf(2049, 2400, 3000, 3072, 4000, 4096)) {
            val spans = BarcodeHits.spans(length, 2048, 512)
            assertWithMessage("$length").that(spans.first().first).isEqualTo(0)
            assertWithMessage("$length").that(spans.last().second).isEqualTo(length)
            spans.forEach { (start, end) -> assertWithMessage("$length").that(end - start).isEqualTo(2048) }
            spans.zipWithNext().forEach { (a, b) -> assertWithMessage("$length").that(a.second - b.first).isAtLeast(512) }
        }
        assertThat(BarcodeHits.spans(4096, 2048, 512)).hasSize(3)
        assertThat(BarcodeHits.spans(3072, 2048, 512)).hasSize(2)
    }

    @Test fun `an edge that fits is one tile`() {
        assertThat(BarcodeHits.spans(2048, 2048, 512)).containsExactly(0 to 2048)
        assertThat(BarcodeHits.spans(1080, 2048, 512)).containsExactly(0 to 1080)
    }

    /**
     * 截图按原图识别，识别图没缩，可长边过了 2048，ML Kit 自己会缩：也得回原图分块扫。
     * 识别图缩过的（照片）照旧分块；没缩、ML Kit 也不缩的不用。
     */
    @Test fun `tiles run whenever the whole-frame scan is not at full resolution`() {
        assertThat(BarcodeHits.needsTiles(scale = 1f, width = 1080, height = 2400)).isTrue()
        assertThat(BarcodeHits.needsTiles(scale = 1f, width = 1080, height = 5148)).isTrue()
        assertThat(BarcodeHits.needsTiles(scale = 0.5f, width = 2000, height = 1500)).isTrue()
        assertThat(BarcodeHits.needsTiles(scale = 1f, width = 1080, height = 1920)).isFalse()
        assertThat(BarcodeHits.needsTiles(scale = 1f, width = 2048, height = 1536)).isFalse()
    }

    /** 50MP、200MP 的照片先降采样，还是切 6 块上下。 */
    @Test fun `very large photos are sampled down before tiling`() {
        assertThat(BarcodeHits.sampleSize(4096)).isEqualTo(1)
        assertThat(BarcodeHits.sampleSize(4624)).isEqualTo(2)
        assertThat(BarcodeHits.sampleSize(8160)).isEqualTo(2)
        assertThat(BarcodeHits.sampleSize(16320)).isEqualTo(4)
    }
}
