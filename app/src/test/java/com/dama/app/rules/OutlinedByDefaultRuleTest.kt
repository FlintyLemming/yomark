package com.dama.app.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutlinedByDefaultRuleTest {

    private fun rule(id: String) = DefaultRuleSet.rules.first { it.id == id }
    private fun masked(id: String, text: String) =
        rule(id).findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    // ---------- URL：只遮 path 与 query ----------
    @Test fun `url keeps the domain and masks the path`() {
        assertThat(masked("url", "see https://app.com/r/AbCdEf123 now")).containsExactly("/r/AbCdEf123")
    }

    @Test fun `url masks the query string too`() {
        assertThat(masked("url", "https://app.com/x?token=secret123")).containsExactly("/x?token=secret123")
    }

    @Test fun `a bare domain with no path yields nothing to mask`() {
        assertThat(masked("url", "visit https://example.com")).isEmpty()
    }

    @Test fun `url without a scheme is still matched`() {
        assertThat(masked("url", "go to app.com/r/Zz9")).containsExactly("/r/Zz9")
    }

    @Test fun `url is outlined by default`() {
        assertThat(rule("url").enabledByDefault).isFalse()
    }

    // ---------- IP ----------
    @Test fun `ipv4 matches and range-checks each octet`() {
        assertThat(masked("ip", "host 192.168.1.10 up")).containsExactly("192.168.1.10")
        assertThat(masked("ip", "bad 999.1.1.1")).isEmpty()
    }

    @Test fun `ipv6 is matched`() {
        assertThat(masked("ip", "addr 2001:0db8:85a3:0000:0000:8a2e:0370:7334")).hasSize(1)
    }

    @Test fun `a version number is not an ip address`() {
        assertThat(masked("ip", "app v1.2.3.4 released")).isEmpty()
        assertThat(masked("ip", "version 10.0.19041.1")).isEmpty()
    }

    @Test fun `ip is outlined by default`() {
        assertThat(rule("ip").enabledByDefault).isFalse()
    }

    // ---------- 快递单号 ----------
    @Test fun `ups tracking with a valid check digit is matched`() {
        assertThat(masked("tracking", "ships 1Z12345E0205271688 today")).containsExactly("1Z12345E0205271688")
    }

    @Test fun `ups tracking with a broken check digit is rejected`() {
        assertThat(masked("tracking", "1Z12345E0205271689")).isEmpty()
    }

    @Test fun `checksumless carrier formats are matched with lower confidence`() {
        val fedex = rule("tracking").findIn("track 123456789012 now")
        assertThat(fedex).hasSize(1)
        assertThat(fedex.single().confidence).isLessThan(0.7f)
    }

    @Test fun `tracking is outlined by default`() {
        assertThat(rule("tracking").enabledByDefault).isFalse()
    }

    // ---------- 规则表完整性 ----------
    @Test fun `the rule set has exactly the eleven spec rules`() {
        assertThat(DefaultRuleSet.rules.map { it.id }).containsExactly(
            "card", "iban", "ssn", "mac", "email", "phone", "passport", "apikey",
            "url", "ip", "tracking",
        )
    }

    @Test fun `exactly eight rules are masked by default and three are outlined`() {
        val (masked, outlined) = DefaultRuleSet.rules.partition { it.enabledByDefault }
        assertThat(masked).hasSize(8)
        assertThat(outlined.map { it.id }).containsExactly("url", "ip", "tracking")
    }
}
