package moe.flinty.yomark.rules

import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine

/**
 * 测试用的一页：每行一段或几段字，每个字等宽。几段拼成一行时中间是一个没有框的空格，同 PP-OCR 的 RowGrouper。
 *
 * @param x 第一个字的左沿。
 * @param y 上沿。
 * @param glued 几段直接连在一起、中间不补空格：OCR 把字段名和值读成一串（「名宋语桐」「出生201 2 年」），
 *   每个字的框却还在原处。
 */
internal class Row(val y: Float, val h: Float, vararg val segments: Segment, val glued: Boolean = false) {
    constructor(text: String, x: Float, y: Float, h: Float = 20f) : this(y, h, Segment(text, x, x + text.length * h * 0.6f))

    val text: String get() = segments.joinToString(if (glued) "" else " ") { it.text }
}

/** 一段字，[left, right) 之间均匀排开。 */
internal class Segment(val text: String, val left: Float, val right: Float)

/** 这一行每个字的框，与 [Row.text] 逐字对齐；空格（段里的、段与段之间补的）是 null。 */
internal fun Row.boxes(): List<Bounds?> = segments.flatMapIndexed { n, seg ->
    val w = (seg.right - seg.left) / seg.text.length
    val chars = seg.text.indices.map { i ->
        if (seg.text[i] == ' ') null else Bounds(seg.left + i * w, y, seg.left + (i + 1) * w, y + h)
    }
    if (n == 0 || glued) chars else listOf(null) + chars
}

internal fun page(vararg rows: Row): Page {
    val boxes = rows.map { it.boxes() }
    return Page(rows.map { it.text }) { line, range ->
        val hit = range.mapNotNull { boxes[line].getOrNull(it) }
        if (hit.isEmpty()) {
            val all = boxes[line].filterNotNull()
            Bounds(all.minOf { it.left }, rows[line].y, all.maxOf { it.right }, rows[line].y + rows[line].h)
        } else Bounds(hit.minOf { it.left }, hit.minOf { it.top }, hit.maxOf { it.right }, hit.maxOf { it.bottom })
    }
}

internal fun Page.text(m: PageMatch): String = texts[m.line].substring(m.match.range)

internal fun PageFinder.texts(page: Page): List<String> = findOnPage(page).map { page.text(it) }

// ---------- 给 RuleClassifier 的 TextLine（要 Robolectric：Quad 用的是 android.graphics） ----------

/**
 * 一行一个 TextLine，像 PP-OCR 那样每个字一个 element：字宽 20、字高 20、行距 40，从 (40, 100) 起；空格没有 element。
 * 认出的框盖住了哪几个字用 [textIn] 量。
 */
internal fun textLines(vararg texts: String): List<TextLine> = texts.mapIndexed { row, text ->
    val top = 100f + row * 40f
    val els = text.indices.filter { text[it] != ' ' }.map { i ->
        TextElement(Quad.fromRect(RectF(40f + i * 20f, top, 60f + i * 20f, top + 20f)), text[i].toString(), i..i)
    }
    TextLine(Quad.fromRect(RectF(40f, top, 40f + text.length * 20f, top + 20f)), text, 0.95f, els)
}

/** 候选框在 [textLines] 排出的那几行上盖住的字。 */
internal fun Candidate.textIn(lines: List<TextLine>): String {
    val b = quad.bounds()
    val line = lines.single { it.quad.bounds().top == b.top }
    val first = ((b.left - 40f) / 20f).toInt()
    val last = ((b.right - 40f) / 20f).toInt() - 1
    return line.text.substring(first, last + 1)
}

/**
 * 这几行（[Row]）转成 TextLine：每个字一个 element，一排里分开的几段仍是一行（同 PP-OCR 的 RowGrouper）。
 * [at] 把一个点挪到图上（转过来的页），框的四个角仍按阅读方向排；默认原样。
 */
internal fun List<Row>.textLines(at: (Float, Float) -> PointF = { x, y -> PointF(x, y) }): List<TextLine> {
    fun quad(b: Bounds) = Quad(at(b.left, b.top), at(b.right, b.top), at(b.right, b.bottom), at(b.left, b.bottom))
    return map { row ->
        val boxes = row.boxes()
        val els = boxes.mapIndexedNotNull { i, b -> b?.let { TextElement(quad(it), row.text[i].toString(), i..i) } }
        val all = boxes.filterNotNull()
        TextLine(quad(Bounds(all.minOf { it.left }, row.y, all.maxOf { it.right }, row.y + row.h)), row.text, 0.95f, els)
    }
}
