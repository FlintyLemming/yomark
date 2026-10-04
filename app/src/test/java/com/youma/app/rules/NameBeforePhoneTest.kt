package com.youma.app.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 电话前面的名字（菜鸟快递详情页实测漏检）。
 *
 * 收件人一行是光秃秃的「沐晨冉 86-186****3392」，没有任何字段名，
 * 标签锚定对它无能为力。锚点换成电话：它前面那 2–4 个汉字几乎总是号码的主人。
 */
class NameBeforePhoneTest {

    private val nameRule = DefaultRuleSet.rules.first { it.id == "name" }

    /** 只看锚定出来的：人名规则里从字面认的那一种（只圈不打码）见 PersonNameRecognizerTest。 */
    private fun names(text: String): List<String> =
        nameRule.findIn(text).filterNot { it.outlineOnly }.map { text.substring(it.range) }

    /** libphonenumber 按地区校验，大陆手机号得用 CN 才认；打了星的号码不看地区。 */
    private val cn = NameBeforePhone(PhoneRule(defaultRegion = "CN"), confidence = 0.7f)

    private fun cnNames(text: String): List<String> = cn.findIn(text).map { text.substring(it.range) }

    @Test fun `the unlabelled recipient line of a logistics page`() {
        assertThat(names("沐晨冉 86-186****3392 号码保护中 取件出示虚拟号>")).containsExactly("沐晨冉")
    }

    @Test fun `the same line when the recognizer splits every character`() {
        assertThat(names("沐 晨 冉 86-186****3392 号 码 保 护 中")).containsExactly("沐 晨 冉")
    }

    @Test fun `a full number is an anchor too`() {
        assertThat(cnNames("张三 13812345678")).containsExactly("张三")
        assertThat(cnNames("张 三 13812345678")).containsExactly("张 三")
    }

    @Test fun `a colon comma or bracket may sit between name and number`() {
        assertThat(names("张三：186****3392")).containsExactly("张三")
        assertThat(names("王小明（186****3392）")).containsExactly("王小明")
    }

    /** 电话前面是字段名或说明，不是人名。 */
    @Test fun `field labels in front of a number are not names`() {
        listOf(
            "联系电话 186****3392",
            "手机号 186****3392",
            "客服热线 186****3392",
            "微信同号 186****3392",
            "收货人 186****3392",
        ).forEach { assertThat(names(it)).isEmpty() }
    }

    /** 电话前面是半句话：「已向」「来自」「我是」这些虚词代词从不出现在人名里。 */
    @Test fun `half a sentence in front of a number is not a name`() {
        listOf(
            "已向186****3392发送验证码",
            "来自186****3392的来电",
            "发送至186****3392",
            "我是186****3392",
        ).forEach { assertThat(names(it)).isEmpty() }
    }

    /** 通讯录里的称呼不是能定位到人的信息。 */
    @Test fun `a form of address is not a name`() {
        assertThat(names("妈妈 139****1111")).isEmpty()
        assertThat(names("紧急联系人 母亲 139****1111")).isEmpty()
    }

    /** 电话前面是一句话：长于 4 个字就不是名字。 */
    @Test fun `a sentence in front of a number is not a name`() {
        assertThat(names("如有疑问请联系 186****3392")).isEmpty()
    }

    @Test fun `a single character is not a name`() {
        assertThat(names("王 186****3392")).isEmpty()
    }

    @Test fun `no number no name`() {
        assertThat(names("沐晨冉 号码保护中")).isEmpty()
    }

    /** 标签锚定与电话锚定认出同一个名字时只出一个区间，否则合并后框的边界取决于谁猜得宽。 */
    @Test fun `a labelled name next to a number is reported once`() {
        assertThat(names("收货人 张三 186****3392")).containsExactly("张三")
    }
}
