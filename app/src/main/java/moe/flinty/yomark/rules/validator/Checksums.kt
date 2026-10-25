package moe.flinty.yomark.rules.validator

import java.math.BigInteger
import kotlin.math.ln

/**
 * 规则表的校验步骤（spec §6）。
 * 只有正则没有校验的规则误报率高到不可用，所以每条规则都必须配一个这里的函数。
 */
object Checksums {

    // ---------- 支付卡：Luhn ----------
    fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        if (!digits.all { it.isDigit() }) return false
        if (digits.all { it == '0' }) return false
        var sum = 0
        var double = false
        for (i in digits.indices.reversed()) {
            var d = digits[i] - '0'
            if (double) {
                d *= 2
                if (d > 9) d -= 9
            }
            sum += d
            double = !double
        }
        return sum % 10 == 0
    }

    // ---------- IBAN：长度查表 + 重排 mod-97 ----------
    fun ibanValid(raw: String): Boolean {
        val s = raw.filterNot { it == ' ' || it == '-' }.uppercase()
        if (s.length < 5) return false
        val cc = s.take(2)
        if (!cc.all { it in 'A'..'Z' }) return false
        val expected = ibanLengthForCountry(cc) ?: return false
        if (s.length != expected) return false
        if (!s.drop(2).take(2).all { it.isDigit() }) return false
        if (!s.drop(4).all { it.isLetterOrDigit() }) return false

        val rearranged = s.drop(4) + s.take(4)
        val numeric = buildString {
            rearranged.forEach { c ->
                if (c.isDigit()) append(c) else append((c - 'A') + 10)
            }
        }
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    fun ibanLengthForCountry(cc: String): Int? = IBAN_LENGTHS[cc]

    // ---------- SSN：排除无效段 ----------
    fun ssnValid(raw: String): Boolean {
        val m = SSN_SHAPE.matchEntire(raw.trim()) ?: return false
        val area = m.groupValues[1].toInt()
        val group = m.groupValues[2].toInt()
        val serial = m.groupValues[3].toInt()
        if (area == 0 || area == 666 || area >= 900) return false
        if (group == 0) return false
        if (serial == 0) return false
        return true
    }

    // ---------- 中国居民身份证：GB 11643 的 ISO 7064 MOD 11-2 校验码 ----------
    fun chineseIdValid(raw: String): Boolean {
        if (raw.length != 18 || !raw.take(17).all { it.isDigit() }) return false
        val sum = (0 until 17).sumOf { (raw[it] - '0') * ID_WEIGHTS[it] }
        return "10X98765432"[sum % 11] == raw[17].uppercaseChar()
    }

    // ---------- 香港身份证：mod 11，单字母前面补一个空格（算 36） ----------
    /** [body] 是一两个字母加六位数字（「A123456」），[check] 是括号里那一位（「3」「A」）。 */
    fun hkidValid(body: String, check: Char): Boolean {
        if (!HKID_BODY.matches(body)) return false
        val padded = if (body[1].isDigit()) " $body" else body
        val sum = padded.withIndex().sumOf { (i, c) ->
            val v = when (c) {
                ' ' -> 36
                in 'A'..'Z' -> c - 'A' + 10
                else -> c - '0'
            }
            v * (9 - i)
        }
        val r = (11 - sum % 11) % 11
        return check == if (r == 10) 'A' else '0' + r
    }

    // ---------- 台湾身份证、居留证统一证号：字母换两位数，加权和是 10 的倍数 ----------
    /**
     * 「A123456789」（身分证，第二位 1/2）、「A800000014」（新式居留证，8/9）、「AA12345675」（旧式居留证，第二位 A–D）。
     * 第二位是字母时取它两位码的个位。
     */
    fun taiwanIdValid(raw: String): Boolean {
        if (raw.length != 10 || raw[0] !in 'A'..'Z' || !raw.drop(2).all { it.isDigit() }) return false
        val first = TW_LETTER["ABCDEFGHIJKLMNOPQRSTUVWXYZ".indexOf(raw[0])]
        val second = when (raw[1]) {
            in '0'..'9' -> raw[1] - '0'
            in 'A'..'D' -> TW_LETTER[raw[1] - 'A'] % 10
            else -> return false
        }
        val digits = listOf(first / 10, first % 10, second) + raw.drop(2).map { it - '0' }
        return digits.zip(TW_WEIGHTS).sumOf { (d, w) -> d * w } % 10 == 0
    }

    // ---------- 新加坡 NRIC / FIN：七位数字加权，按前缀查末位字母 ----------
    fun nricValid(raw: String): Boolean {
        if (raw.length != 9 || !raw.substring(1, 8).all { it.isDigit() }) return false
        val offset = when (raw[0]) { 'S', 'F' -> 0; 'T', 'G' -> 4; 'M' -> 3; else -> return false }
        val sum = (0 until 7).sumOf { (raw[1 + it] - '0') * NRIC_WEIGHTS[it] } + offset
        val table = when (raw[0]) { 'S', 'T' -> "JZIHGFEDCBA"; 'F', 'G' -> "XWUTRQPNMLK"; else -> "XWUTRQPNJLK" }
        return table[sum % 11] == raw[8]
    }

    // ---------- 韩国居民登录号：前十二位加权 mod 11（2020 年 10 月以前发的号码才有）----------
    /** 十三位数字（连字符、空格先去掉）。 */
    fun rrnValid(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }.map { it - '0' }
        if (d.size != 13) return false
        val sum = (0 until 12).sumOf { d[it] * RRN_WEIGHTS[it] }
        return (11 - sum % 11) % 10 == d[12]
    }

    private val RRN_WEIGHTS = intArrayOf(2, 3, 4, 5, 6, 7, 8, 9, 2, 3, 4, 5)

    // ---------- 巴西 CPF：两位 mod 11 校验位，全同的号码不算 ----------
    fun cpfValid(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }.map { it - '0' }
        if (d.size != 11 || d.distinct().size == 1) return false
        fun check(n: Int): Int {
            val r = (0 until n).sumOf { d[it] * (n + 1 - it) } % 11
            return if (r < 2) 0 else 11 - r
        }
        return d[9] == check(9) && d[10] == check(10)
    }

    // ---------- 车辆识别代号（VIN）：GB 16735 附录 A / ISO 3779 的第九位校验位 ----------
    /** 17 位，不含 I、O、Q。余数 10 写作 X。 */
    fun vinValid(raw: String): Boolean {
        if (raw.length != 17) return false
        var sum = 0
        raw.forEachIndexed { i, c ->
            val v = when (c) {
                in '0'..'9' -> c - '0'
                else -> VIN_LETTERS.indexOf(c).takeIf { it >= 0 }?.let { VIN_VALUES[it] } ?: return false
            }
            sum += v * VIN_WEIGHTS[i]
        }
        val r = sum % 11
        return raw[8] == if (r == 10) 'X' else '0' + r
    }

    // ---------- 机读码（MRZ）：怎么认见 rules/Mrz.kt ----------
    /** 机读码一个字段的校验位（权重 7、3、1 循环，字母 A=10……Z=35，填充符 < 算 0）。字段里有别的字符时给「?」。 */
    fun mrzCheck(field: String): Char {
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        field.forEachIndexed { i, c ->
            val v = when {
                c.isDigit() -> c - '0'
                c == '<' -> 0
                c in 'A'..'Z' -> c - 'A' + 10
                else -> return '?'
            }
            sum += v * weights[i % 3]
        }
        return '0' + (sum % 10)
    }

    // ---------- API key：Shannon 熵 ----------
    fun shannonEntropy(s: String): Double {
        if (s.isEmpty()) return 0.0
        val counts = HashMap<Char, Int>()
        s.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        var h = 0.0
        counts.values.forEach { c ->
            val p = c.toDouble() / s.length
            h -= p * (ln(p) / ln(2.0))
        }
        return h
    }

    // ---------- MAC：分隔符一致性 ----------
    fun macSeparatorConsistent(raw: String): Boolean {
        val seps = raw.filter { it == ':' || it == '-' }
        return seps.length == 5 && seps.toSet().size == 1
    }

    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private val SSN_SHAPE = Regex("""^(\d{3})-(\d{2})-(\d{4})$""")
    private val HKID_BODY = Regex("""[A-Z]{1,2}\d{6}""")

    /** A–Z 的两位码。I、O、W、Z 不按顺序：它们是后来补进去的县市。 */
    private val TW_LETTER = intArrayOf(
        10, 11, 12, 13, 14, 15, 16, 17, 34, 18, 19, 20, 21, 22, 35, 23, 24, 25, 26, 27, 28, 29, 32, 30, 31, 33,
    )
    private val TW_WEIGHTS = listOf(1, 9, 8, 7, 6, 5, 4, 3, 2, 1, 1)
    private val NRIC_WEIGHTS = intArrayOf(2, 7, 6, 5, 4, 3, 2)

    private const val VIN_LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ"
    private val VIN_VALUES = intArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 1, 2, 3, 4, 5, 7, 9, 2, 3, 4, 5, 6, 7, 8, 9)
    private val VIN_WEIGHTS = intArrayOf(8, 7, 6, 5, 4, 3, 2, 10, 0, 9, 8, 7, 6, 5, 4, 3, 2)

    /** 常见国家的 IBAN 长度。缺的国家会被判为无效——宁可漏检 IBAN，也不要误报一串数字。 */
    private val IBAN_LENGTHS = mapOf(
        "AD" to 24, "AE" to 23, "AL" to 28, "AT" to 20, "AZ" to 28, "BA" to 20, "BE" to 16,
        "BG" to 22, "BH" to 22, "BR" to 29, "BY" to 28, "CH" to 21, "CR" to 22, "CY" to 28,
        "CZ" to 24, "DE" to 22, "DK" to 18, "DO" to 28, "EE" to 20, "EG" to 29, "ES" to 24,
        "FI" to 18, "FO" to 18, "FR" to 27, "GB" to 22, "GE" to 22, "GI" to 23, "GL" to 18,
        "GR" to 27, "GT" to 28, "HR" to 21, "HU" to 28, "IE" to 22, "IL" to 23, "IQ" to 23,
        "IS" to 26, "IT" to 27, "JO" to 30, "KW" to 30, "KZ" to 20, "LB" to 28, "LC" to 32,
        "LI" to 21, "LT" to 20, "LU" to 20, "LV" to 21, "LY" to 25, "MC" to 27, "MD" to 24,
        "ME" to 22, "MK" to 19, "MR" to 27, "MT" to 31, "MU" to 30, "NL" to 18, "NO" to 15,
        "PK" to 24, "PL" to 28, "PS" to 29, "PT" to 25, "QA" to 29, "RO" to 24, "RS" to 22,
        "SA" to 24, "SC" to 31, "SE" to 24, "SI" to 19, "SK" to 24, "SM" to 27, "ST" to 25,
        "SV" to 28, "TL" to 23, "TN" to 24, "TR" to 26, "UA" to 29, "VA" to 22, "VG" to 24,
        "XK" to 20,
    )
}
