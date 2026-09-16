package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * 电话号码（spec §6）：交给 libphonenumber 的 findNumbers，正则做不了这件事。
 *
 * 校验 = isValidNumber，外加排除明显的订单号：提示词（order / invoice / 订单 / 单号 等）
 * **紧挨着**的那个号码，若无国际前缀则丢弃。
 *
 * 压制按邻近判断而不是按整行，是因为行级压制在 0.2.0 上造成了真实漏检。
 * 这条规则原先写的是「整行含提示词就全丢」，当时成立的前提是首版只有 ML Kit
 * bundled 拉丁识别器、中文标签会被识别成乱码，所以中文提示词根本不会命中（spec §15 第 5 条）。
 * bc04d7a 把出厂默认改成 TextEngineOption.BOTH、中文识别器上线之后这个前提没了：
 * 「订单」真的被认出来，于是订单页上与订单号同处一行的收货人电话被整行压制吞掉。
 *
 * 中文识别器还倾向于把表单的一整行合成一条 TextLine，标签与号码同行是常态而非例外，
 * 所以这里必须是窗口判断。
 */
class PhoneRule(
    private val defaultRegion: String = Locale.getDefault().country.ifBlank { "US" },
) : Rule {

    override val id = "phone"
    override val kind = SensitiveKind.PHONE
    override val enabledByDefault = true

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    override fun findIn(text: String): List<RuleMatch> =
        util.findNumbers(text, defaultRegion, PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE)
            .asSequence()
            .filter { util.isValidNumber(it.number()) }
            // 带 + 的不会是订单号，提示词管不着它
            .filter { it.rawString().trimStart().startsWith("+") || !precededByHint(text, it.start()) }
            .map { RuleMatch(it.start() until it.end(), CONFIDENCE) }
            .toList()

    /**
     * 号码**前面那一小段**里有没有提示词。窗口取 12 个字符，够装下
     * 「订单编号：」和「Order no. 」，又短到「订单号 …… 收货人电话 」这种
     * 一行两个字段的排版不会互相牵连——与 DefaultRuleSet 判断 IP 版本号前缀同一个口径。
     */
    private fun precededByHint(text: String, start: Int): Boolean {
        val window = text.substring(0, start).takeLast(HINT_WINDOW).lowercase()
        return ORDER_HINTS.any { window.contains(it) }
    }

    private companion object {
        const val CONFIDENCE = 0.9f
        const val HINT_WINDOW = 12
        val ORDER_HINTS = listOf(
            "order", "invoice", "receipt", "ref no", "ref.", "tracking",
            "订单", "单号", "流水", "发票",
        )
    }
}
