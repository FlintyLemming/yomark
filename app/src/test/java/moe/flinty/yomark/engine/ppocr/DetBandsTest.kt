package moe.flinty.yomark.engine.ppocr

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** 竖长图分条检测的几何部分：哪种图分、怎么切、各条的框怎么合。不需要模型。 */
class DetBandsTest {

    private val limit = PpOcrEngine.DET_LIMIT

    private fun box(l: Float, t: Float, r: Float, b: Float) = Box(Pt(l, t), Pt(r, t), Pt(r, b), Pt(l, b))

    @Test fun `screenshots at full size are split, photos and short pages are not`() {
        assertThat(DetBands.wanted(1080, 2400, limit)).isTrue()
        assertThat(DetBands.wanted(1080, 5148, limit)).isTrue()
        assertThat(DetBands.wanted(1440, 3200, limit)).isTrue()
        // 只缩两成的照旧整张：1080×1920 的截图、竖拍的照片
        assertThat(DetBands.wanted(1080, 1920, limit)).isFalse()
        assertThat(DetBands.wanted(1500, 2000, limit)).isFalse()
        // 宽过 1536 的切了会把横着的长行竖着切开
        assertThat(DetBands.wanted(2000, 6000, limit)).isFalse()
        // 横着的
        assertThat(DetBands.wanted(5148, 1080, limit)).isFalse()
        assertThat(DetBands.wanted(540, 1170, limit)).isFalse()
    }

    @Test fun `bands cover the page evenly with the overlap`() {
        for (height in listOf(2049, 2400, 3200, 5148, 8192)) {
            val spans = DetBands.spans(height, 1536)
            assertWithMessage("$height").that(spans.first().first).isEqualTo(0)
            assertWithMessage("$height").that(spans.last().second).isEqualTo(height)
            spans.forEach { (top, bottom) -> assertWithMessage("$height").that(bottom - top).isEqualTo(1536) }
            spans.zipWithNext().forEach { (a, b) -> assertWithMessage("$height").that(a.second - b.first).isAtLeast(DetBands.OVERLAP) }
        }
        assertThat(DetBands.spans(2400, 1536)).hasSize(2)
        assertThat(DetBands.spans(5148, 1536)).hasSize(4)
        assertThat(DetBands.spans(1500, 1536)).containsExactly(0 to 1500)
    }

    /** 两条：[0, 1536) 和 [864, 2400)，重叠 672。 */
    private val bands = DetBands.spans(2400, 1536)

    @Test fun `a line in the overlap is kept once`() {
        val line = box(100f, 1000f, 600f, 1040f)
        val merged = DetBands.merge(listOf(bands[0] to listOf(line), bands[1] to listOf(box(101f, 1001f, 601f, 1041f))), 2400)
        assertThat(merged).containsExactly(line)
    }

    /** 切缝上的半截丢掉，用另一条里整的那一行。 */
    @Test fun `half a line at a seam gives way to the whole line from the other band`() {
        val half = box(100f, 1520f, 600f, 1538f)                // 第一条的下沿 1536
        val whole = box(100f, 1520f, 600f, 1560f)
        val merged = DetBands.merge(listOf(bands[0] to listOf(half), bands[1] to listOf(whole)), 2400)
        assertThat(merged).containsExactly(whole)
    }

    /** 比重叠还长的竖行（转了 90° 的证件照）：两条里都只有半截，拼成一个框，不丢。 */
    @Test fun `a vertical line longer than the overlap is joined from both halves`() {
        val upper = box(300f, 500f, 340f, 1540f)                // 碰到第一条的下沿
        val lower = box(301f, 862f, 341f, 1700f)                // 碰到第二条的上沿 864
        val merged = DetBands.merge(listOf(bands[0] to listOf(upper), bands[1] to listOf(lower)), 2400).single()
        assertThat(merged.minX).isEqualTo(300f)
        assertThat(merged.maxX).isEqualTo(341f)
        assertThat(merged.minY).isEqualTo(500f)
        assertThat(merged.maxY).isEqualTo(1700f)
    }

    /** 两行挨得很近、各自整个落在一条里：都留着，不当成同一行。 */
    @Test fun `neighbouring lines in one band are both kept`() {
        val a = box(100f, 100f, 600f, 140f)
        val b = box(100f, 120f, 600f, 160f)                     // 跟 a 压着一半：同一条里的不去重
        assertThat(DetBands.merge(listOf(bands[0] to listOf(a, b), bands[1] to emptyList()), 2400)).containsExactly(a, b)
    }

    /** 图本身的上下沿不是切缝：贴着顶上的状态栏照留。 */
    @Test fun `the page's own top and bottom edges are not seams`() {
        val top = box(10f, 0f, 200f, 30f)
        val bottom = box(10f, 2380f, 200f, 2402f)
        assertThat(DetBands.merge(listOf(bands[0] to listOf(top), bands[1] to listOf(bottom)), 2400)).containsExactly(top, bottom)
    }
}
