package com.youma.app.rules

import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 姓名与地址（真机实测漏检）。`Sensitivity.kt` 原本把 PERSON_NAME / POSTAL_ADDRESS
 * 标成「v2：需要 NER，首版不产出」，所以这两类在 0.2.0 上**一条规则都没有**。
 *
 * 这里走的不是 NER，是**标签锚定**：先认「收货人」「收货地址」这类字段名，再取紧随其后的值。
 * 覆盖面因此止于有字段名的版面——订单页、快递单、证件——
 * **聊天记录里随口提到的人名地址抓不到**，那仍然需要 NER。
 */
class LabeledFieldRuleTest {

    private fun matched(id: String, text: String): List<String> =
        DefaultRuleSet.rules.first { it.id == id }
            .findIn(text)
            .map { text.substring(it.range.first, it.range.last + 1) }

    // ---------- 人名 ----------
    /** 遮的是值，不是字段名——把「收货人」三个字也涂黑，用户就看不懂自己在看什么了。 */
    @Test fun `name takes the value after the label and not the label itself`() {
        assertThat(matched("name", "收货人 张三")).containsExactly("张三")
        assertThat(matched("name", "收货人：李四光")).containsExactly("李四光")
    }

    /** 中文识别器常把相邻汉字拆成独立 element，拼接后成了「张 三」。 */
    @Test fun `name survives ocr splitting the characters apart`() {
        assertThat(matched("name", "收件人 张 三")).containsExactly("张 三")
    }

    /** 同一行的电话归 PhoneRule 管，姓名规则不能顺手把它吞进来。 */
    @Test fun `name stops before the digits that follow it`() {
        assertThat(matched("name", "收货人 张三 13812345678")).containsExactly("张三")
    }

    @Test fun `name ignores text with no label`() {
        assertThat(matched("name", "张三去了北京")).isEmpty()
    }

    // ---------- 地址 ----------
    /** 地址天然含数字（门牌号），不能用姓名那套「遇数字即停」。 */
    @Test fun `address keeps the house number`() {
        assertThat(matched("address", "收货地址 北京市朝阳区建国路 88 号"))
            .containsExactly("北京市朝阳区建国路 88 号")
    }

    @Test fun `address ignores text with no label`() {
        assertThat(matched("address", "北京市朝阳区建国路 88 号")).isEmpty()
    }

    /**
     * 「IP地址」「MAC地址」是界面上极常见的文案，它们里的「地址」不是邮寄地址字段名。
     * 词中词的否定后顾原先只挡汉字，挡不住拉丁前缀。
     */
    @Test fun `address ignores a label that is part of a longer latin prefixed word`() {
        assertThat(matched("address", "IP地址 192.168.1.10")).isEmpty()
        assertThat(matched("address", "MAC地址 00:1B:44:11:3A:B7")).isEmpty()
    }

    /** 同理「邮箱地址」——这条靠汉字后顾已经挡住了，一并钉死免得回归。 */
    @Test fun `address ignores a label that is part of a longer chinese word`() {
        assertThat(matched("address", "邮箱地址 alice@example.com")).isEmpty()
    }

    // ---------- 出厂态 ----------
    @Test fun `both are registered with the right kinds and masked by default`() {
        val name = DefaultRuleSet.rules.first { it.id == "name" }
        val address = DefaultRuleSet.rules.first { it.id == "address" }
        assertThat(name.kind).isEqualTo(SensitiveKind.PERSON_NAME)
        assertThat(address.kind).isEqualTo(SensitiveKind.POSTAL_ADDRESS)
        assertThat(name.enabledByDefault).isTrue()
        assertThat(address.enabledByDefault).isTrue()
    }
}
