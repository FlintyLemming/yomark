package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhoneAndEmailRuleTest {

    private fun matched(rule: Rule, text: String): List<String> =
        rule.findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    private val phone = PhoneRule(defaultRegion = "US")
    private val email = DefaultRuleSet.rules.first { it.id == "email" }

    // ---------- 电话 ----------
    @Test fun `phone finds an international number`() {
        assertThat(matched(phone, "call +1 415 555 2671 today")).contains("+1 415 555 2671")
    }

    @Test fun `phone finds a national number in the default region`() {
        assertThat(matched(phone, "call (415) 555-2671")).isNotEmpty()
    }

    @Test fun `phone rejects an invalid number`() {
        assertThat(matched(phone, "call 000 000 0000")).isEmpty()
    }

    @Test fun `phone rejects a national-format match on an order line`() {
        assertThat(matched(phone, "Order no. 4155552671")).isEmpty()
        assertThat(matched(phone, "订单号 4155552671")).isEmpty()
    }

    @Test fun `phone keeps an international number even on an order line`() {
        // 带 + 的号码不会是订单号
        assertThat(matched(phone, "Order 123, call +14155552671")).isNotEmpty()
    }

    /**
     * 0.2.0 引入的回归：ORDER_HINTS 是**行级**压制，整行里任何位置出现「订单」，
     * 这一行上所有不带 + 的号码全被丢掉。
     *
     * 这条保险丝原本是失效的——PhoneRule 的注释写明「中文标签会被识别成乱码，
     * 不能指望它在中文场景下起作用」。bc04d7a 把出厂默认改成 BOTH、中文识别器上线之后
     * 它突然通电，于是订单页上与订单号同处一行的收货人电话被整个吞掉。
     *
     * 压制必须按**邻近**判断：提示词只压紧挨着它的那个号码。
     */
    @Test fun `phone survives when the order hint belongs to a different number on the line`() {
        val line = "订单号 202609031234567890 收货人电话 4155552671"
        assertThat(matched(phone, line)).contains("4155552671")
    }

    /** 邻近压制不能把老行为放走：紧跟提示词的那个号码仍然要被丢掉。 */
    @Test fun `phone still rejects the number directly after an order hint`() {
        assertThat(matched(phone, "订单号 4155552671 备注 无")).isEmpty()
    }

    @Test fun `phone is masked by default`() {
        assertThat(phone.enabledByDefault).isTrue()
        assertThat(phone.kind).isEqualTo(SensitiveKind.PHONE)
    }

    // ---------- 邮箱 ----------
    @Test fun `email matches common addresses`() {
        assertThat(matched(email, "write to alice@example.com now")).containsExactly("alice@example.com")
        assertThat(matched(email, "a.b+tag@sub.example.co.uk")).containsExactly("a.b+tag@sub.example.co.uk")
    }

    @Test fun `email rejects an unknown tld`() {
        assertThat(matched(email, "bogus@example.zzzz")).isEmpty()
    }

    @Test fun `email rejects a bare at sign`() {
        assertThat(matched(email, "meet @alice at noon")).isEmpty()
    }

    @Test fun `email is masked by default`() {
        assertThat(email.enabledByDefault).isTrue()
        assertThat(email.kind).isEqualTo(SensitiveKind.EMAIL)
    }
}
