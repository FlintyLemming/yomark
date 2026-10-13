package moe.flinty.yomark.rules

import moe.flinty.yomark.rules.validator.Checksums
import kotlin.math.abs

/**
 * 一行里的 18 位公民身份号码（GB 11643）：去掉 OCR 补的空格再认——身份证上号码的字距拉得很开，
 * 用户拍的那张读成了「码3 301 0220…」，头一位还粘在字段名上。校验码要对得上，中间八位要是日历上真有的一天。
 *
 * 临时的：集成时换成身份证号规则那边共用的认法。
 */
internal object ChineseIdNumbers {

    /** 一个号码：在原串上的 [range]，号码里的生日 [birth]（年, 月, 日）。 */
    class Found(val range: IntRange, val birth: Triple<Int, Int, Int>)

    fun findIn(text: String): List<Found> {
        val origin = ArrayList<Int>(text.length)
        val sb = StringBuilder(text.length)
        text.forEachIndexed { i, c ->
            if (c.isWhitespace()) return@forEachIndexed
            origin += i
            sb.append(if (c in X_LIKE) 'X' else c)
        }
        return SHAPE.findAll(sb).mapNotNull { m ->
            val v = m.value
            val birth = Triple(v.substring(6, 10).toInt(), v.substring(10, 12).toInt(), v.substring(12, 14).toInt())
            val real = birth.first >= 1880 && Dates.isRealDate(birth.first, birth.second, birth.third)
            if (real && Checksums.chineseIdValid(v)) Found(origin[m.range.first]..origin[m.range.last], birth) else null
        }.toList()
    }

    private val SHAPE = Regex("""(?<![0-9A-Za-z])[1-9]\d{16}[\dX](?![0-9A-Za-z])""")

    /** 校验码 X 常被读成的字。 */
    private const val X_LIKE = "x×ＸｘΧХ"
}

/**
 * 身份证号里的生日在页上别处又出现：身份证人像面的「出生」一行（字段名读坏了也不要紧）、驾驶证的出生日期、
 * 户口簿、app 里的个人资料页。号码的校验码对得上，页上读出来是同一天的就是生日，写成什么样都行：
 * 「1993 年 7 月 18 日」「1993-07-18」「1993.07.18」「19930718」。年份要四位，两位年对不出是哪个世纪。
 */
class IdBirthEcho(private val confidence: Float) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> {
        val ids = page.texts.map { ChineseIdNumbers.findIn(it) }
        val births = ids.flatten().map { it.birth }.toSet()
        if (births.isEmpty()) return emptyList()
        val out = ArrayList<PageMatch>()
        page.texts.forEachIndexed { i, text ->
            Dates.findAllLenient(text)
                .filter { r -> ids[i].none { it.range.overlaps(r) } }
                .filter { r -> Dates.readings(text.substring(r)).any { it in births } }
                .forEach { out += PageMatch(i, RuleMatch(it, confidence)) }
        }
        return out
    }
}

/**
 * 身份证人像面上字段名读坏了的姓名、住址。用户拍的那张：「名宋语桐」丢了「姓」，「性新女民 族汉」，
 * 姓名、出生一处都没遮上。字段名读对了的那几行归 LabeledField，这里只补它够不着的（同一规则里排在它后面）。
 *
 * 先认出这是一张人像面：有一行是带着号码的「公民身份号码」（六个字对上四个、次序不乱：「公民 身份号 码3…」
 * 「公民身份号玛」），或者一行里有校验码对得上的身份证号。再要人像面自己的排版：号码上面有性别、民族同在一行的那一行
 * （「性别 女 民族 汉」，读坏了的「性新女民 族汉」也算）；认不出这一行的，号码前面要有「公民身份号码」，上面要有出生那一行。
 * app 里的个人资料页也有身份证号、也有一行「性别 男」，但性别和民族不在一行，号码前面写的是「身份证号」，不算。
 * 人像面上几行的次序是死的：姓名、性别民族、出生、住址、公民身份号码。一页上拍了几张的，每张各认各的。
 * - 姓名：性别民族那一行（没认出来就用出生那一行）正上方、同一栏的一行，去掉字段名那一栏里的残字后，
 *   只剩一个名字：两到六个汉字，或带间隔点的名字，后面什么都没有（「手机号 13800138000」「年龄 36」不算）；
 * - 住址：出生和号码两行之间、左沿在值那一栏的几行，字段名那一栏里的字（「住址」，读坏了也一样）不算，
 *   要有门牌、地名用字（hasAddressPiece）。
 * 值那一栏从出生那行的头一个数字量，没有就用性别那一行的「男」「女」。字段名那一栏的残字按这一栏去掉
 * （「娃名 林知夏」的「娃名」），量不出这一栏时按字面去掉「姓名」「姓」「名」。
 */
