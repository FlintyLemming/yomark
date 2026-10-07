package com.yomark.app.rules

import kotlin.math.max

/**
 * 没有字段名的地址：靠中文地址自己的形状认。
 *
 * 菜鸟的快递详情页上，收件地址「祁门路33号四方新村23幢605室」前面只写着「送至」；
 * 淘宝的收货卡片干脆什么都不写；驿站地址更是夹在一句话中间
 * （「您的快件已暂存至合肥四方新村10栋101室店菜鸟驿站」）——标签锚定一个都接不住。
 * 但中文地址的**门牌层级**本身就是强特征：路名加门牌号、楼栋单元、房号……
 * 「数字 + 门牌量词」在别的文本里很少连着出现两个。
 *
 * 认法：
 * 1. 在行里找门牌词。强的是路名门牌（祁门路 33 号）、楼栋单元（23 幢、3 单元）、
 *    房号（605 室）；弱的是楼层（5 楼）、小区后缀（新村、花园）、行政区后缀（合肥市、蜀山区）；
 * 2. 相邻门牌词之间只隔着地名用字，就连成一串；
 * 3. 一串里有路名门牌，或者有其他强词且不止一个门牌词，才算地址；
 * 4. 往左补上地名（「祁门」路、「合肥四方」新村），碰到「的、至、在」这类虚词就停；
 *    往右补上单元后面没带量词的房号（「2单元501」的 501）。
 *
 * 认不出「3室2厅」（房源户型）、「2号线3号口」（地铁）、「10月1号」（日期）——
 * 前者被「厅」排除，后两者根本没有门牌量词。
 *
 * 跑在 HanView 上：逐字切分的「祁 门 路 33 号」先拼回「祁门路33号」。
 */
class AddressShape(private val confidence: Float) : Finder {

    private class Token(val range: IntRange, val strong: Boolean, val road: Boolean)

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        val t = view.text
        return clusters(t, tokens(t))
            .filter { c -> c.any { it.road } || (c.size >= 2 && c.any { it.strong }) }
            .map { c ->
                val start = extendLeft(t, c.first().range.first)
                val end = extendRight(t, c.maxOf { it.range.last })
                RuleMatch(view.toSource(start..end), confidence)
            }
    }

    private fun tokens(t: String): List<Token> =
        (ROAD.findAll(t).map { Token(it.range, strong = true, road = true) } +
            UNIT.findAll(t).map { Token(it.range, strong = true, road = false) } +
            HINT.findAll(t).map { Token(it.range, strong = false, road = false) })
            .sortedBy { it.range.first }
            .toList()

    /** 相邻门牌词之间只隔着几个地名用字，就归进同一串。 */
    private fun clusters(t: String, tokens: List<Token>): List<List<Token>> {
        val out = ArrayList<MutableList<Token>>()
        var end = -1
        tokens.forEach { tok ->
            val gap = if (tok.range.first > end) t.substring(end + 1, tok.range.first) else ""
            if (out.isNotEmpty() && gap.length <= MAX_GAP && gap.all { it.isPlaceChar() }) {
                out.last() += tok
                end = max(end, tok.range.last)
            } else {
                out += mutableListOf(tok)
                end = tok.range.last
            }
        }
        return out
    }

    /** 门牌词左边的地名：「祁门」路、「合肥四方」新村。只收汉字，碰到虚词就停。 */
    private fun extendLeft(t: String, from: Int): Int {
        var s = from
        while (s > 0 && from - s < MAX_PREFIX && t[s - 1].isHan() && t[s - 1] !in STOPS) s--
        return s
    }

    /** 单元、楼层后面没带量词的房号：「2单元501」的 501。 */
    private fun extendRight(t: String, last: Int): Int =
        TRAILING_ROOM.matchAt(t, last + 1)?.range?.last ?: last

    private fun Char.isPlaceChar(): Boolean =
        (isHan() && this !in STOPS) || isDigit() || this in 'A'..'Z' || this in 'a'..'z' || this in " -－#"

    private companion object {
        const val MAX_GAP = 10
        const val MAX_PREFIX = 10

        /** 地名里几乎不出现、句子里却总挡在地址前面的字：「暂存至」「位于」「住在」「地址」。 */
        const val STOPS = "的了是在已请您你我他她它这那把被给让送寄存放至到往于址与及或去从"

        /** 号后面跟这些字就不是门牌：2号线、5号楼（归 UNIT）、3号口、1号门、2号站。 */
        const val NOT_HOUSE_NO = "(?![线楼院口出入门站])"

        /** 路名门牌（含村组门牌「张家村3组15号」）。单独一个就够算地址：「建国路88号」不会是别的东西。 */
        val ROAD = Regex(
            """(?<=[\u4e00-\u9fff])(?:路|街|大道|巷|弄|胡同|村)\s?\d+(?:[-－]\d+)?\s?号$NOT_HOUSE_NO""" +
                """|(?<=[\u4e00-\u9fff])\d+\s?组\s?\d+\s?号$NOT_HOUSE_NO"""
        )

        /** 楼栋、单元、房号。房号排除「3室2厅」——那是房源户型，不是门牌。 */
        val UNIT = Regex(
            """(?:\d+|[A-Za-z])(?:[-－]\d+)?\s?(?:号楼|号院|栋|幢|座|单元)""" +
                """|\d+(?:[-－]\d+)?\s?(?:室|户)(?!\s?\d*\s?厅)"""
        )

        /**
         * 弱门牌词：只凭它们不算地址，但能把一串连起来、把左边界推到省市区。
         * 行政区后缀要求前面至少两个汉字，并排除「市场」「区域」「县长」这类常用词。
         */
        val HINT = Regex(
            """\d+\s?(?:层|楼)(?![梯盘道])""" +
                """|小区|新村|花园|公寓|家园|大厦|社区|广场|苑""" +
                """|(?<=[\u4e00-\u9fff]{2})(?:省|市|区|县|镇|乡)(?![场长民级内外域别委政间分块值])"""
        )

        val TRAILING_ROOM = Regex("""\s?\d[0-9A-Za-z]{0,5}(?![0-9A-Za-z])""")
    }
}
