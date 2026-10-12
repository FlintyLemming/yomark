package moe.flinty.yomark.rules

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 字段名后面的值折到了下面几行：身份证、驾驶证、行驶证、户口簿上的住址一行写不下，下一行不带字段名，
 * 和第一行的值左对齐（「住 址 浙江省杭州市西湖区文三路」下面是「88号6幢2单元501 室」）。逐行的 LabeledField 只认第一行，
 * 第二行以前只能靠 AddressShape 认出「6幢2单元501」那半截。
 *
 * 从 [field] 认出的值往下接（字段名可以另成一段，见 LabeledField.findOnPage），接上的整行遮：
 * - 左沿对着值的左沿，差不到一个字高。和字段名左对齐的是下一个字段（「公民身份号码」）或者一段话，不接；
 * - 离上一行不到一个字高，字高相差不过四成；
 * - 不是下一个字段：不以字段名打头（[startsWithLabel]）；同一排上字段名那一栏里没有字——表格里字段名和值
 *   常常各成一行，「入住时间」右边那个「10月12日」也对着值的左沿；
 * - 不是只有日期、号码的一行。
 * 最多接 [maxLines] 行。
 */
class FollowingLines(
    private val field: Finder,
    private val startsWithLabel: (String) -> Boolean,
    private val confidence: Float,
    private val maxLines: Int = 3,
) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> {
        val out = ArrayList<PageMatch>()
        val lines = page.texts.indices.map { Line.of(page, it) }
        val found = if (field is PageFinder) field.findOnPage(page)
        else page.texts.indices.flatMap { i -> field.findIn(page.texts[i]).map { PageMatch(i, it) } }
        found.forEach { (i, m) ->
            val text = page.texts[i]
            val x0 = page.box(i, m.range.first..m.range.first).left
            var above = page.box(i, m.range)
            val h = above.height
            if (h <= 0f) return@forEach
            // 字段名那一栏的左沿：这一行值前面那一截的头一个字（字段名另成一行时，当它在左边几个字高以内）
            val labelLeft = text.indexOfFirst { !it.isWhitespace() }.takeIf { it < m.range.first }
                ?.let { page.box(i, it..it).left } ?: (x0 - LABEL_REACH * h)
            repeat(maxLines) {
                val next = lines.filterNotNull()
                    .filter { it.index != i && it.continues(above, x0, h) }
                    .minByOrNull { it.box.top } ?: return@forEach
                val t = page.texts[next.index]
                if (startsWithLabel(t) || !hasAddressWords(t) || besideALabel(lines, next, labelLeft, x0, h)) return@forEach
                out += PageMatch(next.index, RuleMatch(next.range, confidence))
                above = next.box
            }
        }
        return out
    }

    /** 一行：第一个到最后一个非空白字，它们的框，头一个字的左沿。 */
    private class Line(val index: Int, val range: IntRange, val box: Bounds, val left: Float) {

        /** 接在 [above] 下面、左沿对着 [x0]、字高和 [h] 差不多。 */
        fun continues(above: Bounds, x0: Float, h: Float): Boolean =
            abs(left - x0) <= ALIGN * h &&
                box.centerY > above.centerY + 0.5f * above.height &&
                box.top - above.bottom <= GAP * h &&
                abs(box.height - h) <= SIZE * h

        companion object {
            fun of(page: Page, j: Int): Line? {
                val t = page.texts[j]
                val s = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
                val e = t.indexOfLast { !it.isWhitespace() }
                val box = page.box(j, s..e)
                return Line(j, s..e, box, page.box(j, s..s).left).takeIf { box.height > 0f }
            }
        }
    }

    /** 同一排上、值那一栏左边还有一段字：那是下一个字段的字段名，这一行是它的值。 */
    private fun besideALabel(lines: List<Line?>, row: Line, labelLeft: Float, x0: Float, h: Float): Boolean =
        lines.filterNotNull().any { o ->
            o.index != row.index && o.box.right <= x0 - 0.2f * h && o.box.right >= labelLeft - h &&
                min(o.box.bottom, row.box.bottom) - max(o.box.top, row.box.top) >= 0.5f * min(o.box.height, row.box.height)
        }

    private companion object {
        /** 左沿差多少个字高还算对齐。 */
        const val ALIGN = 1f

        /** 和上一行之间的空隙最多几个字高。 */
        const val GAP = 1f

        /** 字高最多差几成。 */
        const val SIZE = 0.4f

        /** 值在行首（字段名不在这一行）时，当字段名那一栏在左边几个字高以内。 */
        const val LABEL_REACH = 6f
    }
}

/**
 * 像住址的一行：去掉日期之后还有字（汉字、字母），也不是一长串数字带一个字。只有日期、电话、证件号的一行不是住址；
 * 「128号」「501室」这样短的下半截是。整行括在括号里的是界面上的标签（收货地址下面的「(默认)」）。
 */
internal fun hasAddressWords(text: String): Boolean {
    if (BRACKETED.matches(text.trim())) return false
    var rest = text
    Dates.findAllLenient(text).sortedByDescending { it.first }.forEach { rest = rest.removeRange(it) }
    val letters = rest.count { it.isLetter() }
    return letters >= 1 && !(letters == 1 && rest.count { it.isDigit() } >= 7)
}

private val BRACKETED = Regex("""[(（\[【].*[)）\]】]""")
