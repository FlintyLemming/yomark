package moe.flinty.yomark.rules

/**
 * 日期的写法。日期时间（DateTimeRule）、出生日期、机读码对照共用一套。
 *
 * 两档：
 * - [STRICT]：单凭形状就敢说是日期的。「2026-09-03」「2026年9月3日」，英文的「05 FEB 1965」「Feb 5, 1965」，
 *   四位年的「02/05/1965」「5.2.1965」（日月还是月日不管，有一个能当月份就行）；
 * - [LENIENT]：前面有「Date of birth」「出生日期」这类字段名、或者要拿机读码里的生日去对的，再多认几种：
 *   两位年、空格分隔的「05 02 65」（护照上幽灵像旁边那一行），英国护照的双语月份「01 JAN /JAN 84」；
 *   证件上的中文日期：OCR 在数字里补了空格的「201 2 年4 月17日」，出生医学证明带时分的「2020年06月19日17时00分」，
 *   台湾的「民國79年1月1日」、日本的「平成2年1月1日」、毕业证的「一九九八年三月五日」，户口簿的「19900101」。
 *   这几种单独出现太容易是别的东西（编号、比分、版本号），不进日期时间。
 */
internal object Dates {

    private const val MONTH_NAMES = "January|February|March|April|May|June|July|August|September|October|November|December|" +
        "Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept|Sep|Oct|Nov|Dec"

    /**
     * 月份名，不分大小写：护照上是「FEB」，网页上是「Feb」「February」。
     * 前后不能紧挨字母（「Mark」「Decade」不是月份），挨着数字可以：机票上写「05FEB1965」。
     */
    const val MONTH = """(?<![A-Za-z])(?i:$MONTH_NAMES)(?![A-Za-z])\.?"""

    /** 「05 FEB 1965」「5 February 1965」「5th February 1965」「05-Feb-1965」「05FEB1965」 */
    private const val DAY_MONTH_YEAR = """\d{1,2}(?:st|nd|rd|th)?[ -]?$MONTH[ ,-]*\d{4}"""

    /** 「Feb 5, 1965」「February 5th, 1965」「Oct 28 2026」 */
    private const val MONTH_DAY_YEAR = """$MONTH ?\d{1,2}(?:st|nd|rd|th)?,? \d{4}"""

    /**
     * 「02/05/1965」「5.2.1965」「05-02-1965」。两个分隔符要一样，年要像年（19xx、20xx）：
     * 取件码「3-2-1234」也是这个形状。
     */
    private const val YEAR = """(?:19|20)\d{2}"""
    private const val NUMERIC_YEAR_LAST =
        """(?:\d{1,2}/\d{1,2}/$YEAR|\d{1,2}\.\d{1,2}\.$YEAR|\d{1,2}-\d{1,2}-$YEAR)"""

    /** 「2026-09-03」「2026/9/3」「2026.9.3」「2026年9月3日」 */
    const val YEAR_FIRST = """(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日)"""

    const val STRICT = """(?:$YEAR_FIRST|$DAY_MONTH_YEAR|$MONTH_DAY_YEAR|$NUMERIC_YEAR_LAST)"""

    /** 「Oct 28」：只在后面跟着时间时才算（同 DateTimeRule 的「09-29 12:30」）。 */
    const val MONTH_DAY = """$MONTH ?\d{1,2}(?:st|nd|rd|th)?"""

    /**
     * 中文日期，数字里可以夹空格：身份证上年月日之间拉得很开，PP-OCR 读成「1993 年 7 月 18 日」「201 2 年4 月17日」。
     * 「日」可以没有（读丢了）；出生医学证明后面带着「17时00分」，一起算。
     */
    private const val CN_YEAR_MONTH_DAY = """\d(?: ?\d){3} *年 *\d(?: ?\d)? *月 *\d(?: ?\d)?"""
    private const val CN_DAY = """$CN_YEAR_MONTH_DAY *日"""
    private const val CN_DIGITS = """$CN_YEAR_MONTH_DAY *(?:日(?: *\d{1,2} *[时時](?: *\d{1,2} *分)?)?)?"""

    /** 台湾的民国纪年：「民國79年1月1日」，年份加 1911。 */
    private const val ROC = """民[國国] *\d{1,3} *年 *\d{1,2} *月 *\d{1,2} *日"""

    /** 日本年号：「平成2年 1月 1日」「令和07年04月01日」「平成元年…」。 */
    private const val ERA = """(?:明治|大正|昭和|平成|令和) *(?:\d{1,2}|元) *年 *\d{1,2} *月 *\d{1,2} *日"""

