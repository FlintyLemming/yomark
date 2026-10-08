package moe.flinty.yomark.engine.ppocr

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.FloatBuffer
import kotlin.math.abs

/** PP-OCR 的纯算法部分：不需要模型，不需要 Android。 */
class PpOcrPostProcessTest {

    // ---------- DB 后处理 ----------

    private fun probMap(w: Int, h: Int, fill: (x: Int, y: Int) -> Float) =
        FloatArray(w * h) { fill(it % w, it / w) }

    @Test fun `a solid block becomes one box grown by the unclip distance`() {
        // 40×10 的文字核：unclip 距离 = 400 × 1.5 / 100 = 6
        val prob = probMap(100, 40) { x, y -> if (x in 20..59 && y in 15..24) 0.9f else 0f }
        val box = DbPostProcess.boxes(prob, 100, 40).single()
        assertThat(box.minX).isWithin(0.5f).of(14f)
        assertThat(box.maxX).isWithin(0.5f).of(66f)
        assertThat(box.minY).isWithin(0.5f).of(9f)
        assertThat(box.maxY).isWithin(0.5f).of(31f)
    }

    @Test fun `a faint block is dropped by the box score`() {
        val prob = probMap(100, 40) { x, y -> if (x in 20..59 && y in 15..24) 0.4f else 0f }
        assertThat(DbPostProcess.boxes(prob, 100, 40)).isEmpty()
    }

    @Test fun `separate blocks become separate boxes in reading order corners`() {
        val prob = probMap(200, 40) { x, y -> if (y in 15..24 && (x in 10..49 || x in 120..179)) 0.9f else 0f }
        val boxes = DbPostProcess.boxes(prob, 200, 40)
        assertThat(boxes).hasSize(2)
        boxes.forEach { b ->
            assertThat(b.tl.x).isLessThan(b.tr.x)
            assertThat(b.tl.y).isLessThan(b.bl.y)
        }
    }

    @Test fun `a tilted line gets a tilted box`() {
        // 斜率 0.2 的一条带：外接矩形应当跟着倾斜，而不是一个大方框
        val prob = probMap(200, 100) { x, y -> if (x in 20..179 && abs(y - (30 + 0.2f * x)) < 4f) 0.9f else 0f }
        val box = DbPostProcess.boxes(prob, 200, 100).single()
        val slope = (box.tr.y - box.tl.y) / (box.tr.x - box.tl.x)
        assertThat(slope).isWithin(0.03f).of(0.2f)
        assertThat(box.height).isLessThan(30f)
    }

    // ---------- CTC 解码 ----------

    /** 类别：0 blank，1..3 = 甲乙丙，4 = 空格。 */
    private val dict = listOf("甲", "乙", "丙")

    private fun steps(vararg best: Int): FloatBuffer {
        val classes = dict.size + 2
        val out = FloatArray(best.size * classes)
        best.forEachIndexed { t, k -> out[t * classes + k] = 0.9f }
        return FloatBuffer.wrap(out)
    }

    @Test fun `repeats collapse and blanks separate`() {
        val d = CtcDecoder.decode(steps(0, 1, 1, 0, 1, 2, 2, 0), 8, 5, dict)
        assertThat(d.chars).containsExactly("甲", "甲", "乙").inOrder()
        assertThat(d.steps).containsExactly(1..2, 4..4, 5..6).inOrder()
        assertThat(d.confidence).isWithin(1e-6f).of(0.9f)
    }

    @Test fun `the last class is a space`() {
        val d = CtcDecoder.decode(steps(1, 4, 3), 3, 5, dict)
        assertThat(d.chars.joinToString("")).isEqualTo("甲 丙")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a dictionary that does not match the model is rejected`() {
        CtcDecoder.decode(steps(1), 1, 7, dict)
    }

    // ---------- 词界 ----------

    /** 48 行高、白底，[x0, x1) 列画黑。 */
    private fun strip(width: Int, vararg ink: IntRange) =
        FloatArray(48 * width) { i -> if (ink.any { (i % width) in it } && (i / width) in 10..37) 0f else 1f }

    @Test fun `a wide gap between words is a break`() {
        val found = WordGaps.find(strip(200, 10 until 60, 80 until 130), 200, 48)
        // 间隙 20 列，字高 28：0.71 倍，远超 0.3
        assertThat(WordGaps.isBreak("三", "8", 35f, 105f, found)).isTrue()
    }

    @Test fun `glyph spacing inside a word is not a break`() {
        val found = WordGaps.find(strip(200, 10 until 60, 64 until 114), 200, 48)
        assertThat(WordGaps.isBreak("晨", "冉", 35f, 89f, found)).isFalse()
    }

    /** 窄字形「1」两边留白宽，数字之间要更宽的间隙才算词界，「101」不能被拆成「1 01」。 */
    @Test fun `digits need a wider gap`() {
        val found = WordGaps.find(strip(200, 10 until 20, 30 until 60), 200, 48)   // 间隙 10，0.36 倍
        assertThat(WordGaps.isBreak("1", "0", 15f, 45f, found)).isFalse()
        assertThat(WordGaps.isBreak("码", "1", 15f, 45f, found)).isTrue()
    }

    // ---------- 同一行拼接 ----------

    private fun line(text: String, x0: Float, y0: Float, h: Float = 20f): OcrLine {
        val w = text.length * h
        val box = Box(Pt(x0, y0), Pt(x0 + w, y0), Pt(x0 + w, y0 + h), Pt(x0, y0 + h))
        return OcrLine(text, 0.9f, box, text.indices.map { box.slice(it / text.length.toFloat(), (it + 1) / text.length.toFloat()) })
    }

    @Test fun `segments on one row are joined with a boxless space`() {
        val joined = RowGrouper.group(listOf(line("1-58908", 100f, 0f), line("取件码", 0f, 2f))).single()
        assertThat(joined.text).isEqualTo("取件码 1-58908")
        assertThat(joined.charBoxes[3]).isNull()
        assertThat(joined.charBoxes.count { it == null }).isEqualTo(1)
    }

    @Test fun `different rows and far columns stay apart`() {
        assertThat(RowGrouper.group(listOf(line("上一行", 0f, 0f), line("下一行", 0f, 40f)))).hasSize(2)
        assertThat(RowGrouper.group(listOf(line("左栏", 0f, 0f), line("右栏", 500f, 0f)))).hasSize(2)
    }
}
