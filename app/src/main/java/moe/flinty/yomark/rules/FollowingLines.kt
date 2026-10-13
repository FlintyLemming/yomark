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
 * - 是住址的一截（[hasAddressPiece]）：订单、地图页上对着值的左沿的常是按钮和备注（「修改」「导航」
 *   「预计10月14日送达」「张三 138****1234」），不是住址；
 * - 不是只有日期、号码的一行。
 * 最多接 [maxLines] 行。
 *
 * 按这一页的方向量；截图里一张拍歪了、转过的证件照（字少，RuleClassifier 不单分一组），值那一行、下面那几行有自己的方向的，
 * 也按它们的方向各量一遍（Page.boxIn），哪个方向接得最多取哪个：歪 8° 的驾驶证上，「住址 Address 江苏省…」拼成一排的框是横平的、
 * 字是斜的，按整页量，下面一行的中线不够低，接不上。按别的方向量时，值和接上的行都要顺着这个方向（[fits]）：截图上正着的两行
 * 按照片的方向量，框一样斜着撑高，字高一高、「对齐」的余量跟着变大，会把住址下面的「王小明 86-139****5678」接上。
 */
class FollowingLines(
    private val field: Finder,
    private val startsWithLabel: (String) -> Boolean,
    private val confidence: Float,
    private val maxLines: Int = 3,
) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> {
        val found = if (field is PageFinder) field.findOnPage(page)
        else page.texts.indices.flatMap { i -> field.findIn(page.texts[i]).map { PageMatch(i, it) } }
        // 每个方向的各行算一次
        val linesIn = HashMap<Int?, List<Line?>>()
        val linesOf = { ref: Int? -> linesIn.getOrPut(ref) { page.texts.indices.map { Line.of(page, it, ref) } } }
        return found.flatMap { (i, m) ->
            refsOf(page, i, m.range).map { ref -> follow(page, i, m.range, ref, linesOf(ref), linesOf(null)) }
                .maxByOrNull { it.size }.orEmpty()
        }
    }

    /**
     * 量第 [i] 行的值 [range] 往下接按哪几个方向（null 是这一页的，排第一，接得一样多时取它）：值那一行自己的，
     * 下面一带（左沿差不多对着值、[maxLines] 行以内）转过的行自己的。这一页上没有转过的行就只有这一页的。
     */
    private fun refsOf(page: Page, i: Int, range: IntRange): List<Int?> {
        val value = page.box(i, range)
        val h = value.height
        if (h <= 0f) return IN_PAGE
        val below = page.texts.indices.filter { j ->
            j != i && page.isTurned(j) && page.texts[j].isNotBlank() && page.box(j).let { b ->
                b.top >= value.top && b.top - value.bottom <= REACH * maxLines * h && b.right >= value.left - LABEL_REACH * h && b.left <= value.right
            }
        }
        return IN_PAGE + listOfNotNull(i.takeIf { page.isTurned(it) }) + below
    }

    /**
     * 按第 [ref] 行的方向（见 [refsOf]）从第 [i] 行的值 [range] 往下接；[lines] 是按这个方向量的各行，[inPage] 是按这一页量的。
     */
    private fun follow(page: Page, i: Int, range: IntRange, ref: Int?, lines: List<Line?>, inPage: List<Line?>): List<PageMatch> {
        val out = ArrayList<PageMatch>()
        val box = { j: Int, r: IntRange -> if (ref == null) page.box(j, r) else page.boxIn(j, r, ref) }
        val text = page.texts[i]
        val x0 = box(i, range.first..range.first).left
        // 值前面连着读错了的英文译名（「住址 Aaaress 江苏省…」，译名没认出来，算进了值）：下面一行对着它后面的汉字
        val han = MISREAD_TRANSLATION.matchAt(text, range.first)?.let { it.range.last + 1 }?.takeIf { it <= range.last }
        val xs = listOf(x0) + listOfNotNull(han?.let { box(i, it..it).left })
        var above = box(i, range)
        val h = above.height
        if (h <= 0f || ref != null && !fits(above, page.box(i, range))) return out
        // 字段名那一栏的左沿：这一行值前面那一截的头一个字（字段名另成一行时，当它在左边几个字高以内）
        val labelLeft = text.indexOfFirst { !it.isWhitespace() }.takeIf { it < range.first }
            ?.let { box(i, it..it).left } ?: (x0 - LABEL_REACH * h)
        repeat(maxLines) {
            val next = lines.filterNotNull()
                .filter { it.index != i && xs.any { x -> it.continues(above, x, h) } }
                .filter { ref == null || inPage[it.index]?.let { p -> fits(it.box, p.box) } == true }
                .minByOrNull { it.box.top } ?: return out
            val t = page.texts[next.index]
            if (startsWithLabel(t) || !hasAddressWords(t) || !hasAddressPiece(t) || besideALabel(lines, next, labelLeft, x0, h)) {
                return out
            }
            out += PageMatch(next.index, RuleMatch(next.range, confidence))
            above = next.box
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
            /** 第 [j] 行，按第 [ref] 行的方向量（null 是这一页的）。 */
            fun of(page: Page, j: Int, ref: Int?): Line? {
                val t = page.texts[j]
                val s = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
                val e = t.indexOfLast { !it.isWhitespace() }
                val box = { r: IntRange -> if (ref == null) page.box(j, r) else page.boxIn(j, r, ref) }
                val all = box(s..e)
                return Line(j, s..e, all, box(s..s).left).takeIf { all.height > 0f }
            }
        }
    }

    /**
     * 一段字顺着量出 [turned] 的那个方向：按它量出来的框不比按这一页量的（[inPage]）高。斜着的字按自己的方向量框最矮，
     * 正着的字按斜的方向量，框跟着斜撑高。
     */
    private fun fits(turned: Bounds, inPage: Bounds): Boolean = turned.height <= FIT * inPage.height

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

        /** 找转过的行（[refsOf]）时，每接一行往下看几个字高。 */
        const val REACH = 3f

        /** 按别的方向量的框最多比按这一页量的高这么多（[fits]），留一点给框的误差。 */
        const val FIT = 1.02f

        /** 只按这一页的方向量。 */
        val IN_PAGE: List<Int?> = listOf(null)

        /** 值打头的一个英文词，后面隔着空格是汉字：读错了的译名（「Aaaress」「Adsess」）。 */
        val MISREAD_TRANSLATION = Regex("[A-Za-z]{3,} +(?=[\\u4e00-\\u9fa5])")
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