    /** 毕业证、学位证上的中文数字：「一九九八年三月五日」。「〇」常被读成 O、0、○。 */
    private const val CN_NUMERALS = """[〇零○Oo0一二三四五六七八九]{4} *年 *(?:十[一二]?|[一二三四五六七八九]) *月 *""" +
        """(?:三十一?|[一二]?十[一二三四五六七八九]?|[一二三四五六七八九]) *日"""

    /** 户口簿、表格里连写的「19900101」。只在字段名后面认：单独一串八位数什么都可能是。 */
    private const val COMPACT = """(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])"""

    const val LENIENT = """(?:$ROC|$ERA|$CN_NUMERALS|$CN_DIGITS|$STRICT|""" +
        """\d{1,2}[ -]?$MONTH(?: ?/ ?$MONTH)?[ ,-]*\d{2}(?:\d{2})?|\d{1,2}[ ./-]\d{1,2}[ ./-]\d{2}(?:\d{2})?|$COMPACT)"""

    /** 带着「日」的中文日期，几种纪年都算：后面跟着「生」时它就是生日（见 [BornOn]）。 */
    const val CHINESE_DAY = """(?:$ROC|$ERA|$CN_NUMERALS|$CN_DAY)"""

    private val LENIENT_ALONE = Regex("""(?<![\d:.])$LENIENT(?![\d:])""")
    private val NUMBER = Regex("""\d+""")
    private val MONTH_WORD = Regex(MONTH)

    /** [text] 里第一个像日期的地方（宽松的一档），日、月要在范围里。 */
    fun findLenient(text: String): IntRange? =
        LENIENT_ALONE.findAll(text).firstOrNull { readings(it.value).isNotEmpty() }?.range

    fun findAllLenient(text: String): List<IntRange> =
        LENIENT_ALONE.findAll(text).filter { readings(it.value).isNotEmpty() }.map { it.range }.toList()

    /**
     * 一个日期串能读成哪几个（年, 月, 日）。数字的日月顺序看不出来，两种都给；读不通的给空。
     * 两位年原样给（65 就是 65），比对的一方自己取后两位。民国、年号换算成公历年。
     */
    fun readings(value: String): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val chinese = chinese(value)
        val compact = value.trim()
        if (chinese != null) out += chinese
        else if (compact.length == 8 && compact.all { it in '0'..'9' }) {
            out += Triple(compact.take(4).toInt(), compact.substring(4, 6).toInt(), compact.substring(6).toInt())
        } else {
            val numbers = NUMBER.findAll(value).map { it.value }.toList()
            val month = MONTH_WORD.find(value)?.value?.let { monthOf(it) }
            if (month != null) {
                if (numbers.size < 2) return emptyList()
                // 「05 FEB 1965」「Feb 5, 1965」：第一个一两位的数是日，最后一个数是年
                val day = numbers.firstOrNull { it.length <= 2 }?.toInt() ?: return emptyList()
                val year = numbers.last().toInt()
                out += Triple(year, month, day)
            } else {
                if (numbers.size != 3) return emptyList()
                val (a, b, c) = numbers
                if (a.length == 4) out += Triple(a.toInt(), b.toInt(), c.toInt())
                else {
                    out += Triple(c.toInt(), b.toInt(), a.toInt())         // 日 月 年
                    out += Triple(c.toInt(), a.toInt(), b.toInt())         // 月 日 年（美国）
                }
            }
        }
        return out.filter { (_, m, d) -> m in 1..12 && d in 1..31 }.distinct()
    }

    /** 日历上真有这一天：2 月 30 日、平年的 2 月 29 日都没有。 */
    fun isRealDate(year: Int, month: Int, day: Int): Boolean {
        if (month !in 1..12 || day < 1) return false
        val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
        val days = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day <= days[month - 1]
    }

    /**
     * 带「年」「月」的写法：按「年」「月」「日」切开读，不数数字——「201 2 年」里 OCR 补的空格先去掉，
     * 后面带的「17时00分」不算进来。纪年换算：民国 +1911，昭和 +1925，平成 +1988，令和 +2018。
     */
    private fun chinese(value: String): Triple<Int, Int, Int>? {
        val s = value.filterNot { it.isWhitespace() }
        val y = s.indexOf('年').takeIf { it > 0 } ?: return null
        val mo = s.indexOf('月', y).takeIf { it > y + 1 } ?: return null
        val end = s.indexOf('日', mo).let { if (it < 0) s.length else it }
        val era = ERA_BASE.keys.firstOrNull { s.startsWith(it) }
        val year = number(s.substring(era?.length ?: 0, y)) ?: return null
        val month = number(s.substring(y + 1, mo)) ?: return null
        val day = number(s.substring(mo + 1, end)) ?: return null
        return Triple(year + (era?.let { ERA_BASE.getValue(it) } ?: 0), month, day)
    }

    /** 阿拉伯数字，或者中文数字：逐位写的「一九九八」，带十的「十二」「二十五」「三十一」，年号的「元」。 */
    private fun number(s: String): Int? {
        if (s.isEmpty()) return null
        if (s == "元") return 1
        if ('十' in s) {
            if (s.count { it == '十' } > 1) return null
            val (tens, ones) = s.split('十')
            val t = if (tens.isEmpty()) 1 else digit(tens.singleOrNull() ?: return null) ?: return null
            val o = if (ones.isEmpty()) 0 else digit(ones.singleOrNull() ?: return null) ?: return null
            return t * 10 + o
        }
        var n = 0
        s.forEach { c -> n = n * 10 + (digit(c) ?: return null) }
        return n
    }

    private fun digit(c: Char): Int? = when (c) {
        in '0'..'9' -> c - '0'
        '〇', '零', '○', 'O', 'o' -> 0
        else -> "一二三四五六七八九".indexOf(c).takeIf { it >= 0 }?.plus(1)
    }

    private val ERA_BASE = mapOf(
        "民國" to 1911, "民国" to 1911, "明治" to 1867, "大正" to 1911, "昭和" to 1925, "平成" to 1988, "令和" to 2018,
    )

    private fun monthOf(word: String): Int? {
        val w = word.trimEnd('.').lowercase()
        return MONTH_PREFIXES.indexOfFirst { w.startsWith(it) }.takeIf { it >= 0 }?.plus(1)
    }

    private val MONTH_PREFIXES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}

