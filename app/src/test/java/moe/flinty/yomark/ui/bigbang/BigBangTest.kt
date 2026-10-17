package moe.flinty.yomark.ui.bigbang

import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 大爆炸（spec §7.3，2026-10-16 增补）：字拆开、点中哪个字、选中的字怎么连成一块码。 */
@RunWith(RobolectricTestRunner::class)
class BigBangTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    /**
     * 像 PP-OCR 那样一字一个 element（range i..i），字宽 [w]、字高 20；空格没有 element。
     * 每个 Pair 是 RowGrouper 拼之前的一段（原文、左沿），段与段之间补一个空格。
     */
    private fun ppLine(top: Float, vararg segs: Pair<String, Float>, w: Float = 20f): TextLine {
        val text = StringBuilder()
        val els = ArrayList<TextElement>()
        val segQuads = segs.mapIndexed { n, (s, left) ->
            if (n > 0) text.append(' ')
            s.forEachIndexed { i, c ->
                val at = text.length
                if (c != ' ') els += TextElement(rect(left + i * w, top, left + (i + 1) * w, top + 20f), c.toString(), at..at)
                text.append(c)
            }
            rect(left, top, left + s.length * w, top + 20f)
        }
        val whole = RectF(segQuads[0].bounds()).apply { segQuads.forEach { union(it.bounds()) } }
        return TextLine(Quad.fromRect(whole), text.toString(), 0.95f, els, segQuads)
    }

    private fun page(vararg lines: TextLine) = BigBangPage.of(lines.toList())

    private fun BigBangPage.boxes(vararg ids: Int) = runQuads(ids.toSet()).map { it.bounds() }

    @Test fun `every character becomes a glyph and spaces do not`() {
        val p = page(ppLine(100f, "张 三" to 40f))
        assertThat(p.glyphs.map { it.text }).containsExactly("张", "三").inOrder()
        assertThat(p.glyphs.map { it.id }).containsExactly(0, 1).inOrder()
        assertThat(p.glyphs[1].spaceBefore).isTrue()
        assertThat(p.glyphs[0].spaceBefore).isFalse()
    }

    @Test fun `consecutive selected characters become one box, not one per character`() {
        val p = page(ppLine(100f, "收货人张三" to 40f))
        assertThat(p.boxes(3, 4)).containsExactly(RectF(100f, 100f, 140f, 120f))
    }

    @Test fun `a whole phone number is one box`() {
        val p = page(ppLine(100f, "电话13812345678" to 0f, w = 10f))
        assertThat(p.boxes(*(2..12).toList().toIntArray())).containsExactly(RectF(20f, 100f, 130f, 120f))
    }

    @Test fun `a gap in the selection gives two boxes`() {
        val p = page(ppLine(100f, "收货人张三" to 40f))
        assertThat(p.boxes(0, 1, 3, 4))
            .containsExactly(RectF(40f, 100f, 80f, 120f), RectF(100f, 100f, 140f, 120f)).inOrder()
    }

    @Test fun `a selection across a word space stays one box`() {
        // 同一段里隔着空格的两个词：「张 三」一块
        val p = page(ppLine(100f, "张 三" to 40f))
        assertThat(p.boxes(0, 1)).containsExactly(RectF(40f, 100f, 100f, 120f))
    }

    /** 并排的两个按钮拼成了一行：中间隔着两个多字高，各打各的。 */
    @Test fun `a run does not bridge two far-apart segments joined into one row`() {
        val p = page(ppLine(100f, "赠送好友" to 10f, "查看订单" to 145f))
        assertThat(p.boxes(*(0..7).toList().toIntArray()))
            .containsExactly(RectF(10f, 100f, 90f, 120f), RectF(145f, 100f, 225f, 120f)).inOrder()
    }

    /** 检测在一个空格处把一行切成了两段，两段挨得近（不到一个字高）：选中的照样连成一块。 */
    @Test fun `close segments still join`() {
        val p = page(ppLine(100f, "取件码" to 0f, "1234" to 75f))
        assertThat(p.boxes(*(0..6).toList().toIntArray())).containsExactly(RectF(0f, 100f, 155f, 120f))
    }

    @Test fun `lines never merge into one box`() {
        val p = page(ppLine(100f, "张三" to 40f), ppLine(125f, "李四" to 40f))
        assertThat(p.boxes(0, 1, 2, 3))
            .containsExactly(RectF(40f, 100f, 80f, 120f), RectF(40f, 125f, 80f, 145f)).inOrder()
    }

    /** 斜着的一行：并成的块顺着字的方向，四个角也按阅读方向排（p0→p1 是字的走向）。 */
    @Test fun `a tilted line merges along its own direction`() {
        // 走向 45°，每个字 10√2 宽、10√2 高
        val chars = (0 until 3).map { i ->
            val x = 10f * i
            Quad(PointF(x, x), PointF(x + 10f, x + 10f), PointF(x, x + 20f), PointF(x - 10f, x + 10f))
        }
        val line = TextLine(Quad(chars[0].p0, chars[2].p1, chars[2].p2, chars[0].p3), "abc", 0.9f,
            chars.mapIndexed { i, q -> TextElement(q, "abc"[i].toString(), i..i) })
        val run = page(line).runQuads(setOf(0, 1, 2)).single()
        assertThat(run.p0.x).isWithin(1e-3f).of(0f)
        assertThat(run.p0.y).isWithin(1e-3f).of(0f)
        assertThat(run.p1.x).isWithin(1e-3f).of(30f)
        assertThat(run.p1.y).isWithin(1e-3f).of(30f)
        assertThat(run.p3.x).isWithin(1e-3f).of(-10f)
        assertThat(run.p3.y).isWithin(1e-3f).of(10f)
    }

    /** ML Kit 只给到词：一个词的框按字数等分。 */
    @Test fun `a word element is split evenly into characters`() {
        val line = TextLine(
            rect(0f, 0f, 100f, 20f), "Card 4111", 0.9f,
            listOf(TextElement(rect(0f, 0f, 40f, 20f), "Card", 0..3), TextElement(rect(60f, 0f, 100f, 20f), "4111", 5..8)),
        )
        val p = page(line)
        assertThat(p.glyphs.map { it.text }).containsExactly("C", "a", "r", "d", "4", "1", "1", "1").inOrder()
        assertThat(p.glyphs[1].quad.bounds()).isEqualTo(RectF(10f, 0f, 20f, 20f))
        assertThat(p.glyphs[4].spaceBefore).isTrue()
        // 只遮号码的中间两位
        assertThat(p.boxes(5, 6)).containsExactly(RectF(70f, 0f, 90f, 20f))
    }

    /** ML Kit 给了每个字的框（Symbol）就用它，不再按字数分：字宽不一样的词里遮中间几个字也对得上。 */
    @Test fun `per-character boxes from the recognizer win over splitting the word`() {
        val chars = listOf(rect(0f, 0f, 30f, 20f), rect(30f, 0f, 34f, 20f), rect(34f, 0f, 60f, 20f))
        val line = TextLine(rect(0f, 0f, 60f, 20f), "WiM", 0.9f, listOf(TextElement(rect(0f, 0f, 60f, 20f), "WiM", 0..2, chars)))
        val p = page(line)
        assertThat(p.glyphs.map { it.quad.bounds() }).containsExactlyElementsIn(chars.map { it.bounds() }).inOrder()
    }

    /** 没有每个字的框时按字宽分：汉字算两份。「手机号13812345678」里遮中间四位，码落在那四个数字上。 */
    @Test fun `without per-character boxes han characters count double`() {
        // 3 个汉字 × 20 + 11 个数字 × 10 = 170 宽
        val text = "手机号13812345678"
        val line = TextLine(rect(0f, 0f, 170f, 20f), text, 0.9f, listOf(TextElement(rect(0f, 0f, 170f, 20f), text, text.indices)))
        val p = page(line)
        assertThat(p.glyphs[2].quad.bounds().right).isWithin(1e-3f).of(60f)
        // 「1234」是第 6..9 个字：60 + 3 × 10 起，到 60 + 7 × 10
        val box = p.runQuads(setOf(6, 7, 8, 9)).single().bounds()
        assertThat(box.left).isWithin(1e-3f).of(90f)
        assertThat(box.right).isWithin(1e-3f).of(130f)
    }

    /** ML Kit 中文识别器把一整排拼成一行、一行只有一段：两个词隔得远照样不连，量的是字与字的距离。 */
    @Test fun `far-apart words on one ml kit line are separate boxes`() {
        val line = TextLine(
            rect(0f, 0f, 200f, 20f), "复制 分享", 0.9f,
            listOf(TextElement(rect(0f, 0f, 40f, 20f), "复制", 0..1), TextElement(rect(160f, 0f, 200f, 20f), "分享", 3..4)),
        )
        val p = page(line)
        assertThat(p.boxes(0, 1, 2, 3)).containsExactly(RectF(0f, 0f, 40f, 20f), RectF(160f, 0f, 200f, 20f)).inOrder()
    }

    /** ML Kit 的英文、中文两个识别器把同一行各读一遍：只留置信度高的那一遍。 */
    @Test fun `a line read twice is kept once`() {
        val latin = TextLine(rect(0f, 0f, 100f, 20f), "AE", 0.4f, listOf(TextElement(rect(0f, 0f, 100f, 20f), "AE", 0..1)))
        val han = TextLine(rect(2f, 1f, 100f, 21f), "收货", 0.9f, listOf(TextElement(rect(2f, 1f, 100f, 21f), "收货", 0..1)))
        val other = TextLine(rect(0f, 40f, 100f, 60f), "张三", 0.9f, listOf(TextElement(rect(0f, 40f, 100f, 60f), "张三", 0..1)))
        val p = page(latin, other, han)
        assertThat(p.glyphs.map { it.text }).containsExactly("张", "三", "收", "货").inOrder()
    }

    @Test fun `glyphsAlong can skip the point the last segment already looked at`() {
        val p = page(ppLine(100f, "一二三四五" to 0f))
        assertThat(p.glyphsAlong(PointF(15f, 110f), PointF(25f, 110f), 0f, 100f, withStart = false).map { it.id }).containsExactly(1)
    }

    /** PP-OCR 给 BMP 以外的字两个 element（一个 UTF-16 单元一个），并成一个字。 */
    @Test fun `a supplementary character is one glyph`() {
        val han = String(Character.toChars(0x20BB7))          // 𠮷
        val text = "a${han}b"
        val els = listOf(
            TextElement(rect(0f, 0f, 10f, 20f), "a", 0..0),
            TextElement(rect(10f, 0f, 30f, 20f), text[1].toString(), 1..1),
            TextElement(rect(10f, 0f, 30f, 20f), text[2].toString(), 2..2),
            TextElement(rect(30f, 0f, 40f, 20f), "b", 3..3),
        )
        val p = page(TextLine(rect(0f, 0f, 40f, 20f), text, 0.9f, els))
        assertThat(p.glyphs.map { it.text }).containsExactly("a", han, "b").inOrder()
        assertThat(p.glyphs[1].quad.bounds()).isEqualTo(RectF(10f, 0f, 30f, 20f))
    }

    @Test fun `lines without characters are left out and ids stay contiguous`() {
        val blank = TextLine(rect(0f, 0f, 10f, 10f), " ", 0.9f, emptyList())
        val p = page(ppLine(100f, "张三" to 40f), blank, ppLine(160f, "李四" to 40f))
        assertThat(p.lines).hasSize(2)
        assertThat(p.glyphs.map { it.id }).containsExactly(0, 1, 2, 3).inOrder()
        assertThat(p.glyphs.map { it.line }).containsExactly(0, 0, 1, 1).inOrder()
        // 两行之间空出了两个字高：另起一段
        assertThat(p.lines[1].paragraphBefore).isTrue()
    }

    @Test fun `glyphAt finds the character under the finger or the nearest within reach`() {
        val p = page(ppLine(100f, "收货人" to 40f))
        assertThat(p.glyphAt(PointF(65f, 110f), 0f)?.text).isEqualTo("货")
        assertThat(p.glyphAt(PointF(65f, 124f), 6f)?.text).isEqualTo("货")
        assertThat(p.glyphAt(PointF(65f, 140f), 6f)).isNull()
    }

    @Test fun `glyphsAlong does not skip characters on a fast swipe`() {
        val p = page(ppLine(100f, "一二三四五" to 0f))
        assertThat(p.glyphsAlong(PointF(5f, 110f), PointF(95f, 110f), 0f, 5f).map { it.id })
            .containsExactly(0, 1, 2, 3, 4).inOrder()
    }

    @Test fun `covered glyphs are the ones under a redacting mask only`() {
        val p = page(ppLine(100f, "收货人张三" to 40f))
        val solid = MaskItem("a", rect(98f, 98f, 142f, 122f), SensitiveKind.PERSON_NAME, DetectorSource.RULE)
        val marker = MaskItem("b", rect(38f, 98f, 82f, 122f), SensitiveKind.MANUAL, DetectorSource.MANUAL, look = MaskLook(MaskStyle.MARKER))
        assertThat(p.covered(listOf(solid, marker))).containsExactly(3, 4)
    }

    @Test fun `a sweep selects or deselects what it passes`() {
        assertThat(BigBangSelection.sweep(setOf(1), listOf(2, 3), select = true)).containsExactly(1, 2, 3)
        assertThat(BigBangSelection.sweep(setOf(1, 2, 3), listOf(2, 3), select = false)).containsExactly(1)
        assertThat(BigBangSelection.toggle(setOf(1), 1)).isEmpty()
        assertThat(BigBangSelection.toggle(emptySet(), 4)).containsExactly(4)
    }
}
