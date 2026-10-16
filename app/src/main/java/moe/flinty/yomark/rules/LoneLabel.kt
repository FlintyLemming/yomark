package moe.flinty.yomark.rules

import kotlin.math.max
import kotlin.math.min

/**
 * 字段名自己成一段、值另成一段：短信卡片、订单页上小字的「取件码」，下面一行是大字的「8-5-2001」（左对齐的、居中的都有），
 * 或者码在同一排右边，隔得老远、字号大得多。OCR 不把这样的两段拼成一行（PP-OCR 的 RowGrouper 只拼三个字高以内、字高差不到一倍的），
 * 逐行的 LabeledField 接不上；它接同一排右边那一段只看十个字高以内，还要字段名带着英文译名或者 bareRow。
 *
 * - 字段名那一段除了字段名只有标点、图标：「复制取件码」「出示取件码」是按钮，「3 条待取件」是标题，它们下面、右边是什么都不认；
 * - 值先看同一排右边最近的一段，它不像值，再看下面紧挨着的一段：中线低过字段名的下沿，上沿离它不超过一个半字高
 *   （按两个里高的算，码的字号常大得多），横向和字段名有交叠。居中的大字比字段名宽，左沿在字段名左边，
 *   StackedField 从字段名左沿起分栏，切下来的是半截；
 * - 那一段整段对得上 [value] 才遮，不像就不往别处找了：右边的「复制」、下面的下一个字段名、一句话都不是值。
 *
 * 一段：一行里字与字之间的空白不到一个字高的一串。RowGrouper 拼成一行的几段之间只补了一个空格，按框断开。
 *
 * @param labels 字段名，汉字之间可夹空格（逐字切开的「取 件 码」）。
 */
class LoneLabel(labels: List<String>, private val value: Regex, private val confidence: Float) : PageFinder {

    private val anywhere = Regex(LabeledField.alternatives(labels))

    private val lone = Regex("""[^\p{L}\p{N}]*(?:${anywhere.pattern})[^\p{L}\p{N}]*""")

    override fun findOnPage(page: Page): List<PageMatch> {
        if (page.texts.none { anywhere.containsMatchIn(it) }) return emptyList()
        val cells = page.texts.indices.map { cellsOf(page, it) }
        val out = ArrayList<PageMatch>()
        cells.forEachIndexed { i, row ->
            row.filter { lone.matches(page.texts[i].substring(it)) }.forEach { c ->
                val lb = page.box(i, c)
                if (lb.height <= 0f) return@forEach
                val (j, r) = beside(page, cells, lb)?.takeIf { isValue(page, it) }
                    ?: below(page, cells, i, lb)?.takeIf { isValue(page, it) }
                    ?: return@forEach
                out += PageMatch(j, RuleMatch(r, confidence))
            }
        }
        return out
    }

    private fun isValue(page: Page, cell: Pair<Int, IntRange>): Boolean = value.matches(page.texts[cell.first].substring(cell.second))

    /** 同一排、在字段名 [lb] 右边最近的一段：（行号, 区间）。量法同 LabeledField 接右边那一段：竖直方向重叠过半，中线在它右边。 */
    private fun beside(page: Page, cells: List<List<IntRange>>, lb: Bounds): Pair<Int, IntRange>? =
        cells.flatMapIndexed { j, row -> row.map { (j to it) to page.box(j, it) } }
            .filter { (_, b) ->
                min(b.bottom, lb.bottom) - max(b.top, lb.top) >= SAME_ROW * min(b.height, lb.height) &&
                    b.centerX > lb.right && b.left - lb.right <= ROW_REACH * lb.height
            }
            .minByOrNull { (_, b) -> b.left }?.first

    /** 第 [i] 行的字段名 [lb] 下面紧挨着、横向和它有交叠的一段。 */
    private fun below(page: Page, cells: List<List<IntRange>>, i: Int, lb: Bounds): Pair<Int, IntRange>? =
        cells.flatMapIndexed { j, row -> if (j == i) emptyList() else row.map { (j to it) to page.box(j, it) } }
            .filter { (_, b) ->
                b.centerY > lb.bottom && b.top - lb.bottom <= ROW_GAP * max(lb.height, b.height) &&
                    min(b.right, lb.right) > max(b.left, lb.left)
            }
            .minByOrNull { (_, b) -> b.top }?.first

    /** 第 [j] 行里的一段段字：字与字之间的空白宽过一个字高（按两边高的那个）就断开，同 StackedField 分栏。 */
    private fun cellsOf(page: Page, j: Int): List<IntRange> {
        val text = page.texts[j]
        val out = ArrayList<IntRange>()
        var start = -1
        var end = -1
        var right = 0f
        var height = 0f
        for (k in text.indices) {
            if (text[k].isWhitespace()) continue
            val b = page.box(j, k..k)
            if (start >= 0 && b.left - right > CELL_GAP * max(height, b.height)) {
                out += start..end
                start = -1
            }
            if (start < 0) {
                start = k
                right = b.right
                height = b.height
            } else {
                right = max(right, b.right)
                height = max(height, b.height)
            }
            end = k
        }
        if (start >= 0) out += start..end
        return out
    }

    private companion object {
        /** 一段里两个字之间最宽的空白，按字高算。 */
        const val CELL_GAP = 1f

        /** 值的上沿离字段名的下沿最多几个字高。 */
        const val ROW_GAP = 1.5f

        /** 竖直方向重叠过这么多（按矮的那个框算）就是同一排。 */
        const val SAME_ROW = 0.5f

        /** 同一排右边那一段最远隔几个字高：手机截图上一整排，字段名在左、码靠右对齐。 */
        const val ROW_REACH = 30f
    }
}
