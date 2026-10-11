package moe.flinty.yomark.rules

/**
 * 日期的写法。日期时间（DateTimeRule）、出生日期、机读码对照共用一套。
 *
 * 两档：
 * - [STRICT]：单凭形状就敢说是日期的。「2026-09-03」「2026年9月3日」，英文的「05 FEB 1965」「Feb 5, 1965」，
 *   四位年的「02/05/1965」「5.2.1965」（日月还是月日不管，有一个能当月份就行）；
 * - [LENIENT]：前面有「Date of birth」「出生日期」这类字段名、或者要拿机读码里的生日去对的，再多认几种：
 *   两位年、空格分隔的「05 02 65」（护照上幽灵像旁边那一行），英国护照的双语月份「01 JAN /JAN 84」。
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

    const val LENIENT = """(?:$STRICT|\d{1,2}[ -]?$MONTH(?: ?/ ?$MONTH)?[ ,-]*\d{2}(?:\d{2})?|""" +
        """\d{1,2}[ ./-]\d{1,2}[ ./-]\d{2}(?:\d{2})?|\d{4}年\d{1,2}月\d{1,2}日?)"""

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
     * 两位年原样给（65 就是 65），比对的一方自己取后两位。
     */
    fun readings(value: String): List<Triple<Int, Int, Int>> {
        val numbers = NUMBER.findAll(value).map { it.value }.toList()
        val month = MONTH_WORD.find(value)?.value?.let { monthOf(it) }
        val out = ArrayList<Triple<Int, Int, Int>>()
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
        return out.filter { (_, m, d) -> m in 1..12 && d in 1..31 }.distinct()
    }

    private fun monthOf(word: String): Int? {
        val w = word.trimEnd('.').lowercase()
        return MONTH_PREFIXES.indexOfFirst { w.startsWith(it) }.takeIf { it >= 0 }?.plus(1)
    }

    private val MONTH_PREFIXES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
}
