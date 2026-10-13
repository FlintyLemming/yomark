package moe.flinty.yomark.rules

import kotlin.math.max
import kotlin.math.min

/**
 * 字段名在上一行、值在下一行：护照、身份证的资料页（「Surname/Nom/Apellidos」下面是「TRAVELER」），
 * 表单、订单页上单独占一行的「Shipping Address」「Full name」。逐行的 LabeledField 接不住，值不在同一行。
 *
 * 拿 OCR 的框找值：
 * - 在字段名下面：中线低过字段名的下半截，上沿离它不超过一个半字高。证件照上两行常常上下咬住一截，允许重叠；
 * - 在它那一栏：护照上一排有好几个字段名（「Type」「Code」「Passport No.」），值各在各的下面。取下面那一行里
 *   从字段名左边一点起、到同一排下一个字段名为止的那一段，遇到大的空白断开；从左边连过来的一整行字不算；
 * - 长得像这一类的值（[value]）：名字没有数字，护照号是字母加数字，生日要能读成日期；
 * - 下面那一行本身就是字段名的不是值（这一栏空着，或者 OCR 漏了它的值）。
 *
 * 字段名不分大小写、不看空格：照片上「Given names」常读成「Given na me s」。
 *
 * 调研时的原型（tools/model-eval/ocr-dump 的 EnProto.pageFinds）只认单独一行的「Shipping Address」「Full name」，
 * 70 页英文基准上多认出 6 个人名、18 处地址；护照这种一排几个字段名、字段名后面带法文西文的，是这一版加的。
 *
 * @param labels 在一行里找字段名，见 [document]、[header]。
 * @param value 下面那一段（[maxLines] > 1 时是每一行的那一段）里要遮的部分；不像这一类的值给 null。
 * @param sameLine 字段名同一行、在它后面已经有值了，就不再往下找：驾照上「DOB 01/07/1973」下面一行是有效期。
 */
