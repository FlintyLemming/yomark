package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.youma.app.rules.validator.Checksums
import com.youma.app.rules.validator.KnownTlds

/**
 * 全部规则（spec §6 + 2026-09-04 增补 §3）。15 条：10 条默认打码、5 条仅圈出。
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

    // ---------- 标签锚定：姓名与地址（见 LabeledField） ----------

    /**
     * 姓名。值遇数字即停——同一行的电话归 PhoneRule 管，两条规则各出各的候选。
     *
     * 三个分支对应三种排版：连写的汉字名、被中文识别器拆成单字再用空格拼回来的名、
     * 拉丁名。连写分支放在最前，`张三 性别 男` 才会只取到 `张三`。
     */
    private val NAME_VALUE = Regex(
        """[\u4e00-\u9fa5]{2,6}""" +
            """|[\u4e00-\u9fa5](?:[ ][\u4e00-\u9fa5]){1,5}""" +
            """|[A-Za-z][A-Za-z.'\-]*(?:[ ][A-Za-z][A-Za-z.'\-]*){0,2}"""
    )

    private val NAME = CompositeRule(
        id = "name",
        kind = SensitiveKind.PERSON_NAME,
        enabledByDefault = true,
        LabeledField(
            labels = listOf("收货人", "收件人", "联系人", "持卡人", "真实姓名", "姓名", "户名", "开户名", "Name", "Recipient"),
            value = NAME_VALUE,
            confidence = 0.8f,
        ),
    )

    /**
     * 地址。**不能用姓名那套「遇数字即停」**——门牌号、楼层、邮编本来就是地址的一部分。
     * 所以取到行尾，上限 60 字符；首尾都卡非空白，免得把标签后的空格也遮进去。
     *
     * 因此「收货地址 …… 电话 138……」会把电话一起遮掉。这是过遮，方向是安全的
     * （spec §13：多遮一块只是麻烦，漏遮一块是事故），何况电话本来也该遮。
     */
    private val ADDRESS_VALUE = Regex("""\S.{2,58}\S""")

    private val ADDRESS = CompositeRule(
        id = "address",
        kind = SensitiveKind.POSTAL_ADDRESS,
        enabledByDefault = true,
        LabeledField(
            labels = listOf(
                "收货地址", "收件地址", "寄送地址", "送货地址", "详细地址", "联系地址",
                "家庭住址", "住址", "地址", "Address",
            ),
            value = ADDRESS_VALUE,
            confidence = 0.8f,
        ),
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

    /**
     * 长数字串兜底（spec §15 第 5 条的实测漏检）。
     *
     * 实测：真实淘宝订单页上的 28 位支付宝交易号不被任何规则命中——
     * CARD 限 13–19 位、TRACKING 限 12/15/20/22 位，28 位落在所有窗口之外。
     * 漏检是事故，所以补一条不看语义、只看长度的兜底。
     *
     * 三条边界，都是为了不跟已有规则抢同一串数字（合并只在同 kind 之间做，
     * 抢起来会在同一处叠出两个候选）：
     * - 下限 16 位：E.164 电话最长 15 位，兜底与 PhoneRule 完全不重叠；
     * - 排除 20/22 位：那是 TRACKING 的 USPS 窗口；
     * - 排除 13–19 位里 Luhn 通过的：那是 CARD 的地盘。
     *   只在 CARD 的窗口内让位——别处 Luhn 碰巧通过（约十分之一）不该让兜底失灵。
     *
     * 没有任何校验位，误报天然多（时间戳拼接、纯序号都会撞上），
     * 因此按 §6「默认按误报率划线」默认仅圈出，靠导出拦截兜底。
     */
    private val LONG_NUMBER = object : Rule {
        override val id = "longnum"
        override val kind = SensitiveKind.LONG_NUMBER
        override val enabledByDefault = false

        private val run = Regex("""(?<![A-Za-z0-9])\d{16,}(?![A-Za-z0-9])""")
        private val trackingLengths = setOf(20, 22)

        override fun findIn(text: String): List<RuleMatch> =
            run.findAll(text)
                .filter { m ->
                    val v = m.value
                    when {
                        v.length in trackingLengths -> false
                        v.length in 13..19 && Checksums.luhn(v) -> false
                        else -> true
                    }
                }
                .map { RuleMatch(it.range, 0.4f) }      // 无校验位 → 低置信度
                .toList()
    }

    val rules: List<Rule> = listOf(
        CARD, IBAN, SSN, MAC, EMAIL, PhoneRule(), PASSPORT, API_KEY, NAME, ADDRESS,
        URL, IP, TRACKING, LONG_NUMBER, DateTimeRule(),
    )
}
