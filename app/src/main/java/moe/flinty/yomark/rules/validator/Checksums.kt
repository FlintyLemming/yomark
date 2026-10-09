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

    // ---------- 护照 MRZ（TD3，两行各 44 字符） ----------
    fun mrzTd3Line1(line: String): Boolean =
        line.length == 44 && MRZ_L1.matches(line)

    fun mrzTd3Line2Valid(line: String): Boolean {
        if (line.length != 44) return false
        if (!line.all { it in 'A'..'Z' || it.isDigit() || it == '<' }) return false
        // 护照号 0..8 校验位 9；生日 13..18 校验位 19；有效期 21..26 校验位 27
        return mrzCheck(line.substring(0, 9)) == line[9] &&
            mrzCheck(line.substring(13, 19)) == line[19] &&
            mrzCheck(line.substring(21, 27)) == line[27]
    }

    private fun mrzCheck(field: String): Char {
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

    // ---------- UPS 快递单号校验位 ----------
    fun upsCheckDigit(tracking: String): Boolean {
        val s = tracking.uppercase()
        if (s.length != 18 || !s.startsWith("1Z")) return false
        val body = s.substring(2, 17)
        val check = s[17]
        if (!check.isDigit()) return false
        var sum = 0
        body.forEachIndexed { i, c ->
            // 字母取 (ASCII - 63) mod 10，即 A=2 … Z=7（'A' - 'A' + 2 == 'A'.code - 63）
            val v = if (c.isDigit()) c - '0' else ((c - 'A') + 2) % 10
            // 加倍落在**偶数下标**上。计划里写的是奇数下标加倍，那会把公开标准样例
            // 1Z12345E0205271688 的校验位算成 6（实际是 8）。用该样例反推确认了这个方向。
            sum += if (i % 2 == 0) v * 2 else v
        }
        val expected = (10 - sum % 10) % 10
        return expected == check - '0'
    }

    // ---------- MAC：分隔符一致性 ----------
    fun macSeparatorConsistent(raw: String): Boolean {
        val seps = raw.filter { it == ':' || it == '-' }
        return seps.length == 5 && seps.toSet().size == 1
    }

    private val ID_WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    private val SSN_SHAPE = Regex("""^(\d{3})-(\d{2})-(\d{4})$""")
    private val MRZ_L1 = Regex("""^P[A-Z<][A-Z<]{3}[A-Z<]{39}$""")

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