class StackedField(
    private val labels: (String) -> List<IntRange>,
    private val value: (String) -> IntRange?,
    private val confidence: Float,
    private val maxLines: Int = 1,
    private val sameLine: Boolean = false,
) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> {
        val out = ArrayList<PageMatch>()
        val isLabel by lazy { page.texts.map(::isLabelLine) }
        page.texts.forEachIndexed { i, text ->
            labels(text).forEach { label ->
                if (sameLine && value(text.substring(label.last + 1)) != null) return@forEach
                val lb = page.box(i, label)
                val h = lb.height
                if (h <= 0f) return@forEach
                // 这一栏到同一排下一个字段名为止
                val stop = documentLabels(text).filter { it.first > label.last }
                    .minOfOrNull { page.box(i, it).left } ?: Float.POSITIVE_INFINITY
                var above = lb
                var firstGap = -1f
                repeat(maxLines) {
                    val (j, cell) = cellBelow(page, i, above, lb, stop) ?: return@forEach
                    if (isLabel[j]) return@forEach
                    val box = page.box(j, cell)
                    val gap = max(0f, box.top - above.bottom)
                    // 一块地址的行距是均匀的；比前面的大出一截，就是下一样东西了
                    if (firstGap >= 0f && gap > max(firstGap * 1.5f, 0.3f * h)) return@forEach
                    if (firstGap < 0f) firstGap = gap
                    val r = value(page.texts[j].substring(cell)) ?: return@forEach
                    out += PageMatch(j, RuleMatch((cell.first + r.first)..(cell.first + r.last), confidence))
                    above = box
                }
            }
        }
        return out
    }

    /** [above] 下面紧挨着的、[label] 那一栏里的一段：（行号, 区间）。 */
    private fun cellBelow(page: Page, labelLine: Int, above: Bounds, label: Bounds, stop: Float): Pair<Int, IntRange>? {
        val h = label.height
        var best: Pair<Int, IntRange>? = null
        var bestTop = Float.POSITIVE_INFINITY
        for (j in page.texts.indices) {
            if (j == labelLine) continue
            val cell = cellIn(page, j, label.left - COLUMN_SLACK * h, stop, h) ?: continue
            val box = page.box(j, cell)
            if (box.height > WATERMARK * h) continue
            if (box.centerY <= above.centerY + 0.5f * above.height) continue
            if (box.top - above.bottom > ROW_GAP * max(h, box.height)) continue
            if (box.left > min(label.right, label.left + 6 * h) + h) continue
            if (box.top < bestTop) {
                best = j to cell
                bestTop = box.top
            }
        }
        return best
    }

    /**
     * 第 [j] 行里落在 [left, right) 这一栏的第一段字：中心在栏里的字，遇到宽过一个字高的空白就断开。
     * 栏左边紧挨着就有字的不算——那是从左边连过来的一整行（一段话、另一栏的长值），不是这一栏的值。
     */
    private fun cellIn(page: Page, j: Int, left: Float, right: Float, h: Float): IntRange? {
        val text = page.texts[j]
        var start = -1
        var end = -1
        var lastRight = Float.NaN
        for (k in text.indices) {
            if (text[k].isWhitespace()) continue
            val b = page.box(j, k..k)
            val near = !lastRight.isNaN() && b.left - lastRight <= CELL_GAP * max(h, b.height)
            when {
                b.centerX < left -> Unit
                b.centerX >= right -> break
                start < 0 -> {
                    if (near) return null
                    start = k
                    end = k
                }
                near -> end = k
                else -> break
            }
            lastRight = if (lastRight.isNaN()) b.right else max(lastRight, b.right)
        }
        return if (start < 0) null else start..end
    }

    companion object {
        /** 栏的左沿比字段名的左沿再往左放多少个字高：值有时比字段名靠左一点。 */
        private const val COLUMN_SLACK = 2f

        /** 一段值里两个字之间最宽的空白，按字高算。 */
        private const val CELL_GAP = 1f

        /** 值的上沿离上面一行的下沿最多几个字高。 */
        private const val ROW_GAP = 1.5f

        /** 比字段名高出这么多倍的不是值，是压在版面上的水印（护照样张上斜着的「SPECIMEN」）。 */
        private const val WATERMARK = 3f

        /**
         * 证件上的字段名：英文的那一份，护照上它在最前或斜杠之间（「Surname/Nom/Apellidos」「Name/Surname/Nom (1)」
         * 「姓/Surname」）。在一行里任何地方都认，前后不能紧挨字母。
         *
         * 中国护照、永居证上是「中文/English」（「出生日期/Date of birth」「护照号/Passport No.」）：前面的中文算进字段名，
         * 这一栏从中文的左沿起——值和中文左对齐，只从英文那里量，值的头一个字就落在栏外了。
         */
        fun document(words: List<String>): (String) -> List<IntRange> {
            val pattern = keys(words)
            return { text -> compactFind(text, pattern).map { withChinese(text, it) } }
        }

        /** 表单上单独占一行的字段名：整行就是它（可带冒号）。 */
        fun header(line: Regex): (String) -> List<IntRange> = { text ->
            if (line.matches(text)) listOfNotNull(text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 }
                ?.let { it..text.indexOfLast { c -> !c.isWhitespace() } })
            else emptyList()
        }

        /** 人名的字段名。 */
        val NAME_LABELS = listOf(
            "surname", "surnames", "last name", "family name", "given name", "given names", "first name", "first names",
            "forename", "forenames", "middle name", "middle names", "full name", "legal name", "name of holder", "holder's name",
        )

        /**
         * 中国护照、永居证的姓名栏：「姓名/Name」下面两行，汉字名、拼音「ZHANG, SAN」。
         * 只认连着中文的写法：单独一个「Name」在表单上什么都可能领着。斜杠常被读成 1、l、I、竖线，或者读丢了
         * （「姓名1Name」「姓名Name」）。
         */
        val CHINESE_NAME_LABELS = listOf("/", "1", "l", "i", "|", "").flatMap { listOf("姓名${it}name", "name${it}姓名") }

        /** 证件号的字段名。 */
        val DOCUMENT_NUMBER_LABELS = listOf(
            "passport no", "passport number", "passport #", "document no", "document number", "doc no",
        )

        /** 出生日期的字段名。 */
        val BIRTH_DATE_LABELS = listOf("date of birth", "birth date", "birthdate", "birthday", "dob", "d.o.b")

        /** 出生地的字段名。 */
        val BIRTH_PLACE_LABELS = listOf("place of birth", "birth place", "birthplace")

        /** 不打码的字段名：只拿来分栏（同一排它右边是下一栏）、截住（下面一行是它，就不是上一个字段的值）。 */
        private val OTHER_LABELS = listOf(
            "type", "code", "country code", "issuing country", "issuing state", "nationality", "sex", "gender",
            "date of issue", "issue date", "date of expiry", "date of expiration", "expiry date", "expiration date",
            "expires", "exp", "iss", "valid until", "authority", "issuing authority", "issued by", "place of issue",
            "personal no", "personal number", "height", "eyes", "signature", "holder's signature", "observations",
            "endorsements",
        )

        /**
         * 表单上单独一行的地址字段名（调研原型的 addressHeader）：「Shipping Address」「Deliver to」「Pickup」，
         * 网约车上后面可带时间「Pickup 9:41 AM」。前面可以有图标、符号，不能有字：驾驶证上的「住址 Address」
         * 是中文字段名带着译名，下面一栏是别的字段名，不是地址。
         */
        val ADDRESS_HEADER = Regex(
            """(?i)^[^\p{L}\p{N}]*(?:shipping|billing|delivery|mailing|home|work|pickup|return|business|postal|residential|registered|""" +
                """street|property|service|installation|correspondence|permanent|current)?\s*(?:address|ship to|deliver to|""" +
                """delivering to|pickup|dropoff|drop-off|postage address|address line 1|address line 2)\b[^A-Za-z0-9]*""" +
                """(?:\d{1,2}:\d{2}\s*[AP]M)?\s*$"""
        )

        /** 表单上单独一行的人名字段名（调研原型的 nameHeader）。 */
        val NAME_HEADER = Regex(
            """(?i)^[^\p{L}\p{N}]*(?:full name|name|first name|last name|guest name|recipient|guest|passenger|cardholder|""" +
                """account holder|contact|customer)\s*:?\s*$"""
        )

        private val ALL_DOCUMENT = keys(
            NAME_LABELS + CHINESE_NAME_LABELS + DOCUMENT_NUMBER_LABELS + BIRTH_DATE_LABELS + BIRTH_PLACE_LABELS + OTHER_LABELS
        )

        private fun documentLabels(text: String) = compactFind(text, ALL_DOCUMENT).map { withChinese(text, it) }

        /**
         * 「出生日期/Date of birth」：英文字段名前面隔着斜杠的一段汉字，并进字段名。
         * 斜杠读成了 1、l、I、竖线（「出生日期1Date of birth」），或者读丢了（「出生日期Date of birth」），一样。
         */
        private fun withChinese(text: String, label: IntRange): IntRange {
            var k = label.first - 1
            while (k >= 0 && text[k] == ' ') k--
            if (k >= 0 && text[k] in "/／|｜1lI") {
                var s = k - 1
                while (s >= 0 && text[s] == ' ') s--
                if (s >= 0 && text[s].isHan()) k = s
            }
            var start = -1
            while (k >= 0 && text[k].isHan()) start = k--
            return if (start >= 0) start..label.last else label
        }

        /** 这一行是字段名，不是值。表单上的字段名常以冒号、必填的星号结尾（「City, State, ZIP *」）。 */
        internal fun isLabelLine(text: String): Boolean =
            documentLabels(text).isNotEmpty() || ADDRESS_HEADER.matches(text) || NAME_HEADER.matches(text) ||
                text.trimEnd().let { it.endsWith(':') || it.endsWith('：') || it.endsWith('*') }

        /** 字段名去掉空格、转小写后拼成的正则，长的在前：「given names」不会只认出「given name」。 */
        private fun keys(words: List<String>) =
            Regex(words.map { it.replace(" ", "") }.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) })

        /** 字母，汉字除外：中文字段名后面直接跟着英文（「出生日期Date of birth」）照样认。 */
        private fun Char.isNonHanLetter() = isLetter() && !isHan()

        /**
         * 在去掉空格、转了小写的行里找，映射回原串；前后紧挨字母的不算（「Typeface」里没有「Type」）。
         * 全角斜杠当半角：「姓名／Name」。
         */
        private fun compactFind(text: String, pattern: Regex): List<IntRange> {
            val origin = text.indices.filter { !text[it].isWhitespace() }
            val compact = String(CharArray(origin.size) {
                when (val c = text[origin[it]].lowercaseChar()) { '’' -> '\''; '／' -> '/'; else -> c }
            })
            return pattern.findAll(compact).mapNotNull { m ->
                val first = origin[m.range.first]
                val last = origin[m.range.last]
                val before = text.getOrNull(first - 1)
                val after = text.getOrNull(last + 1)
                if (before?.isNonHanLetter() == true || after?.isNonHanLetter() == true) null else first..last
            }.toList()
        }
    }
}
