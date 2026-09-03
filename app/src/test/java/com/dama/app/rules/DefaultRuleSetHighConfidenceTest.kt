package com.dama.app.rules

import com.dama.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DefaultRuleSetHighConfidenceTest {

    private fun rule(id: String): Rule =
        DefaultRuleSet.rules.first { it.id == id }

    private fun matched(id: String, text: String): List<String> =
        rule(id).findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    // ---------- 支付卡 ----------
    @Test fun `card matches plain grouped and hyphenated numbers`() {
        assertThat(matched("card", "pay 4111111111111111 now")).containsExactly("4111111111111111")
        assertThat(matched("card", "4111 1111 1111 1111")).containsExactly("4111 1111 1111 1111")
        assertThat(matched("card", "4111-1111-1111-1111")).containsExactly("4111-1111-1111-1111")
    }

    @Test fun `card rejects a luhn failure`() {
        assertThat(matched("card", "4111111111111112")).isEmpty()
    }

    @Test fun `card rejects a phone-length digit run`() {
        assertThat(matched("card", "call 4155551234")).isEmpty()      // 10 位，长度不够
    }

    @Test fun `card is masked by default`() {
        assertThat(rule("card").enabledByDefault).isTrue()
        assertThat(rule("card").kind).isEqualTo(SensitiveKind.PAYMENT_CARD)
    }

    // ---------- IBAN ----------
    @Test fun `iban matches with and without spaces`() {
        assertThat(matched("iban", "IBAN DE89370400440532013000 ok")).containsExactly("DE89370400440532013000")
        assertThat(matched("iban", "DE89 3704 0044 0532 0130 00")).containsExactly("DE89 3704 0044 0532 0130 00")
    }

    @Test fun `iban rejects a bad check digit`() {
        assertThat(matched("iban", "DE88370400440532013000")).isEmpty()
    }

    // ---------- SSN ----------
    @Test fun `ssn matches a valid number and rejects invalid segments`() {
        assertThat(matched("ssn", "SSN 123-45-6789")).containsExactly("123-45-6789")
        assertThat(matched("ssn", "666-45-6789")).isEmpty()
        assertThat(matched("ssn", "123-00-6789")).isEmpty()
    }

    // ---------- MAC ----------
    @Test fun `mac matches consistent separators only`() {
        assertThat(matched("mac", "mac 00:1A:2B:3C:4D:5E")).containsExactly("00:1A:2B:3C:4D:5E")
        assertThat(matched("mac", "00-1A-2B-3C-4D-5E")).containsExactly("00-1A-2B-3C-4D-5E")
        assertThat(matched("mac", "00:1A-2B:3C-4D:5E")).isEmpty()
    }

    @Test fun `all four high-confidence rules default to masked`() {
        listOf("card", "iban", "ssn", "mac").forEach {
            assertThat(rule(it).enabledByDefault).isTrue()
        }
    }
}
