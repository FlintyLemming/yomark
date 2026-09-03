package com.dama.app.rules

import com.dama.app.core.model.SensitiveKind
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/**
 * 电话号码（spec §6）：交给 libphonenumber 的 findNumbers，正则做不了这件事。
 *
 * 校验 = isValidNumber，外加排除明显的订单号：
 * 同一行出现 order / invoice / 订单 / 单号 等提示词时，无国际前缀的匹配一律丢弃。
 *
 * 注意（spec §15 第 5 条实测）：首版用的是 ML Kit bundled 拉丁识别器，中文标签会被识别成乱码，
 * 所以 ORDER_HINTS 里的中文词在真实中文页面上不会命中。留着它们是为了兜住少数
 * 中英混排且中文恰好被识别出来的情况，不能指望它在中文场景下起作用。
 */
class PhoneRule(
    private val defaultRegion: String = Locale.getDefault().country.ifBlank { "US" },
) : Rule {

    override val id = "phone"
    override val kind = SensitiveKind.PHONE
    override val enabledByDefault = true

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    override fun findIn(text: String): List<RuleMatch> {
        val orderish = ORDER_HINTS.any { text.contains(it, ignoreCase = true) }
        return util.findNumbers(text, defaultRegion, PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE)
            .asSequence()
            .filter { util.isValidNumber(it.number()) }
            .filter { !orderish || it.rawString().trimStart().startsWith("+") }
            .map { RuleMatch(it.start() until it.end(), CONFIDENCE) }
            .toList()
    }

    private companion object {
        const val CONFIDENCE = 0.9f
        val ORDER_HINTS = listOf(
            "order", "invoice", "receipt", "ref no", "ref.", "tracking",
            "订单", "单号", "流水", "发票",
        )
    }
}
