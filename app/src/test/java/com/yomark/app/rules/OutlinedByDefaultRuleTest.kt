package com.yomark.app.rules

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
    // spec §6 的 11 条
    //   + 计划 04 按 §15 第 5 条的实测漏检补的 longnum 兜底
    //   + 2026-09-04 增补 §3 按真机漏检补的 datetime
    //   + 标签锚定的 name / address（见 LabeledField）——原先它们标着
    //     「v2 需要 NER，首版不产出」，真机上表现为姓名地址完全漏检
    //   + 菜鸟快递详情页实测漏检补的 pickup（取件码）
    // 滴滴出票页实测漏检补的「从字面认人名」不单独成条，并在 name 里（见 PersonNameRecognizer）
    @Test fun `the rule set has the eleven spec rules plus the five field-added ones`() {
        assertThat(DefaultRuleSet.rules.map { it.id }).containsExactly(
            "card", "iban", "ssn", "mac", "email", "phone", "passport", "apikey", "name", "address",
            "pickup", "url", "ip", "tracking", "longnum", "datetime",
        )
    }

    /**
     * name / address / pickup 加在默认打码一侧：标签锚定的误报率低——「收货地址」四个字后面那一段
     * 几乎必然是地址，符合 §6「按误报率划线」。name 里从字面认出的那部分例外，只圈不打码，
     * 见 PersonNameRecognizerTest。
     */
    @Test fun `exactly eleven rules are masked by default and five are outlined`() {
        val (masked, outlined) = DefaultRuleSet.rules.partition { it.enabledByDefault }
        assertThat(masked).hasSize(11)
        assertThat(outlined.map { it.id })
            .containsExactly("url", "ip", "tracking", "longnum", "datetime")
    }

    /**
     * 设置页上一条规则一行、行名就是类型名。同一类型出现两条规则，设置页上就有两行同名的开关——
     * 「人名」与「人名（无字段名）」就是这么让人分不清关的是哪一个的。几种认法要合成一条（CompositeRule）。
     */
    @Test fun `every kind has exactly one rule and so one settings row`() {
        val kinds = DefaultRuleSet.rules.map { it.kind }
        assertThat(kinds).containsNoDuplicates()
        assertThat(RuleCatalog.all.map { RuleCatalog.label(it) }).containsNoDuplicates()
    }
}
