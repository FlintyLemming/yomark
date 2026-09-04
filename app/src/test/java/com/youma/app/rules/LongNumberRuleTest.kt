package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 长数字串兜底（spec §15 第 5 条实测到的漏检）。
 *
 * 实测：真实淘宝订单页上的 28 位支付宝交易号
 * `2026090323001114571431156787` 不被任何规则命中——
 * card 限 13–19 位、tracking 限 12/15/20/22 位，28 位落在所有窗口之外。
 * 「漏检是事故」，所以补一条不看语义、只看长度的兜底。
 *
 * 它没有任何校验位，误报天然多（时间戳拼接、版本号、纯序号都会撞上），
 * 因此按 §6「默认按误报率划线」的口径**默认仅圈出**，靠导出拦截兜底。
 */
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
    fun `it is outlined by default not masked`() {
        assertThat(rule.enabledByDefault).isFalse()
        assertThat(rule.kind).isEqualTo(SensitiveKind.LONG_NUMBER)
    }

    @Test
    fun `a valid card number is left to the card rule`() {
        // Luhn 通过且落在 card 的 13-19 位窗口内 → 兜底规则让位，不重复出候选
        assertThat(matched("4111111111111111")).isEmpty()
    }

    @Test
    fun `tracking number lengths are left to the tracking rule`() {
        assertThat(matched("9400111899223197428490")).isEmpty()      // 22 位 USPS
        assertThat(matched("94001118992231974284")).isEmpty()        // 20 位 USPS
    }

    @Test
    fun `phone-length digit runs never reach this rule`() {
        // E.164 最长 15 位，兜底的下限是 16，两者不重叠
        assertThat(matched("861380013800")).isEmpty()                // 12 位
        assertThat(matched("861380013800123")).isEmpty()             // 15 位
    }

    @Test
    fun `short numbers are ignored`() {
        assertThat(matched("Total 42.50 USD")).isEmpty()
        assertThat(matched("order 12345")).isEmpty()
    }

    @Test
    fun `a digit run embedded in a longer token is not matched`() {
        assertThat(matched("abc2026090323001114571431156787def")).isEmpty()
    }

    @Test
    fun `separators break the run so grouped numbers are not merged`() {
        assertThat(matched("1234 5678 9012 3456 7890")).isEmpty()
    }
}
