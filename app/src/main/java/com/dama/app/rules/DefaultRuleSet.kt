package com.dama.app.rules

import com.dama.app.core.model.SensitiveKind
import com.dama.app.rules.validator.Checksums
import com.dama.app.rules.validator.KnownTlds

/**
 * 首版的全部规则（spec §6）。11 条，每条都有校验步骤。
 *
 * 「默认」按**误报率**划线，不按危害划线：高误报类型自动打码会让用户
 * 一直在跟 app 对着干；有校验位兜底的类型误报接近零，自动打码不打扰任何人。
 */
object DefaultRuleSet {

    // ---------- 高置信：有校验位兜底，误报接近零，默认打码 ----------

    private val CARD = RegexRule(
        id = "card",
        kind = SensitiveKind.PAYMENT_CARD,
        enabledByDefault = true,
        // 13–19 位数字，容忍空格与连字符分组；前后不能紧邻数字
        pattern = Regex("""(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.luhn(m.value.filter { it.isDigit() }) },
    )

    private val IBAN = RegexRule(
        id = "iban",
        kind = SensitiveKind.IBAN,
        enabledByDefault = true,
        pattern = Regex("""(?<![A-Z0-9])[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{1,4}){2,8}(?![A-Z0-9])"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.ibanValid(m.value) },
    )

    private val SSN = RegexRule(
        id = "ssn",
        kind = SensitiveKind.SSN,
        enabledByDefault = true,
        pattern = Regex("""(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.ssnValid(m.value) },
    )

    private val MAC = RegexRule(
        id = "mac",
        kind = SensitiveKind.MAC_ADDR,
        enabledByDefault = true,
        pattern = Regex("""(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])"""),
        confidence = 0.9f,
        validate = { _, m -> Checksums.macSeparatorConsistent(m.value) },
    )

    private val EMAIL = RegexRule(
        id = "email",
        kind = SensitiveKind.EMAIL,
        enabledByDefault = true,
        // RFC 5322 简化式
        pattern = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.([A-Za-z]{2,24})(?![A-Za-z0-9.-])"""),
        confidence = 0.9f,
        validate = { _, m -> KnownTlds.isKnown(m.groupValues[1]) },
    )

    private val PASSPORT = RegexRule(
        id = "passport",
        kind = SensitiveKind.PASSPORT,
        enabledByDefault = true,
        // TD3：两行各 44 字符，字符集是大写字母、数字与填充符
        pattern = Regex("""(?<![A-Z0-9<])[A-Z0-9<]{44}(?![A-Z0-9<])"""),
        confidence = 0.95f,
        validate = { _, m ->
            Checksums.mrzTd3Line1(m.value) || Checksums.mrzTd3Line2Valid(m.value)
        },
    )

    private val API_KEY = RegexRule(
        id = "apikey",
        kind = SensitiveKind.API_KEY,
        enabledByDefault = true,
        pattern = Regex("""(?<![A-Za-z0-9_-])(?:sk-|ghp_|gho_|ghs_|AKIA|eyJ)[A-Za-z0-9_\-.]{12,}"""),
        confidence = 0.9f,
        validate = { _, m -> Checksums.shannonEntropy(m.value) > 3.5 },
    )

    // ---------- 仅圈出：误报率高，靠导出拦截兜底（spec §6 / §7.4） ----------

    private val URL_PATTERN = Regex(
        """(?<![A-Za-z0-9@._-])(?:https?://)?[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.([A-Za-z]{2,24})(/[^\s]*)?"""
    )

    private val URL = RegexRule(
        id = "url",
        kind = SensitiveKind.URL,
        enabledByDefault = false,
        pattern = URL_PATTERN,
        confidence = 0.7f,
        validate = { _, m -> KnownTlds.isKnown(m.groupValues[1]) && m.groupValues[2].length > 1 },
        // 只遮 path 与 query，域名保留
        select = { m ->
            val path = m.groups[2]!!
            path.range
        },
    )

    private val IP = RegexRule(
        id = "ip",
        kind = SensitiveKind.IP_ADDR,
        enabledByDefault = false,
        pattern = Regex(
            """(?<![\w.:])(?:(?:\d{1,3}\.){3}\d{1,3}|(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4})(?![\w.:])"""
        ),
        confidence = 0.6f,
        validate = { text, m -> validIpAndNotAVersion(text, m) },
    )

    private fun validIpAndNotAVersion(text: String, m: MatchResult): Boolean {
        val v = m.value
        if (v.contains(':')) return true                      // IPv6：正则本身已足够严格

        // 各段数值范围
        val parts = v.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.length > 1 && it.startsWith("0") }) return false
        if (parts.any { (it.toIntOrNull() ?: return false) !in 0..255 }) return false

        // 排除版本号误报：紧邻的 v / version 前缀
        val before = text.substring(0, m.range.first).takeLast(12).lowercase()
        if (before.trimEnd().endsWith("v") || before.contains("version")) return false
        return true
    }

    private val TRACKING = object : Rule {
        override val id = "tracking"
        override val kind = SensitiveKind.TRACKING_NO
        override val enabledByDefault = false

        private val ups = Regex("""(?<![A-Za-z0-9])1Z[0-9A-Za-z]{16}(?![A-Za-z0-9])""")
        // FedEx 12/15 位、USPS 20/22 位：无校验位，置信度调低
        private val plainDigits = Regex("""(?<![\d-])(?:\d{12}|\d{15}|\d{20}|\d{22})(?![\d-])""")

        override fun findIn(text: String): List<RuleMatch> {
            val out = ArrayList<RuleMatch>()
            ups.findAll(text)
                .filter { Checksums.upsCheckDigit(it.value) }
                .forEach { out += RuleMatch(it.range, 0.85f) }
            plainDigits.findAll(text)
                .filter { d -> out.none { it.range.first <= d.range.last && d.range.first <= it.range.last } }
                .forEach { out += RuleMatch(it.range, 0.5f) }   // 无校验位 → 低置信度
            return out
        }
    }

    val rules: List<Rule> = listOf(
        CARD, IBAN, SSN, MAC, EMAIL, PhoneRule(), PASSPORT, API_KEY,
        URL, IP, TRACKING,
    )
}