/**
 * 没有字段名、后面跟着「生」的中文日期：毕业证、学位证正文里的「一九九八年三月五日生，于……」，
 * 日本证件上的「平成2年 1月 1日生」，「1998年3月5日出生」。遮日期，不遮「生」。
 *
 * 「生」后面紧跟着别的汉字的多半是另一个词：「2026年9月3日生效」「生产」「生病」不算；「生于」「生人」算。
 * 跑在 HanView 上：ML Kit 逐字切开的「自 2026 年 9 月 3 日 生 效」拼回来才看得出是「生效」。
 * 「生」在行尾的，看折下去的那一行：「本协议自2026年9月3日生」下面是「效。」，也不算。
 */
class BornOn(private val confidence: Float) : PageFinder {

    override fun findIn(text: String): List<RuleMatch> = find(text).map { (r, _) -> RuleMatch(r, confidence) }

    override fun findOnPage(page: Page): List<PageMatch> = page.texts.indices.flatMap { i ->
        find(page.texts[i])
            .filterNot { (_, atEnd) -> atEnd && wordGoesOn(page, i) }
            .map { (r, _) -> PageMatch(i, RuleMatch(r, confidence)) }
    }

    /** 一行里的日期：（原串上的区间, 后面的「生」在不在行尾）。 */
    private fun find(text: String): List<Pair<IntRange, Boolean>> {
        val view = HanView.of(text)
        return PATTERN.findAll(view.text)
            .filter { Dates.readings(it.value).isNotEmpty() }
            .map { m ->
                val rest = view.text.substring(m.range.last + 1)
                val sheng = LEADING_SHENG.find(rest)!!.range.last + 1
                view.toSource(m.range) to rest.substring(sheng).isBlank()
            }
            .toList()
    }

    /** 折下去的那一行以汉字打头（「于」「的」「人」除外）：行尾的「生」是「生效」「生产」的半截。 */
    private fun wordGoesOn(page: Page, i: Int): Boolean {
        val j = page.wrapOf(i) ?: return false
        val first = page.texts[j].trimStart().firstOrNull() ?: return false
        return first.isHan() && first !in "于於的人"
    }

    internal companion object {
        /** 日期后面的「生」「出生」；「生效」「生产」不算。 */
        const val SHENG = """ *(?:出生|生(?![\u4e00-\u9fa5])|生[于於的人])"""

        private val PATTERN = Regex("""(?<![\d:.])${Dates.CHINESE_DAY}(?=$SHENG)""")

        private val LEADING_SHENG = Regex("""^ *出?生""")
    }
}
