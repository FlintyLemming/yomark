package com.yomark.app.rules

import com.yomark.app.core.model.SensitiveKind

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
 */
class DateTimeRule : Rule {

    override val id = "datetime"
    override val kind = SensitiveKind.DATETIME
    override val enabledByDefault = false

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
        val monthDay = MONTH_DAY_FIRST.find(value)
        val (month, day) = when {
            YEAR_FIRST.containsMatchIn(value) -> NUMBER.findAll(value).map { it.value.toInt() }.toList()
                .takeIf { it.size >= 3 }?.let { it[1] to it[2] } ?: return true
            // 不能用 NUMBER 切：「09-2912:30」里日与时粘在一起，会切出 2912
            monthDay != null -> monthDay.groupValues[1].toInt() to monthDay.groupValues[2].toInt()
            else -> return true
        }
        return month in 1..12 && day in 1..31
    }

    private companion object {
        const val CONFIDENCE = 0.6f

        /** 2026-09-03 / 2026/9/3 / 2026.9.3 / 2026年9月3日 */
        const val DATE = """(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日)"""

        /** 时:分(:秒)，小时与分钟用字符类卡死范围，所以 88:12 根本不成形 */
        const val TIME = """(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?"""

        /**
         * 不带年份的「09-29 12:30」：物流轨迹、聊天记录里最常见的写法。
         * 月日单独出现太容易撞上别的（比分、编号），所以只认后面跟着时间的。
         * 时间前的空格可有可无——PP-OCR 按像素补空格，窄间隙会被当成没有，读成「09-2912:30」。
         */
        const val MONTH_DAY = """\d{1,2}[-/]\d{1,2}"""

        val PATTERN = Regex("""(?<![\d:.])(?:$DATE(?:[ T]$TIME)?|$MONTH_DAY ?$TIME|$TIME)(?![\d:])""")
        val NUMBER = Regex("""\d+""")
        val YEAR_FIRST = Regex("""^\d{4}""")
        val MONTH_DAY_FIRST = Regex("""^(\d{1,2})[-/](\d{1,2})(?= ?$TIME)""")
    }
}
