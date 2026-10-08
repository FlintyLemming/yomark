package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
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

    /**
     * 只看锚定出来的。人名规则里还有一种从字面认的（只圈不打码），「张三去了北京」它照样认得出，
     * 那一种见 PersonNameRecognizerTest。
     */
    private fun matched(id: String, text: String): List<String> =
        DefaultRuleSet.rules.first { it.id == id }
            .findIn(text)
            .filterNot { it.outlineOnly }
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

    /**
     * 物流轨迹每一单都有「签收人：本人」。这些值整个作废，
     * 不能停在「驿站」前面把「菜鸟」两个字当成名字。
     */
    @Test fun `placeholder recipients are not names`() {
        listOf("签收人：本人", "签收人：门卫", "签收人：菜鸟驿站", "签收人：丰巢快递柜", "签收人：他人代收")
            .forEach { assertThat(matched("name", it)).isEmpty() }
    }

    @Test fun `a courier label anchors the courier name`() {
        assertThat(matched("name", "快 递 员 王 强 138****5678")).containsExactly("王 强")
    }

    // ---------- 地址 ----------
    /** 地址天然含数字（门牌号），不能用姓名那套「遇数字即停」。 */
    @Test fun `address keeps the house number`() {
        assertThat(matched("address", "收货地址 北京市朝阳区建国路 88 号"))
            .containsExactly("北京市朝阳区建国路 88 号")
    }

    /** 没有字段名、也没有门牌形状的文字不是地址（有门牌形状的见 AddressShapeTest）。 */
    @Test fun `address ignores text with no label and no house number`() {
        assertThat(matched("address", "我明天去北京出差")).isEmpty()
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

    // ---------- 中文识别器逐字切分 ----------
    /**
     * 中文识别器可能一个汉字一个 element，拼接后标签成了「收 货 地 址」。
     * 规则要先把它拼回来，否则标签锚定在真机上整条失灵。
     */
    @Test fun `labels are found when the recognizer splits every character`() {
        assertThat(matched("address", "收 货 地 址 ： 北 京 市 朝 阳 区 建 国 路 88 号"))
            .containsExactly("北 京 市 朝 阳 区 建 国 路 88 号")
        assertThat(matched("name", "收 件 人 张 三 13812345678")).containsExactly("张 三")
    }

    /** 拼回来之后「IP地址」仍然是一个词，词中词的挡板不能因为切分而失效。 */
    @Test fun `split latin prefixed labels are still rejected`() {
        assertThat(matched("address", "IP 地 址 192.168.1.10")).isEmpty()
    }

    /** 逐字切分拼回来成了「张三手机」时，名字在下一个字段名前停下。 */
    @Test fun `a split name stops before the next field label`() {
        assertThat(matched("name", "联 系 人 张 三 手 机 号 码")).containsExactly("张 三")
    }

    /** 菜鸟快递详情页的收件地址只有「送至」两个字领着。 */
    @Test fun `address follows the song zhi label of a logistics page`() {
        assertThat(matched("address", "送至 祁门路33号四方新村23幢605室"))
            .containsExactly("祁门路33号四方新村23幢605室")
    }

    /** 「送至」在句子中间是动词，不是字段名。 */
    @Test fun `song zhi inside a sentence is not a label`() {
        assertThat(matched("address", "预计明天送至驿站")).isEmpty()
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