/**
 * 住址的一截：有门牌、楼栋、地名用字（「88号6幢2单元501 室」「民族大道136号」「车辆段宿舍3号楼」「128号」）。
 * 「10号线」「3号口」的「号」不算；带着「距」「步行」「预计」「送达」的是到那儿的路、送货的时间，
 * 带着「包邮」「次日达」「自提」的是商家的配送说明（收货地址下面的「杭州市内次日达」「门店自提点：张江路店」），不是地址。
 */
internal fun hasAddressPiece(text: String): Boolean = ADDRESS_PIECE.containsMatchIn(text) && !DIRECTIONS.containsMatchIn(text)

private val ADDRESS_PIECE =
    Regex("""号(?![线口门站])|[省市区县镇乡村路街巷弄楼栋幢室院苑园庄屯组座寓舍厦队坊里湾]|大道|胡同|单元|小区|新村|花园|公寓|家园|大厦|社区|广场""")

private val DIRECTIONS = Regex("""距|步行|驾车|公里|分钟|预计|送达|包邮|次日达|当日达|隔日达|发货|自提|配送|运费""")

/**
 * 通行证上名字下面一行的拼音：往来港澳通行证、往来台湾通行证、港澳台居民居住证上「姓名 张三」，
 * 下面一行和名字左对齐的是「ZHANG,SAN」。从 [field] 认出的汉字名往下看紧挨着的一行，整行是 [ROMANISED_NAME]、
 * 每个词都拼得成音节（[SYLLABLES]）才遮：「OK」「Thanks a lot」、下一个字段都不算，
 * 名片、订单上名字下面的「SALES MANAGER」「VIP MEMBER」也不算。
 */
class PinyinBelow(private val field: PageFinder, private val confidence: Float) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> =
        field.findOnPage(page).mapNotNull { (i, m) ->
            if (page.texts[i].substring(m.range).none { it.isHan() }) return@mapNotNull null
            val name = page.box(i, m.range)
            val h = name.height
            if (h <= 0f) return@mapNotNull null
            val (j, r) = page.texts.indices.filter { it != i }
                .mapNotNull { j ->
                    val t = page.texts[j]
                    val s = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return@mapNotNull null
                    val r = s..t.indexOfLast { !it.isWhitespace() }
                    val b = page.box(j, r)
                    val below = b.top >= name.centerY && b.top - name.bottom <= h
                    if (below && abs(page.box(j, s..s).left - name.left) <= ALIGN * h) j to r else null
                }
                .minByOrNull { (j, r) -> page.box(j, r).top } ?: return@mapNotNull null
            val line = page.texts[j].substring(r)
            PageMatch(j, RuleMatch(r, confidence))
                .takeIf { ROMANISED_NAME.matches(line) && WORD.findAll(line).all { SYLLABLES.matches(it.value) } }
        }

    private companion object {
        /** 拼音的左沿离名字的左沿最多几个字高。 */
        const val ALIGN = 1.5f

        val WORD = Regex("""[A-Za-z]+""")

        /**
         * 一个词拼得成一串音节：声母（可无）、一到三个元音、收尾的鼻音或塞音。拼音之外也收港台的拼法
         * （「CHEUNG」「KWOK」「TSANG」「HSU」）和吕、女的「LV」「LYU」「NV」。「SALES」「MANAGER」「VIP」「MEMBER」拼不成。
         */
        val SYLLABLES = Regex(
            """(?i)(?:(?:zh|ch|sh|ts|tz|hs|kw|gw|ng|[bpmfdtnlgkhjqxrzcsyw])?[aeiou]{1,3}(?:ng|n|m|k|t|p|h)?|[ln](?:v|yu)(?:e|an|n)?|er)+"""
        )
    }
}

/**
 * 证件上的拼音、罗马字名：全大写，「姓, 名」带逗号（「ZHANG,SAN」「CHEUNG, TAI MAN」「GARCIA LOPEZ, MARIA」），
 * 或者两到四个词（「OUYANG NA NA」）。
 */
internal val ROMANISED_NAME = Regex(
    """[A-Z][A-Z'\-]*(?: [A-Z][A-Z'\-]*){0,2} ?, ?[A-Z][A-Z'\-]*(?: [A-Z][A-Z'\-]*){0,2}|[A-Z][A-Z'\-]*(?: [A-Z][A-Z'\-]*){1,3}"""
)