class IdCardFront(private val what: What, private val confidence: Float) : PageFinder {

    enum class What { NAME, ADDRESS }

    override fun findOnPage(page: Page): List<PageMatch> =
        Card.all(page)
            .flatMap { card ->
                when (what) {
                    What.NAME -> listOfNotNull(card.name())
                    What.ADDRESS -> card.address()
                }
            }
            .distinct()
            .map { (line, range) -> PageMatch(line, RuleMatch(range, confidence)) }

    /** 认出来的一张人像面：号码那一行 [idRow]，性别民族、出生两行（认不出的是 null）。行号都是 Page.texts 的下标。 */
    private class Card(val page: Page, val idRow: Int, val sexRow: Int?, val birthRow: Int?) {

        private fun box(j: Int): Bounds = page.box(j)

        private val id = box(idRow)

        fun name(): Pair<Int, IntRange>? {
            val below = box(sexRow ?: birthRow ?: return null)
            val h = below.height
            val x = valueColumn()
            return page.texts.indices
                .filter { j -> j != idRow && page.texts[j].isNotBlank() }
                .filter { j ->
                    val b = box(j)
                    b.centerY < below.top && below.top - b.bottom <= NAME_GAP * h &&
                        b.left >= below.left - h && b.left <= below.left + VALUE_REACH * h
                }
                .sortedByDescending { box(it).bottom }
                .firstNotNullOfOrNull { j -> nameIn(j, x, h)?.let { j to it } }
        }

        fun address(): List<Pair<Int, IntRange>> {
            val above = box(birthRow ?: sexRow ?: return emptyList())
            val x = valueColumn() ?: return emptyList()
            val h = above.height
            return page.texts.indices.mapNotNull { j ->
                val t = page.texts[j]
                if (j == idRow || t.isBlank()) return@mapNotNull null
                val b = box(j)
                if (b.centerY <= above.bottom || b.centerY >= id.top) return@mapNotNull null
                val first = valueStart(j, x, h) ?: return@mapNotNull null
                if (abs(page.box(j, first..first).left - x) > VALUE_SLACK * h) return@mapNotNull null
                val value = first..t.indexOfLast { !it.isWhitespace() }
                value.takeIf { hasAddressWords(t.substring(value)) && hasAddressPiece(t.substring(value)) }?.let { j to it }
            }
        }

        /**
         * 第 [j] 行在值那一栏的头一个字：字段名那一栏里的字（「住址」「娃名」，读坏了也一样）跳过去，至多三个。
         * 中心在栏的左沿往左半个字高以内就算进了这一栏。字段名的末一个字压在栏边上、后面隔着空格的
         * （「佳址 江苏省…」的「址」）也跳过去。
         */
        private fun valueStart(j: Int, x: Float, h: Float): Int? {
            val t = page.texts[j]
            val chars = t.indices.filter { !t[it].isWhitespace() }
            var first = chars.firstOrNull { page.box(j, it..it).centerX >= x - 0.5f * h } ?: return null
            if (t[first] in LABEL_TAILS && t.getOrNull(first + 1)?.isWhitespace() == true) {
                first = chars.firstOrNull { it > first } ?: return null
            }
            return first.takeIf { chars.count { it < first } <= 3 }
        }

        /** 第 [j] 行去掉字段名的残字后，整行只剩一个名字。 */
        private fun nameIn(j: Int, x: Float?, h: Float): IntRange? {
            val text = page.texts[j]
            val start = if (x != null) valueStart(j, x, h) ?: return null else NAME_LABEL.find(text)?.range?.last?.plus(1) ?: 0
            val m = NAME.find(text, start)?.takeIf { m -> text.substring(start, m.range.first).isBlank() } ?: return null
            if (text.substring(m.range.last + 1).isNotBlank()) return null
            if (NOT_A_NAME.containsMatchIn(m.value.filterNot { it.isWhitespace() })) return null
            return m.range
        }

