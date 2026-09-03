package com.dama.app.rules

import com.dama.app.core.model.SensitiveKind
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
