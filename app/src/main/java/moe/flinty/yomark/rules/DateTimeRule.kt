package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind

/**
 * 日期与时间（2026-09-04 增补设计 §3）。
 *
 * 真机实测的漏检：订单页的「创建时间 2026-09-03 12:08:06」不被任何规则命中——
 * OCR 认得出，是判定层没人管。
 *
 * **出厂仅圈出**：时间在截图里无处不在（状态栏时钟、每一条聊天记录），
 * 默认打码会让用户一直在跟 app 对着干。这是 spec §6「按误报率划线，
 * 不按危害划线」的直接应用。
 *
 * 日期与紧随其后的时间**合成一个匹配**（含不带年份的「09-29 12:30」）。拆成两条会在同一处叠出两个候选，
 * 而合并只在同 kind 之间做，用户就得点两次才能放过一个时间戳。
 *
 * 英文的写法（2026-10，护照样张上的「05 FEB 1965」一个都没认出来）：「Feb 5, 1965」「5 February 1965」、
 * 四位年的「02/05/1965」，时间带「10:30 AM」。写法表在 [Dates]。
 * 出生日期另有一类（「Date of birth」下面那个），同一处它让给出生日期；取件码「3-2-2025」也让。
 */
class DateTimeRule : Rule {

    override val id = "datetime"
    override val kind = SensitiveKind.DATETIME
    override val enabledByDefault = false
    override val yieldsTo = setOf(SensitiveKind.BIRTH_DATE, SensitiveKind.PICKUP_CODE)

    override fun findIn(text: String): List<RuleMatch> =
        PATTERN.findAll(text)
            .filter { inRange(it.value) }
            .map { RuleMatch(it.range, CONFIDENCE) }
            .toList()

    /**
     * 正则只保证形状，范围要另外查：`2026-19-03` 形状对但没有 19 月，
     * `88:12` 会被时间分支的字符类挡掉，但月/日没有同样的字符类可用。
     */
    private fun inRange(value: String): Boolean {
        DATE_FIRST.find(value)?.let { return Dates.readings(it.value).isNotEmpty() }
        // 不能用数字切：「09-2912:30」里日与时粘在一起，会切出 2912
        MONTH_DAY_FIRST.find(value)?.let { return it.groupValues[1].toInt() in 1..12 && it.groupValues[2].toInt() in 1..31 }
        MONTH_NAME_DAY_FIRST.find(value)?.let { return it.groupValues[1].toInt() in 1..31 }
        return true
    }

    private companion object {
        const val CONFIDENCE = 0.6f

        /** 时:分(:秒)，小时与分钟用字符类卡死范围，所以 88:12 根本不成形。英文界面上常带「AM」「p.m.」 */
        const val TIME = """(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?(?: ?[AaPp]\.?[Mm]\.?(?![A-Za-z]))?"""

        /**
         * 不带年份的「09-29 12:30」：物流轨迹、聊天记录里最常见的写法。
         * 月日单独出现太容易撞上别的（比分、编号），所以只认后面跟着时间的。
         * 时间前的空格可有可无——PP-OCR 按像素补空格，窄间隙会被当成没有，读成「09-2912:30」。
         * 英文的「Oct 28」同理，后面跟着时间才算。
         */
        const val MONTH_DAY = """\d{1,2}[-/]\d{1,2}"""

        /** 日期与时间之间：空格、T，英文还写「Sep 28, 2026, 10:30 AM」「Oct 28 at 9:41」。 */
        const val JOIN = """(?:[ T]|,? (?:at )?)"""

        val PATTERN = Regex(
            """(?<![\d:.])(?:${Dates.STRICT}(?:$JOIN$TIME)?|$MONTH_DAY ?$TIME|${Dates.MONTH_DAY}$JOIN$TIME|$TIME)(?![\d:])"""
        )
        val DATE_FIRST = Regex("""^${Dates.STRICT}""")
        val MONTH_DAY_FIRST = Regex("""^(\d{1,2})[-/](\d{1,2})(?= ?$TIME)""")
        val MONTH_NAME_DAY_FIRST = Regex("""^${Dates.MONTH} ?(\d{1,2})""")
    }
}
