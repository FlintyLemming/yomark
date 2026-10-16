package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.DATETIME
import moe.flinty.yomark.core.model.SensitiveKind.LONG_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.PHONE
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 长数字串兜底：至少 8 位数字连成的一串，中间可以夹着连字符和大写字母。
 *
 * 起初（spec §15 第 5 条实测到的漏检：真实淘宝订单页上 28 位的支付宝交易号
 * `2026090323001114571431156787` 不被任何规则命中）只认 16 位以上的纯数字；
 * 2026-10-16 起快递单号并进来，下限降到 8 位。
 *
 * 它没有任何校验位，误报天然多，出厂关着。同一处别的类型认出了东西它就让开；
 * 日期和过得了 Luhn 的卡号，那一类开没开都让。
 */
@RunWith(RobolectricTestRunner::class)
class LongNumberRuleTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "longnum" }

    private fun matched(text: String) = rule.findIn(text).map { text.substring(it.range) }

    @Test
    fun `the alipay transaction number from the spec is caught`() {
        assertThat(matched("交易号 2026090323001114571431156787"))
            .containsExactly("2026090323001114571431156787")
    }

    @Test
    fun `the taobao order number from the spec is caught`() {
        // 19 位，Luhn 不通过，card 规则已正确排除它——但它仍是一串标识符
        assertThat(matched("订单号 5127402746064028833"))
            .containsExactly("5127402746064028833")
    }

    @Test
    fun `it is off by default`() {
        assertThat(rule.enabledByDefault).isFalse()
        assertThat(rule.kind).isEqualTo(SensitiveKind.LONG_NUMBER)
    }

    /** 原先快递单号一类认的 UPS、FedEx 12 位、USPS 22 位，和它不认的申通 15 位、顺丰、圆通、EMS。 */
    @Test
    fun `tracking numbers are long numbers`() {
        assertThat(matched("ships 1Z12345E0205271688 today")).containsExactly("1Z12345E0205271688")
        assertThat(matched("track 123456789012 now")).containsExactly("123456789012")
        assertThat(matched("9400111899223197428490")).containsExactly("9400111899223197428490")
        assertThat(matched("申通快递 773443899958908 复制")).containsExactly("773443899958908")
        assertThat(matched("顺丰速运 SF1234567890123")).containsExactly("SF1234567890123")
        assertThat(matched("运单号：YT1234567890123")).containsExactly("YT1234567890123")
        assertThat(matched("EMS EA123456789CN")).containsExactly("EA123456789CN")
    }

    @Test
    fun `hyphens and capital letters do not break the run`() {
        assertThat(matched("订单编号 231016-091234567890123")).containsExactly("231016-091234567890123")
        assertThat(matched("Order # 123-4567890-1234567")).containsExactly("123-4567890-1234567")
        // 头尾的连字符不算进去
        assertThat(matched("单号-12345678-")).containsExactly("12345678")
    }

    @Test
    fun `eight digits are enough and seven are not`() {
        assertThat(matched("QQ 12345678")).containsExactly("12345678")
        assertThat(matched("会员号 1234567")).isEmpty()
        // 字母、连字符不算位数
        assertThat(matched("COVID-19 WPA2-PSK AB12-CD34-EF56")).isEmpty()
    }

    @Test
    fun `a valid card number is left to the card rule`() {
        // Luhn 通过且落在 card 的 13-19 位窗口内 → 兜底规则让位，不重复出候选
        assertThat(matched("4111111111111111")).isEmpty()
        assertThat(matched("4111-1111-1111-1111")).isEmpty()
    }

    /** 日期时间出厂关着，不能从这里遮上。连写的八位数不当日期：单独一串八位数什么都可能是（见 Dates）。 */
    @Test
    fun `dates are not long numbers`() {
        assertThat(matched("下单时间 2026-10-16 14:05:33")).isEmpty()
        assertThat(matched("16-10-2026")).isEmpty()
        assertThat(matched("2026-10-16T14:05:33Z")).isEmpty()
        assertThat(matched("20261016")).containsExactly("20261016")
    }

    @Test
    fun `short numbers are ignored`() {
        assertThat(matched("Total 42.50 USD")).isEmpty()
        assertThat(matched("order 12345")).isEmpty()
    }

    /** 挨着小写字母、下划线的是哈希、变量名、文件名，不是编号。 */
    @Test
    fun `a digit run embedded in a longer word is not matched`() {
        assertThat(matched("abc2026090323001114571431156787def")).isEmpty()
        assertThat(matched("sha256 e3b0c44298fc1c149afbf4c8996fb924")).isEmpty()
        assertThat(matched("IMG_20260312_100512.jpg")).isEmpty()
    }

    @Test
    fun `spaces break the run so grouped numbers are not merged`() {
        assertThat(matched("1234 5678 9012 3456 7890")).isEmpty()
    }

    // ---------- 整页：同一处别的类型认出了东西，它让开 ----------

    private suspend fun found(rules: List<Rule>, vararg texts: String): List<Pair<SensitiveKind, String>> {
        val lines = textLines(*texts)
        return RuleClassifier(rules).classify(lines).map { it.kind to it.textIn(lines) }
    }

    @Test
    fun `a phone number is left to the phone rule`() = runTest {
        assertThat(found(DefaultRuleSet.rules, "电话 13812345678")).containsExactly(PHONE to "13812345678")
    }

    /** 紧挨着「订单号」的不算电话（见 PhoneRule），落到这里。 */
    @Test
    fun `an order number shaped like a phone number is a long number`() = runTest {
        assertThat(found(DefaultRuleSet.rules, "订单号 13812345678")).containsExactly(LONG_NUMBER to "13812345678")
    }

    /** 只打开了长数字串、日期时间照出厂关着：订单号、快递单号遮，下单时间不遮。 */
    @Test
    fun `only long numbers turned on leaves the dates alone`() = runTest {
        val rules = RuleCatalog.rulesFor(RecognitionConfig(ruleOverrides = mapOf("longnum" to RuleState.MASKED)))
        val page = found(rules, "订单编号 2026092912304518839201746355", "下单时间 2026-09-29 12:30:45", "顺丰速运 SF1234567890123")
        assertThat(page.filter { it.first == LONG_NUMBER || it.first == DATETIME })
            .containsExactly(LONG_NUMBER to "2026092912304518839201746355", LONG_NUMBER to "SF1234567890123")
    }
}
