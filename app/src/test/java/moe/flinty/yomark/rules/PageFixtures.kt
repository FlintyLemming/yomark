package moe.flinty.yomark.rules

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
