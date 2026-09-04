package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind

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
 * 日期与紧随其后的时间**合成一个匹配**。拆成两条会在同一处叠出两个候选，
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
        val nums = NUMBER.findAll(value).map { it.value.toInt() }.toList()
        if (value.first().isDigit() && value.length >= 8 && nums.size >= 3) {
            val (_, month, day) = nums
            if (month !in 1..12 || day !in 1..31) return false
        }
        return true
    }

    private companion object {
        const val CONFIDENCE = 0.6f

        /** 2026-09-03 / 2026/9/3 / 2026.9.3 / 2026年9月3日 */
        const val DATE = """(?:\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{4}年\d{1,2}月\d{1,2}日)"""

        /** 时:分(:秒)，小时与分钟用字符类卡死范围，所以 88:12 根本不成形 */
        const val TIME = """(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?"""

        val PATTERN = Regex("""(?<![\d:.])(?:$DATE(?:[ T]$TIME)?|$TIME)(?![\d:])""")
        val NUMBER = Regex("""\d+""")
    }
}
