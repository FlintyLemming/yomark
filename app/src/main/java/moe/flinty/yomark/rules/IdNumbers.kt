package moe.flinty.yomark.rules

import moe.flinty.yomark.rules.validator.Checksums
import java.time.DateTimeException
import java.time.LocalDate

/**
 * 大陆居民身份证号（GB 11643）：18 位，末位可以是 X。没有字段名也认，靠三道关：省份打头、中间八位是过去的
 * 真实日期、MOD 11-2 校验码。随手一串 18 位数字三道都过的不到万分之一；只看校验码的话十一个里就有一个。
 *
 * 身份证上号码字距宽，照片上 OCR 会在中间补空格，还会把第一位粘到字段名上：
 * 「公民 身份号 码3 202 01201109254526」。所以把单个空格隔开的数字段连起来看，连起来正好 18 位的那几段
 * 算一个号码；只在段与段之间切，不在段内切——20 位的时间戳里切不出身份证号来。
 * X 认 x、×、全角的Ｘ，和希腊、西里尔字母里长得一样的那几个。
 *
 * 港澳台居民居住证（810000/820000/830000 打头）、2023 版外国人永居证（9 加省份打头）用的是同一套号码。
 *
 * 只认号码本身，不管前面写着什么；证件号码一类、人名的旁证用的是挡掉单据号的 [IdNumbers.RESIDENT]。
 * [birthDate] 给对照证件上「出生」那一行用。
 */
object ResidentIdNumber : Finder {

    /** 一处号码：[range] 在原串上，从第一位到校验位；[number] 去掉了空格，X 统一成大写。 */
    class Found(val range: IntRange, val number: String)

    override fun findIn(text: String): List<RuleMatch> = findAll(text).map { RuleMatch(it.range, CONFIDENCE) }

    fun findAll(text: String, today: LocalDate = LocalDate.now()): List<Found> {
        val out = ArrayList<Found>()
        runs(text).forEach { groups ->
            // 从第 s 段起往后连，连到正好 18 位为止；对得上就从下一段接着找，对不上从 s 的下一段重来
            var s = 0
            while (s < groups.size) {
                var e = s
                val number = StringBuilder()
                var found: Found? = null
                while (e < groups.size) {
                    number.append(groups[e].chars)
                    if (number.length >= LENGTH) {
                        if (number.length == LENGTH && isValid(number.toString(), today)) {
                            found = Found(groups[s].first..groups[e].last, number.toString())
                        }
                        break
                    }
                    e++
                }
                if (found != null) {
                    out += found
                    s = e + 1
                } else s++
            }
        }
        return out
    }

    /** 省份、生日、校验码都对得上。[number] 是 18 位，X 大写。 */
    fun isValid(number: String, today: LocalDate = LocalDate.now()): Boolean {
        if (number.length != LENGTH || !number.take(17).all { it in '0'..'9' }) return false
        val province = if (number[0] == '9') number.substring(1, 3) else number.take(2)
        if (province.toInt() !in PROVINCES) return false
        val birth = birthDate(number) ?: return false
        return birth.year >= 1880 && !birth.isAfter(today) && Checksums.chineseIdValid(number)
    }

