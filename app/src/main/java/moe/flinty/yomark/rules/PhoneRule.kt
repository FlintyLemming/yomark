package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
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
 *
 * 另认一种 libphonenumber 认不了的：**打了星的手机号**，见 MASKED_MOBILE。
 */
class PhoneRule(
    private val defaultRegion: String = Locale.getDefault().country.ifBlank { "US" },
) : Rule {

    override val id = "phone"
    override val kind = SensitiveKind.PHONE
    override val enabledByDefault = true

    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    override fun findIn(text: String): List<RuleMatch> {
        val numbers = util.findNumbers(text, defaultRegion, PhoneNumberUtil.Leniency.VALID, Long.MAX_VALUE)
            .asSequence()
            .filter { util.isValidNumber(it.number()) }
            // 带 + 的不会是订单号，提示词管不着它
            .filter { it.rawString().trimStart().startsWith("+") || !precededByHint(text, it.start()) }
            .map { RuleMatch(it.start() until it.end(), CONFIDENCE) }
            .toList()
        val masked = MASKED_MOBILE.findAll(text)
            .filter { looksLikeMaskedMobile(it.value) }
            .map { RuleMatch(it.range, MASKED_CONFIDENCE) }
            .filter { m -> numbers.none { it.range.overlaps(m.range) } }
        return numbers + masked
    }

    /**
     * 号码**前面那一小段**里有没有提示词。窗口取 12 个字符，够装下
     * 「订单编号：」和「Order no. 」，又短到「订单号 …… 收货人电话 」这种
     * 一行两个字段的排版不会互相牵连——与 DefaultRuleSet 判断 IP 版本号前缀同一个口径。
     */
    private fun precededByHint(text: String, start: Int): Boolean {
        val window = text.substring(0, start).takeLast(HINT_WINDOW).lowercase()
        return ORDER_HINTS.any { window.contains(it) }
    }

    /** 去掉国家码之后，数字与星号合计 11 位上下，星号至少 3 个——大陆手机号打码后的样子。 */
    private fun looksLikeMaskedMobile(value: String): Boolean {
        val body = value.substring(MOBILE_START.find(value)!!.range.first)
        val masks = body.count { it in MASKS }
        val digits = body.count { it.isDigit() }
        return masks >= 3 && masks + digits in 10..12
    }

    private companion object {
        const val CONFIDENCE = 0.9f
        const val MASKED_CONFIDENCE = 0.85f
        const val HINT_WINDOW = 12
        val ORDER_HINTS = listOf(
            "order", "invoice", "receipt", "ref no", "ref.", "tracking",
            "订单", "单号", "流水", "发票",
        )

        const val MASKS = "*＊•●xX"

        /**
         * 打了星的大陆手机号：「186****3392」「86-186****3392」「+86 138 **** 5678」。
         *
         * 电商、快递页面上的收件人电话几乎都是这个样子（菜鸟的「号码保护中」），
         * libphonenumber 不认带星号的串，于是真机上这一类**整类漏检**。露出来的前三后四
         * 照样能配合姓名把人定位出来，所以它仍然是电话，仍然默认打码。
         *
         * 形状卡得很死：1[3-9] 开头，星号连成一段；位数交给 looksLikeMaskedMobile 查。
         * 国家码一起遮，框才是完整的一块。星号之间容忍单个空格：中文识别器可能把它们拆成几个 element。
         */
        val MASKED_MOBILE = Regex(
            """(?<![0-9A-Za-z*＊])(?:(?:[+＋]\s?)?86\s?[-－]?\s?)?1[3-9]\d{0,5}\s?""" +
                """[*＊•●xX](?:\s?[*＊•●xX]){2,7}(?:\s?\d{1,4})?(?![0-9A-Za-z*＊])"""
        )
        val MOBILE_START = Regex("""1[3-9]""")
    }
}
