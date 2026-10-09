package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test

/**
 * 从字面认人名（滴滴出票页实测漏检）。
 *
 * 乘车人一行是「沐晨冉 成人」，下面是打了星的身份证号：没有字段名，后面也不是电话，
 * 标签锚定和电话锚定都接不住。HanLP 的人名识别不看这些，只看字面。
 */
class PersonNameRecognizerTest {

    private val recognizer = PersonNameRecognizer(confidence = 0.6f)

    private fun names(text: String): List<String> = recognizer.findIn(text).map { text.substring(it.range) }

    @Test fun `the unlabelled passenger line of a ticket page`() {
        assertThat(names("沐晨冉 成人")).containsExactly("沐晨冉")
        assertThat(names("李思雨 成人票")).containsExactly("李思雨")
    }

    /** 标签锚定够不着的另一处：名字夹在一句话里。 */
    @Test fun `a name inside a chat sentence`() {
        assertThat(names("对了 ， 转告周振宇周三的会")).containsExactly("周振宇")
    }

    /** HanLP 把「沐晨冉」切成「沐晨」和「冉」两个人名词：首尾相接的要拼回一个。 */
    @Test fun `a name split into two pieces comes back whole`() {
        val text = "沐晨冉 86-186****3392 号码保护中"
        assertThat(recognizer.findIn(text).map { it.range }).containsExactly(0..2)
    }

    /** 只认出了姓：后面紧跟的一两个字连上。 */
    @Test fun `a bare surname takes the given name after it`() {
        assertThat(names("< 陈晓峰")).containsExactly("陈晓峰")
        assertThat(names("快递员 赵师傅 已为您签收")).containsExactly("赵师傅")
        assertThat(names("欧阳娜娜")).containsExactly("欧阳娜娜")
    }

    @Test fun `names next to labels and numbers`() {
        assertThat(names("入住人 张伟")).containsExactly("张伟")
        assertThat(names("收货人 ： 刘洋 138****6612")).containsExactly("刘洋")
        assertThat(names("王小明 86-139****5678 号码保护中")).containsExactly("王小明")
    }

    /** 拿人名、姓氏起头的商号：后面紧跟着行业词就不是人名。 */
    @Test fun `shop and brand names are not names`() {
        listOf(
            "申通快递 773443899958908 复制 | 打电话", "瑞幸咖啡", "张亮麻辣烫", "杨国福麻辣烫", "陈克明面条",
            "苏宁易购", "高德地图", "曹操出行",
        ).forEach { assertThat(names(it)).isEmpty() }
    }

    /** 「优衣库」是被当成音译人名认出来的：音译名、日本人名都不要。 */
    @Test fun `transliterated names are left out`() {
        assertThat(names("优衣库官方旗舰店 >")).isEmpty()
    }

    /** 姓开头的路名：以门牌用字结尾的不是人名。 */
    @Test fun `street names that start with a surname are not names`() {
        assertThat(names("送至 祁门路33号四方新村23幢605室")).isEmpty()
    }

    @Test fun `ordinary page text yields nothing`() {
        listOf(
            "出票成功", "查看乘车码", "使用乘车码检票请提前保存", "温馨提示 ： 请携带购票时使用的有效身份证件原件",
            "豪华大床房 · 1间 · 含双早", "中华门地铁站公交站 （下车点）", "该线路由皖美合肥客运提供服务",
            "南京南都会酒店 维也纳酒店(南京. .艺选安來酒店.", "您的快件已暂存至合肥四方新村10栋101室店菜鸟驿",
            "【乐事X蔚蓝档案】薯片多口味... ¥99.9", "刘海屏", "确认收货", "文件传输助手",
        ).forEach { assertThat(names(it)).isEmpty() }
    }

