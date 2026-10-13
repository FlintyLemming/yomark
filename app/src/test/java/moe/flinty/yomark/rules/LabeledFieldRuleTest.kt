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
     * 只看锚定出来的。人名规则里还有一种从字面认的（要旁证才算），「张三去了北京」它照样认得出，
     * 那一种见 PersonNameRecognizerTest。
     */
    private fun matched(id: String, text: String): List<String> =
        DefaultRuleSet.rules.first { it.id == id }
            .findIn(text)
            .filterNot { it.needsAnchor }
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

    // ---------- 证件 ----------

    /** 证件上的字段名常带英文译名：跳过去取值，粘在一起、隔着斜杠、英文在前都一样。 */
    @Test fun `a bilingual label is skipped to the value`() {
        assertThat(matched("name", "姓名 Name 张三")).containsExactly("张三")
        assertThat(matched("name", "姓名Name张三")).containsExactly("张三")
        assertThat(matched("name", "姓名/Name SMITH, JOHN")).containsExactly("SMITH, JOHN")
        assertThat(matched("name", "Name/姓名 张三")).containsExactly("张三")
        assertThat(matched("name", "所有人 Owner 沈墨白")).containsExactly("沈墨白")
        assertThat(matched("address", "住址 Address 江苏省南京市玄武区北京东路")).containsExactly("江苏省南京市玄武区北京东路")
        assertThat(matched("birthdate", "出生日期 Date of Birth 1990-03-07")).containsExactly("1990-03-07")
        assertThat(matched("birthdate", "出生日期/Date of Birth/DOB 1990.03.07")).containsExactly("1990.03.07")
    }

    /** 只跳认得的译名：值本身是拉丁字母的名字不能被当成译名跳过去。 */
    @Test fun `a latin value is not skipped as a translation`() {
        assertThat(matched("name", "姓名 ZHANG SAN")).containsExactly("ZHANG SAN")
        assertThat(matched("name", "持证人 ZHANG, SAN")).containsExactly("ZHANG, SAN")
    }

    /** 字距拉开、两端对齐的字段名：营业执照的「住　　所」，没被认成逐字切分的「姓 名」。 */
    @Test fun `spaces inside a chinese label`() {
        assertThat(matched("address", "住　　所 浙江省杭州市西湖区三墩镇")).containsExactly("浙江省杭州市西湖区三墩镇")
        assertThat(matched("name", "姓　名 欧阳娜娜")).containsExactly("欧阳娜娜")
        assertThat(matched("name", "法定代表人 陈一鸣")).containsExactly("陈一鸣")
    }

    @Test fun `names with a middle dot and surname comma given`() {
        assertThat(matched("name", "姓名 阿依古丽·买买提")).containsExactly("阿依古丽·买买提")
        assertThat(matched("name", "姓名 阿依古丽•买买提 性别 女")).containsExactly("阿依古丽•买买提")
        assertThat(matched("name", "Name: SMITH,JOHN")).containsExactly("SMITH,JOHN")
    }

    /** 户口簿：名字后面紧跟着下一个字段名（逐字切分拼回来时连在一起）。 */
    @Test fun `a household register name stops at the next field`() {
        assertThat(matched("name", "姓 名 张 三 户 主 或 与 户 主 关 系 户 主")).containsExactly("张 三")
        assertThat(matched("name", "户主姓名 王五")).containsExactly("王五")
        assertThat(matched("address", "本市（县）其他住址 南京市鼓楼区中山北路8号")).containsExactly("南京市鼓楼区中山北路8号")
        assertThat(matched("address", "出生地 江苏省南京市 民族 汉")).containsExactly("江苏省南京市")
    }

    /** 也是常用词的字段名后面紧跟着字的是一句话。 */
    @Test fun `common word labels need a gap before the value`() {
        assertThat(matched("name", "所有人都到齐了吗")).isEmpty()
        assertThat(matched("name", "@所有人 明天下午三点开会")).isEmpty()
        assertThat(matched("name", "经营者应当依法纳税")).isEmpty()
        assertThat(matched("name", "法定代表人签字")).isEmpty()
    }

    /** 也是常用词的字段名，后面是一个名字、到行尾或下一个字段为止的，照样认。 */
    @Test fun `common word labels with a name after them`() {
        assertThat(matched("name", "法定代表人：陈一鸣")).containsExactly("陈一鸣")
        assertThat(matched("name", "监护人：张三")).containsExactly("张三")
        assertThat(matched("name", "经营者：王五 13800138000")).containsExactly("王五")
        assertThat(matched("name", "曾用名 张小三 性别 男")).containsExactly("张小三")
        assertThat(matched("name", "投资人 欧阳娜娜")).containsExactly("欧阳娜娜")
        assertThat(matched("name", "所有人 Owner ZHANG SAN")).containsExactly("ZHANG SAN")
    }

    /** 也是常用词的地址字段名后面要隔着空格、冒号。 */
    @Test fun `common word address labels need a gap`() {
        assertThat(matched("address", "住所：北京市朝阳区建国路88号")).containsExactly("北京市朝阳区建国路88号")
        assertThat(matched("address", "现居住地：上海市浦东新区")).containsExactly("上海市浦东新区")
        assertThat(matched("address", "户籍所在地 江苏省南京市鼓楼区")).containsExactly("江苏省南京市鼓楼区")
        assertThat(matched("address", "出生地 江苏")).containsExactly("江苏")
        assertThat(matched("address", "住所地人民法院管辖")).isEmpty()
        assertThat(matched("address", "居住地在北京朝阳")).isEmpty()
    }

    /** 字段名和值之间隔着表格的竖线、斜杠：值从它后面起，竖线斜杠不遮。 */
    @Test fun `a separator between label and value`() {
        assertThat(matched("address", "收货地址 | 朝阳公园南门东侧小区")).containsExactly("朝阳公园南门东侧小区")
        assertThat(matched("address", "地址 | 西溪湿地东区游客中心")).containsExactly("西溪湿地东区游客中心")
        assertThat(matched("address", "| 地址 | 北京市朝阳区建国路88号 |")).containsExactly("北京市朝阳区建国路88号")
        assertThat(matched("address", "地址/西溪湿地东区游客中心")).containsExactly("西溪湿地东区游客中心")
        assertThat(matched("address", "收货地址：/")).isEmpty()
    }

    /** 增值税发票的「地 址、电 话：」：值从冒号后面起，不带上「、电 话：」。 */
    @Test fun `the address and phone field of an invoice`() {
        assertThat(matched("address", "地 址、电 话：杭州市西湖区文三路88号 0571-88888888"))
            .containsExactly("杭州市西湖区文三路88号 0571-88888888")
    }

    /** 英文小字读错一个字母、「m」读成「rn」：照样跳过去。 */
    @Test fun `a misread translation is still skipped`() {
        assertThat(matched("name", "姓名 Narne 周以宁")).containsExactly("周以宁")
        assertThat(matched("address", "住址 Adress 江苏省南京市玄武区北京东路")).containsExactly("江苏省南京市玄武区北京东路")
        assertThat(matched("birthdate", "出生日期 Date of Brith 1986-02-14")).containsExactly("1986-02-14")
    }

    /** 认得出的译名：照写法、全大写、读错一个字母的长词、「m」读成「rn」的；名字不能被当成译名。 */
    @Test fun `which english words are translations`() {
        fun skipped(s: String) = LabeledField.Translations.skip(s, 0).let { (end, n) -> s.substring(0, end).trim().takeIf { n > 0 } }
        assertThat(skipped("Surname 张")).isEqualTo("Surname")
        assertThat(skipped("ID No. 123")).isEqualTo("ID No.")
        assertThat(skipped("Plate No 浙A")).isEqualTo("Plate No")
        assertThat(skipped("M&F 女")).isEqualTo("M&F")
        assertThat(skipped("Date of Birth/DOB 1990")).isEqualTo("Date of Birth/DOB")
        assertThat(skipped("Narne 周")).isEqualTo("Narne")
        assertThat(skipped("Valid Periad 2018")).isEqualTo("Valid Periad")
        listOf("Vin Diesel", "Id No", "Nana", "Bob", "Holden", "Owen", "Names", "ZHANG SAN").forEach {
            assertThat(skipped(it)).isNull()
        }
        // 括在括号里的；两个字母的「No.」要带句点
        assertThat(skipped("(VIN)LFV")).isEqualTo("(VIN)")
        assertThat(skipped(" （VIN） LFV")).isEqualTo("（VIN）")
        assertThat(skipped("No. 3201")).isEqualTo("No.")
        listOf("NO MIN HO", "No 3201", "Nora", "(ZHANG)").forEach { assertThat(skipped(it)).isNull() }
    }

    /** 「NO」是个姓（韩国的노），不当译名跳过。 */
    @Test fun `a surname that looks like a translation`() {
        assertThat(matched("name", "姓名 NO MIN HO")).containsExactly("NO MIN HO")
    }

    /** 缩写的译名只认大写：「Vin」是名字，不是 VIN。 */
    @Test fun `an acronym translation only in capitals`() {
        assertThat(matched("name", "姓名 Vin Diesel")).containsExactly("Vin Diesel")
    }

    /** 复姓和名之间隔着空格；间隔点两边隔着空格。 */
    @Test fun `spaced compound surnames and middle dots`() {
        assertThat(matched("name", "姓名：欧阳 娜娜")).containsExactly("欧阳 娜娜")
        assertThat(matched("name", "姓名 阿依古丽 • 买买提")).containsExactly("阿依古丽 • 买买提")
        assertThat(matched("name", "收货人 张三 已签收")).containsExactly("张三")
    }

    /** 点后面是店名、地名的不是间隔点。 */
    @Test fun `a dot before a store or a place`() {
        assertThat(matched("name", "签收人：菜鸟驿站·文三路店")).isEmpty()
        assertThat(matched("name", "收件人 王先生.北京市朝阳区")).containsExactly("王先生")
    }

    /** 表单上没填的格子里是提示语。 */
    @Test fun `a prompt is not a value`() {
        assertThat(matched("name", "姓名：请输入真实姓名")).isEmpty()
        assertThat(matched("address", "收货地址 请填写详细地址")).isEmpty()
    }

    /** 译名没读对（「Adress」）时值里会带着它，但不会只遮一个斜杠开头的英文字段名。 */
    @Test fun `a value never starts with the slash of a label`() {
        assertThat(matched("address", "出生地点/Place ofbirth")).isEmpty()
        assertThat(matched("address", "住址/Adress")).isEmpty()
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