    /** 号码里的生日（第 7 到 14 位）。不是真实的日期给 null。 */
    fun birthDate(number: String): LocalDate? {
        if (number.length < 14) return null
        val digits = number.substring(6, 14)
        if (!digits.all { it in '0'..'9' }) return null
        return try {
            LocalDate.of(digits.take(4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    /** 一段连着的数字：[chars] 是归一后的字，[first]..[last] 是它在原串上的位置。 */
    private class Group(val first: Int, val last: Int, val chars: String)

    /**
     * 行里一串串单个空格连起来的数字段。整串前后紧挨着字母数字的不要：「ID330106…」「…0423A」都不是一个独立的号码。
     */
    private fun runs(text: String): List<List<Group>> {
        val out = ArrayList<List<Group>>()
        var i = 0
        while (i < text.length) {
            if (norm(text[i]) == null) { i++; continue }
            val groups = ArrayList<Group>()
            val start = i
            while (true) {
                val g = i
                val sb = StringBuilder()
                while (i < text.length) {
                    sb.append(norm(text[i]) ?: break)
                    i++
                }
                groups += Group(g, i - 1, sb.toString())
                // 只跨单个空格：两个空格以上是版面上真有的间隙，不是 OCR 补的
                if (i + 1 < text.length && text[i] == ' ' && norm(text[i + 1]) != null) i++ else break
            }
            val before = text.getOrNull(start - 1)
            val after = text.getOrNull(i)
            if (!before.isAlnum() && !after.isAlnum()) out += groups
        }
        return out
    }

    /** 数字、全角数字、各种写法的 X 归成 0–9 和 X，别的字给 null。 */
    private fun norm(c: Char): Char? = when (c) {
        in '0'..'9' -> c
        in '０'..'９' -> '0' + (c - '０')
        in X_LOOKALIKES -> 'X'
        else -> null
    }

    private fun Char?.isAlnum() = this != null && (this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this in '０'..'９')

    private const val LENGTH = 18
    private const val CONFIDENCE = 0.95f

    /** 校验码 X 被认成的字：x、×、全角Ｘｘ、希腊字母 Χχ、西里尔字母 Хх。 */
    internal const val X_LOOKALIKES = "Xx×ＸｘΧχХх"

    /** 省级行政区代码：华北 11–15，东北 21–23，华东 31–37，中南 41–46，西南 50–54，西北 61–65，台湾 71，港澳台居住证 81–83。 */
    private val PROVINCES: Set<Int> =
        ((11..15) + (21..23) + (31..37) + (41..46) + (50..54) + (61..65) + 71 + (81..83)).toSet()
}

/**
 * 证件号码的其余认法：字段名后面的号码（[labeled]），和几种没有字段名也敢认的大陆、港台、外国证件号。
 * 排列先后、默认打码见 DefaultRuleSet 的 ID_NUMBER。
 */
internal object IdNumbers {

    /**
     * 证件号码的汉字字段名。身份证、驾驶证（证号、档案编号）、社保卡、通行证、居住证、出生证、结婚证、毕业证书、
     * 学生证、户口簿，港台的身份证，日本的个人番号。长的排前面由 [IdField] 管。
     * 字段名前面紧挨着汉字的不认（「凭证号」「客户号」），所以「毕业证书编号」这种要整个写上。
     * 「身份号码」是 OCR 把「公民」另切成一块时剩下的。
     */
    val LABELS = listOf(
        "公民身份号码", "公民身份证号码", "公民身份证件编号", "身份证号码", "身份证号", "身份证件号码", "身份证件号", "身份号码",
        "有效身份证件号码", "证件号码", "证件号", "证件编号", "社会保障号码", "社保号码", "社会保障卡号", "社保卡号",
        "证号", "档案编号", "通行证号码", "居住证号码", "居留证号码", "出生证编号", "出生医学证明编号",
        "结婚证字号", "离婚证字号", "毕业证书编号", "学位证书编号", "教师资格证书编号",
        "学号", "学籍号", "户号",
        "身份證號碼", "身分證字號", "個人番号", "マイナンバー",
    )

    /** 不分大小写的英文字段名：够长，不会是别的意思。 */
    private val SPELLED_OUT = listOf(
        "Identity Card No.", "Identity No.", "National Insurance number", "NI number", "Social Insurance Number",
        "Aadhaar", "Aadhaar No.",
    )

    /** 缩写只认大写（下面的 FIN、SIN、VID 也是）：小写的「?vid=」「sin」是网址参数、正弦。 */
    private val ACRONYMS = listOf("NINO", "UI No.", "DNI", "NIE", "CPF")

    /**
     * 字段名后面认号码。值见 [VALUE]；不验校验位：字段名已经说明了这是证件号，OCR 认错一两位也照样该遮。
     * 几个字段名在别处也常见，后面的值要再像一点：
     * - 「FIN」「NRIC」要跟新加坡的「S1234567D」，「FIN 202612345」是财务的；「SIN」要九位，「VID」要十六位；
     * - 「ID No.」「ID Number」前面紧挨着别的英文词的是别的号码：「Tracking ID Number」「Product ID No.」，
     *   「National ID No.」这几种例外（[ID_OWNERS]）；
     * - 「統一編號」也是台湾公司的统一编号，八位数字印在每张发票上，要是个人的「A123456789」；
     * - 「证书编号」也印在产品认证证书上（「CQC2021…」），个人证书的编号是数字打头的；
     * - 澳门身份证的字段名只是「編號」，后面要是「1234567(8)」。
     */
    fun labeled(confidence: Float): Finder {
        fun field(
            labels: List<String>,
            value: Regex = VALUE,
            ignoreCase: Boolean = false,
            keep: (text: String, label: IntRange, value: IntRange) -> Boolean = { _, _, _ -> true },
        ) = IdField(labels, value, confidence, ignoreCase) { text, label, v -> plausible(text, v) && keep(text, label, v) }
        return firstOf(
            field(LABELS),
            field(SPELLED_OUT, ignoreCase = true),
            field(ACRONYMS),
            field(listOf("ID No.", "ID Number"), ignoreCase = true) { text, label, _ -> ownedId(text, label) },
            field(listOf("NRIC No.", "NRIC", "FIN"), value = Regex("""[STFGM]\d{7}[A-Z](?![A-Za-z0-9])""")),
            field(listOf("SIN"), value = Regex("""\d{3}[ \-]?\d{3}[ \-]?\d{3}(?![A-Za-z0-9])""")),
            field(listOf("VID"), value = Regex("""\d{4} ?\d{4} ?\d{4} ?\d{4}(?![A-Za-z0-9])""")),
            field(listOf("統一編號", "統一證號"), value = Regex("""[A-Z][A-Z0-9]\d{8}(?![A-Za-z0-9])""")),
            field(listOf("证书编号", "证书号码")) { text, _, v -> text[v.first].isDigit() },
            field(listOf("編號", "Número", "Numero"), value = Regex("""\d{7} ?[(（] ?\d ?[)）]"""), ignoreCase = true),
        )
    }

    private fun plausible(text: String, r: IntRange): Boolean {
        val v = text.substring(r)
        val hidden = v.count { it in MASKS }
        return v.count(Char::isDigit) >= 5 &&
            // 号码大半打了星：截图的 app 已经替用户遮过了，不再另框（原先也不框）
            hidden * 2 < v.count { !it.isWhitespace() } &&
            // 字母字段名直接连着字母开头的值：「FINLAND 12345」里的「FIN」不是字段名
            !(v.first().isLetter() && text.getOrNull(r.first - 1)?.let { it in 'A'..'Z' || it in 'a'..'z' } == true)
    }

    /** 「ID No.」前面没有别的英文词，或者是「National」「Passenger」这种说明是人的证件号的。 */
    private fun ownedId(text: String, label: IntRange): Boolean {
        val word = WORD_BEFORE.find(text.substring(0, label.first))?.groupValues?.get(1) ?: return true
        return word.lowercase() in ID_OWNERS
    }

    private val WORD_BEFORE = Regex("""([A-Za-z]+)[ 　]+$""")

    private val ID_OWNERS = setOf(
        "national", "personal", "citizen", "resident", "residence", "voter", "student", "passenger", "applicant", "holder",
        "your", "my",
    )

    private const val MASKS = "*＊•●※"

    private const val X = ResidentIdNumber.X_LOOKALIKES

    /**
     * 字段名后面的号码：字母数字，中间可夹单个空格或者连字符（「AB 12 34 56 C」「J320102-2015-001234」），两种不混用；
     * 末尾可带括号里的校验位（「A123456(3)」）和各种写法的 X（「11010519491231002Ｘ」）。
     * 第一段之后每段只有数字，末尾单独一个校验字母例外：「ID No. 123456 VALID」不把「VALID」吞进去。
     * 空格后面那段接着点、斜杠、冒号、年月的是日期时间，不接：「证件号码 H12345678 2026.10.12」。
     * 打了星的号码也收，大半打了星的在 [plausible] 里丢掉；总长在 [IdField] 里截。
     * 后面紧跟着汉字没关系（「证件号码330106…有效期」），紧跟着字母数字的不是一个完整的号码。
     */
    private val VALUE = Regex(
        """[A-Z0-9][A-Z0-9$MASKS]*(?:(?:-[0-9$MASKS]++)+|(?: [0-9$MASKS]++(?![./:：\-][0-9]|[年月]))+)?""" +
            """(?:[ \-]?[A-Z](?![A-Za-z0-9]))?(?: ?[(（][0-9A-Z][)）])?[$X]?(?![A-Za-z0-9$MASKS$X])"""
    )

    /**
     * 没有字段名的大陆身份证号。地区码加日期再加四位的单据号（「办件编号 330106202610110019」）只剩校验码一道关，
     * 十一个里有一个过得去，跟在订单这类提示词后面的不算。
     */
    val RESIDENT: Finder = notAfterOrderHint(ResidentIdNumber)

    /** 香港身份证：一两个字母、六位数字、括号里的校验位，「A123456(3)」。不带括号的只在字段名后面认。 */
    val HKID: Finder = regexFinder(Regex("""(?<![A-Za-z0-9])([A-Z]{1,2})(\d{6}) ?[(（] ?([0-9A]) ?[)）]"""), CHECKED) {
        Checksums.hkidValid(it.groupValues[1] + it.groupValues[2], it.groupValues[3][0])
    }

    /** 台湾身分证「A123456789」（第二位 1、2），居留证统一证号新式「A800000014」（8、9）、旧式「AA12345675」（A–D）。 */
    val TAIWAN: Finder = notAfterOrderHint(
        regexFinder(Regex("""(?<![A-Za-z0-9])[A-Z][1289A-D]\d{8}(?![A-Za-z0-9])"""), CHECKED) { Checksums.taiwanIdValid(it.value) }
    )

    /** 新加坡 NRIC、FIN：「S1234567D」。 */
    val NRIC: Finder = notAfterOrderHint(
        regexFinder(Regex("""(?<![A-Za-z0-9])[STFGM]\d{7}[A-Z](?![A-Za-z0-9])"""), CHECKED) { Checksums.nricValid(it.value) }
    )

    /**
     * 韩国居民登录号「800101-1234560」：生日、连字符、性别世纪位 1–8 加六位。2020 年以后发的号码后六位是随机的，
     * 不验校验位，只验生日；连字符必须有，两边可有空格。日期打头的订单号「261012-1234567」也是这个形状，跟在订单后面的不算。
     */
    val KOREA_RRN: Finder = notAfterOrderHint(
        regexFinder(Regex("""(?<![A-Za-z0-9\-])(\d{6}) ?- ?([1-8])\d{6}(?![A-Za-z0-9\-])"""), SHAPED) {
            val century = if (it.groupValues[2][0] in "1256") 1900 else 2000
            pastDate(century, it.groupValues[1]) != null
        }
    )

    /** 马来西亚 MyKad「900101-14-5677」：生日、出生地代码、四位。只认带连字符的，十二位连写的太像别的号码。 */
    val MYKAD: Finder = notAfterOrderHint(
        regexFinder(Regex("""(?<![A-Za-z0-9\-])(\d{6}) ?- ?(\d{2}) ?- ?\d{4}(?![A-Za-z0-9\-])"""), SHAPED) {
            (pastDate(1900, it.groupValues[1]) != null || pastDate(2000, it.groupValues[1]) != null) &&
                it.groupValues[2].toInt() in MYKAD_PLACES
        }
    )

    /** 日本在留卡「AB12345678CD」：两个字母、八位数字、两个字母。没有校验位，形状本身够特别。 */
    val ZAIRYU: Finder = notAfterOrderHint(regexFinder(Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{8}[A-Z]{2}(?![A-Za-z0-9])"""), SHAPED) { true })

    /** 巴西 CPF「529.982.247-25」：带点和连字符的写法，两位校验位。十一位连写的和大陆手机号一样长，只在字段名后面认。 */
    val CPF: Finder = regexFinder(Regex("""(?<![\d.])\d{3}\.\d{3}\.\d{3}-\d{2}(?![\d.\-])"""), CHECKED) {
        Checksums.cpfValid(it.value)
    }

    /** 两位年月日（YYMMDD）加上世纪是不是过去的一个真实日期。 */
    private fun pastDate(century: Int, yymmdd: String): LocalDate? = try {
        LocalDate.of(century + yymmdd.take(2).toInt(), yymmdd.substring(2, 4).toInt(), yymmdd.substring(4, 6).toInt())
            .takeIf { !it.isAfter(LocalDate.now()) }
    } catch (_: DateTimeException) {
        null
    }

    /** MyKad 第七、八位：01–16、21–59 是州属，60–68、71–72、74–79、82–93、98–99 是外国或其他。 */
    private val MYKAD_PLACES: Set<Int> =
        ((1..16) + (21..59) + (60..68) + (71..72) + (74..79) + (82..93) + (98..99)).toSet()

    /** 有校验位的。 */
    private const val CHECKED = 0.95f

    /** 只有形状和日期的。 */
    private const val SHAPED = 0.85f
}

/**
 * 字段名后面的证件号码，同 LabeledField（也跑在 HanView 上），多认两种照片上 OCR 的写法：
 * 字段名被拆成几段（「公民 身份号 码3 202 01201109254526」），冒号前多了空格（「身份证号码 :330…」「证件号码 ： H12345678」）。
 * 所以字段名里两个汉字之间可夹一个空格，冒号前后都可以有空格。
 *
 * 值最多 [MAX_VALUE] 个字母数字：最长的证件号（身份证号、毕业证书编号、学籍号）18、19 位。超了就从最后一个空格往回截，
 * 「身份证号 330106199307180423 13812345678」后面那段是手机号。
 *
 * @param keep 再验一道：收下整行、字段名和值在原串上的区间。
 */
internal class IdField(
    labels: List<String>,
    private val value: Regex,
    private val confidence: Float,
    ignoreCase: Boolean = false,
    private val keep: (text: String, label: IntRange, value: IntRange) -> Boolean,
) : Finder {

    private val label = Regex(
        "(?<![\\u4e00-\\u9fa5A-Za-z0-9])(?" + (if (ignoreCase) "i" else "") + ":" +
            labels.sortedByDescending { it.length }.joinToString("|") { spaced(it) } +
            ")[ 　]*[：:＝=]?[ 　]*"
    )

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        return label.findAll(view.text).mapNotNull { l ->
            val v = value.matchAt(view.text, l.range.last + 1) ?: return@mapNotNull null
            val name = view.toSource(l.range)
            val range = trimmed(text, view.toSource(v.range))
            range.takeIf { keep(text, name, it) }
        }.map { RuleMatch(it, confidence) }.toList()
    }

    private fun trimmed(text: String, r: IntRange): IntRange {
        var last = r.last
        while ((r.first..last).count { text[it].isLetterOrDigit() || text[it] in "*＊•●※" } > MAX_VALUE) {
            val cut = text.lastIndexOf(' ', last)
            if (cut <= r.first) break
            last = cut - 1
        }
        return r.first..last
    }

    private companion object {
        const val MAX_VALUE = 20

        /** 「公民身份号码」→「公[ 　]?民[ 　]?身…」：只在两个汉字之间放。 */
        fun spaced(label: String): String {
            val parts = ArrayList<String>()
            var start = 0
            for (i in 1 until label.length) {
                if (label[i - 1].isHan() && label[i].isHan()) {
                    parts += label.substring(start, i)
                    start = i
                }
            }
            parts += label.substring(start)
            return parts.joinToString("[ 　]?") { Regex.escape(it) }
        }
    }
}

/** 正则找形状，[valid] 再验一道。 */
internal fun regexFinder(pattern: Regex, confidence: Float, valid: (MatchResult) -> Boolean) = Finder { text ->
    pattern.findAll(text).filter(valid).map { RuleMatch(it.range, confidence) }.toList()
}

/** [finder] 的命中再验一道：[keep] 收下整行和命中的区间。 */
internal class Checked(private val finder: Finder, private val keep: (text: String, range: IntRange) -> Boolean) : Finder {
    override fun findIn(text: String): List<RuleMatch> = finder.findIn(text).filter { keep(text, it.range) }
}

/** 几种认法合成一种，排在前面的优先：后面的与前面重叠的丢掉（同 CompositeRule）。 */
internal fun firstOf(vararg finders: Finder) = Finder { text ->
    val out = ArrayList<RuleMatch>()
    finders.forEach { f -> f.findIn(text).forEach { m -> if (out.none { it.range.overlaps(m.range) }) out += m } }
    out
}

/**
 * 前面一小段里有订单这类提示词的不算：没有字段名、只靠形状和校验位认的号码，单据号常是同一个形状。
 * 12306 的订单号「E123456789」就是一个字母加九位数字，十个里有一个过得了台湾身份证的校验；
 * 日期打头的订单号「261012-1234567」像韩国的居民登录号。窗口与 PhoneRule 判断订单号同一个口径。
 * 窗口里又说到身份、证件的照认：「订单联系人身份证 330106…」。
 */
internal fun notAfterOrderHint(finder: Finder) = Checked(finder) { text, r ->
    val window = text.substring(0, r.first).takeLast(12).lowercase()
    ORDER_HINTS.none { it in window } || ID_WORDS.any { it in window }
}

private val ORDER_HINTS = listOf(
    "order", "invoice", "receipt", "ref no", "ref.", "tracking", "sku", "model",
    "订单", "单号", "流水", "发票", "编号", "型号", "批号", "票号", "货号", "受理",
)

private val ID_WORDS = listOf("身份", "证件")