    /**
     * 真机反馈：淘宝商品规格页上一整页都被圈成了人名。HanLP 把药名「福来恩」「海乐妙」、规格「成猫」「单月」
     * 都认成人名，还把「8周以上」里的「周」当成姓、连上「以上」。它们紧贴着数量、规格词、名词，名字不会这样。
     */
    @Test fun `a product spec page yields no names`() {
        listOf(
            "【8周以上猫通用】福来恩3支", "【8周以上猫通用】福来恩1支", "【幼猫单月装】福来恩1支+海乐妙1粒",
            "【成猫单月装】福来恩1支+海乐妙1粒", "【幼猫季度装】福来恩3支+海乐妙3粒", "【成猫季度装】福来恩3支+海乐妙3粒",
            "【幼猫半年装】福来恩6支+海乐妙6粒", "【成猫半年装】福来恩6支+海乐妙6粒", "成猫单月套",
            "福来恩滴剂+海乐妙驱虫一粒", "平台加补后¥64.3 | 优惠前¥120", "（限购3件）有货", "颜色分类：成猫单月装",
            "规格：单月装 季度装 半年装", "适用体重：成猫2-8kg",
        ).forEach { assertThat(names(it)).isEmpty() }
    }

    /** 品牌名里被切出来的一截：左右紧贴着名词。 */
    @Test fun `brand names glued to the product are not names`() {
        listOf(
            "冠能猫粮 室内成猫 7kg", "伟嘉成猫猫粮海洋鱼味 3.6kg", "兰蔻小黑瓶精华肌底液 50ml", "珀莱雅双抗精华 2.0",
            "百雀羚水能量焕颜霜", "良品铺子猪肉脯 200g", "卫龙大面筋 106g*5袋", "金龙鱼大米", "舒肤佳香皂",
            "潘婷护发素", "曼秀雷敦唇膏", "本周热卖", "北京西站", "希尔顿欢朋", "连花清瘟胶囊", "江中健胃消食片",
        ).forEach { assertThat(names(it)).isEmpty() }
    }

    /** 名要像名：猫几乎不作名用，「古茗」的茗也是。 */
    @Test fun `a given name that is never used as one is rejected`() {
        assertThat(names("古茗")).isEmpty()
        assertThat(names("诺和锐")).isEmpty()
    }

    /** 名字后面紧贴称呼、自己的东西，前面紧贴身份，都还是名字。 */
    @Test fun `names next to titles roles and their own things`() {
        assertThat(names("张三老师好")).containsExactly("张三")
        assertThat(names("李娜电话多少")).containsExactly("李娜")
        assertThat(names("沐晨冉成人")).containsExactly("沐晨冉")
        assertThat(names("司机李建国正在赶来")).containsExactly("李建国")
        assertThat(names("群主王建国")).containsExactly("王建国")
        assertThat(names("您的快件已由李师傅揽收")).containsExactly("李师傅")
    }

    @Test fun `a single character is never a name on its own`() {
        assertThat(names("冉")).isEmpty()
    }

    /** 中文识别器逐字切分时，先拼回再认，区间映射回原串（带着拼接用的空格）。 */
    @Test fun `a line split into single characters`() {
        assertThat(names("沐 晨 冉 成 人")).containsExactly("沐 晨 冉")
    }

    // ---------- 进规则表的样子 ----------

    /** 不单独成条：并在人名规则里，排在字段名、电话两种锚定之后。 */
    private val rule = DefaultRuleSet.rules.first { it.id == "name" }

    /** 只凭字面认出的只是猜测：附近有没有旁证，RuleClassifier 看整页再定（见 NameAnchorPageTest）。 */
    @Test fun `a name found only from the text is a guess that needs an anchor`() {
        assertThat(rule.kind).isEqualTo(SensitiveKind.PERSON_NAME)
        assertThat(rule.enabledByDefault).isTrue()
        val found = rule.findIn("沐晨冉 成人").single()
        assertThat(found.needsAnchor).isTrue()
        assertThat(found.confidence).isEqualTo(0.6f)
    }

    /** 字段名或电话锚定得到的不用旁证；字面也认得出它，但锚定的排在前面，只出一个区间。 */
    @Test fun `an anchored name is reported once and needs nothing more`() {
        listOf("收货人 ： 刘洋 138****6612", "沐晨冉 86-186****3392 号码保护中").forEach { text ->
            val found = rule.findIn(text).single()
            assertThat(found.needsAnchor).isFalse()
        }
    }

    /** 设置页上只有一行「人名」，它的页上交代字面猜的那部分要旁证。 */
    @Test fun `the settings row is one plain name row that explains the evidence`() {
        assertThat(RuleCatalog.all.filter { it.kind == SensitiveKind.PERSON_NAME }).containsExactly(rule)
        assertThat(RuleCatalog.label(rule)).isEqualTo("人名")
        assertThat(RuleCatalog.description(rule)).contains("旁边得有电话、证件号、地址")
    }
}