        /** 值那一栏的左沿：出生那一行的头一个数字，没有就用性别那一行的「男」「女」。 */
        private fun valueColumn(): Float? {
            birthRow?.let { j -> page.texts[j].indexOfFirst { it.isDigit() }.takeIf { it >= 0 }?.let { return page.box(j, it..it).left } }
            sexRow?.let { j -> SEX_VALUE.find(page.texts[j])?.let { return page.box(j, it.range.first..it.range.first).left } }
            return null
        }

        companion object {
            /** 页上的每一张人像面，一个号码一张。 */
            fun all(page: Page): List<Card> {
                val compact = page.texts.map { t -> t.filterNot { it.isWhitespace() } }
                val labelled = compact.indices.filter { idLabel(compact[it]) && compact[it].count(Char::isDigit) >= 15 }
                val valid = page.texts.indices.filter { ChineseIdNumbers.findIn(page.texts[it]).isNotEmpty() }
                return (labelled + valid).distinct().mapNotNull { idRow ->
                    val idBox = page.box(idRow)
                    // 号码那一行上面、一张卡的高度以内，离它最近的性别民族、出生两行
                    fun above(match: (String) -> Boolean): Int? = compact.indices
                        .filter { it != idRow && match(compact[it]) }
                        .filter { val b = page.box(it); b.centerY < idBox.top && idBox.top - b.bottom <= CARD_HEIGHT * idBox.height }
                        .maxByOrNull { page.box(it).bottom }
                    val sex = above { SEX_ROW.containsMatchIn(it) }
                    val birth = above { BIRTH_ROW.containsMatchIn(it) }
                    if (sex == null && !(idRow in labelled && birth != null)) return@mapNotNull null
                    // 次序：性别民族在出生上面
                    if (sex != null && birth != null && page.box(sex).centerY >= page.box(birth).centerY) return@mapNotNull null
                    Card(page, idRow, sex, birth)
                }
            }

            /** 「公民身份号码」六个字在行首（数字之前）对上至少四个，次序不乱。 */
            private fun idLabel(compact: String): Boolean {
                val head = compact.takeWhile { !it.isDigit() }.take(10)
                return lcs(head, ID_LABEL) >= 4
            }

            private fun lcs(a: String, b: String): Int {
                val d = Array(a.length + 1) { IntArray(b.length + 1) }
                for (i in a.indices) for (j in b.indices) {
                    d[i + 1][j + 1] = if (a[i] == b[j]) d[i][j] + 1 else maxOf(d[i][j + 1], d[i + 1][j])
                }
                return d[a.length][b.length]
            }

            private const val ID_LABEL = "公民身份号码"

            /**
             * 性别、民族同在一行（去掉空格后）：字段名的残字、「男」「女」、再是「民」或「族」。
             * 「性别女民族汉」「性新女民族汉」「别女民族」算；「性别 男」单独一行不算。
             */
            private val SEX_ROW = Regex("""[性别].{0,3}[男女].{0,4}[民族]""")
            private val BIRTH_ROW = Regex("""^(?:出生|出|生)\d""")
            private val SEX_VALUE = Regex("""[男女](?=\s*(?:民|$))""")
            private val NAME_LABEL = Regex("""^\s*(?:姓\s*名|姓|名)[：:]?""")
            private val NAME = Regex(
                """[\u4e00-\u9fa5]{1,8}(?: ?[·•・.．‧] ?[\u4e00-\u9fa5]{1,8}){1,3}|[\u4e00-\u9fa5](?: [\u4e00-\u9fa5]){1,5}(?![\u4e00-\u9fa5])|""" +
                    """[\u4e00-\u9fa5]{1,2} [\u4e00-\u9fa5]{1,2}(?![\u4e00-\u9fa5])|[\u4e00-\u9fa5]{2,6}"""
            )
            private val NOT_A_NAME = Regex("""性别|民族|出生|住址|公民|身份|号码""")

            /** 「姓名」「住址」的末一个字。 */
            private const val LABEL_TAILS = "名址"

            /** 姓名一行离性别民族那一行最多几个字高（姓名字号大，行距也大）。 */
            const val NAME_GAP = 2.5f

            /** 姓名的左沿最多在性别民族那一行左沿右边几个字高（字段名读丢了，名字从值那一栏起）。 */
            const val VALUE_REACH = 6f

            /** 住址的左沿离值那一栏最多几个字高。 */
            const val VALUE_SLACK = 1.5f

            /** 性别民族、出生两行离号码那一行最多几个字高：一张卡的高度。 */
            const val CARD_HEIGHT = 16f
        }
    }
}
