package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 证件上的那些认法（也是常用词的字段名、同一排右边那一段、折下去的几行、身份证人像面、毕业证的句子、「……日生」）
 * 放到聊天、订单、合同、设置页、路由器后台上，什么都不该认出来。例子都是 2026-10 评审时实测误遮的。
 */
class IdRulesOnOrdinaryTextTest {

    /** 一类在这一页上认出的；要旁证的猜测（从名单猜的人名）由 RuleClassifier 再定，这里不算。 */
    private fun found(id: String, page: Page): List<String> =
        DefaultRuleSet.rules.first { it.id == id }.findOnPage(page).filter { !it.match.needsAnchor }.map { page.text(it) }

    /** 一行一页，聊天气泡那样。 */
    private fun lines(vararg texts: String) = page(*texts.mapIndexed { i, t -> Row(t, 190f, 300f + 120f * i, 38f) }.toTypedArray())

    // ---------- 也是常用词的字段名 ----------

    /** 群聊里隔着冒号、空格的也是一句话：后面还连着字、跟着逗号，不以姓打头，带着「了」「都」。 */
    @Test fun `common word name labels in chat`() {
        val p = lines(
            "所有人：今晚八点开会，别迟到", "所有人 下午三点在三楼会议室", "经营者 注意了", "投资人 都很满意",
            "监护人 签字确认", "曾用名 没有", "法定代表人：负责人签字盖章", "监护人 未成年人的父母", "所有人 收到请回复",
            "所有人：明天放假", "所有人 马上到", "所有人 OK", "所有人 Thanks",
        )
        assertThat(found("name", p)).isEmpty()
    }

    /** 合同条文、聊天里的「住所」「居住地」「出生地」「营业场所」后面紧跟着字，是一句话。 */
    @Test fun `common word address labels in sentences`() {
        val p = lines(
            "住所地人民法院提起诉讼。", "，住所离公司很近", "居住地在北京朝阳", "出生地是哪儿来着", "出生地呢？",
            "出生地点在哪", "营业场所内禁止吸烟", "经营场所应当悬挂营业执照", "户籍所在地在哪里呀", "出生地 不重要",
        )
        assertThat(found("address", p)).isEmpty()
    }

    /** 合同折了行：「被告」/「住所地人民法院」，「本合同自双方签字之日起生」/「效。」。 */
    @Test fun `a wrapped contract clause`() {
        val p = page(
            Row("协商不成的，任何一方均可向被告", 60f, 300f, 30f),
            Row("住所地人民法院提起诉讼。", 60f, 340f, 30f),
            Row("本协议自2026年9月3日生", 60f, 400f, 30f),
            Row("效。", 60f, 440f, 30f),
        )
        assertThat(found("address", p)).isEmpty()
        assertThat(found("birthdate", p)).isEmpty()
    }

    // ---------- 毕业证的句子、「……日生」 ----------

    /** 「，男，」后面不是年月日俱全的「……日生」。 */
    @Test fun `a sex between commas without a birth date`() {
        val p = lines("招聘：保安，男，1970年后出生", "收到，女，1990年生的那个", "可以，男，", "好的，男，女都可以")
        assertThat(found("name", p)).isEmpty()
    }

    /** ML Kit 逐字切开的「生 效」。 */
    @Test fun `a split sheng xiao`() {
        assertThat(found("birthdate", lines("自 2026 年 9 月 3 日 生 效"))).isEmpty()
    }

    // ---------- 同一排右边那一段 ----------

    /** 设置页、路由器后台：「IP 地址」「Email 地址」里的英文不是译名，右边的值不是住址。 */
    @Test fun `settings and router rows`() {
        val p = page(
            Row(300f, 40f, Segment("IP 地址", 60f, 200f)), Row(300f, 40f, Segment("192.168.1.5", 420f, 600f)),
            Row(400f, 40f, Segment("WLAN MAC 地址", 60f, 300f)), Row(400f, 40f, Segment("02:00:00:44:55:66", 420f, 700f)),
            Row(500f, 40f, Segment("USDT 地址", 60f, 240f)), Row(500f, 40f, Segment("TQn9Y2khEsLJW1ChVWFMSMeRDow5KcbLSE", 420f, 1000f)),
            Row(600f, 40f, Segment("Email 地址", 60f, 240f)), Row(600f, 40f, Segment("abc@example.com", 420f, 700f)),
            Row(700f, 40f, Segment("DNS 地址", 60f, 220f)), Row(700f, 40f, Segment("8.8.8.8", 420f, 540f)),
            Row(800f, 30f, Segment("LAN 口 IP 地址", 60f, 260f)), Row(800f, 30f, Segment("192.168.1.1", 360f, 520f)),
        )
        assertThat(found("address", p)).isEmpty()
    }

    /** 没填的双语表单、双语表头：右边那一段本身是字段名，不是值。 */
    @Test fun `a blank bilingual form and a bilingual table header`() {
        val form = page(
            Row(300f, 40f, Segment("姓名 Name", 60f, 260f)), Row(300f, 40f, Segment("房号 Room No.", 500f, 760f)),
            Row(400f, 40f, Segment("姓名 Name", 60f, 260f)), Row(400f, 40f, Segment("年龄 Age", 500f, 660f)),
            Row(500f, 40f, Segment("地址 Address", 60f, 300f)), Row(500f, 40f, Segment("邮编 Postcode", 500f, 760f)),
            Row(600f, 40f, Segment("出生日期 Date of Birth", 60f, 440f)), Row(600f, 40f, Segment("国籍 Nationality", 600f, 900f)),
        )
        assertThat(found("name", form)).isEmpty()
        assertThat(found("address", form)).isEmpty()
        assertThat(found("birthdate", form)).isEmpty()
        val header = page(
            Row(300f, 40f, Segment("姓名 Name", 60f, 260f)), Row(300f, 40f, Segment("学号 Student No.", 330f, 640f)),
            Row(300f, 40f, Segment("成绩 Score", 700f, 900f)),
        )
        assertThat(found("name", header)).isEmpty()
    }

    // ---------- 身份证人像面 ----------

    private val id = "110101199001011237"

    /** app 里的个人资料页：有身份证号，有一行「性别 男」，但性别和民族不在一行，号码前面写的不是「公民身份号码」。 */
    @Test fun `profile forms are not id card fronts`() {
        val forms = listOf(
            page(
                Row("个人信息", 60f, 200f, 40f), Row("姓名", 60f, 300f, 40f), Row("张三", 360f, 300f, 40f),
                Row("手机号", 60f, 380f, 40f), Row("13800138000", 360f, 380f, 40f), Row("性别", 60f, 460f, 40f),
                Row("男", 360f, 460f, 40f), Row("职业", 60f, 540f, 40f), Row("工程师", 360f, 540f, 40f),
                Row("身份证号", 60f, 620f, 40f), Row(id, 360f, 620f, 40f),
            ),
            page(Row("手机号 13800138000", 60f, 300f, 40f), Row("性别 男", 60f, 380f, 40f), Row("职业 工程师", 60f, 460f, 40f), Row("身份证号 $id", 60f, 540f, 40f)),
            page(Row("实名认证", 60f, 200f, 40f), Row("性别 男", 60f, 300f, 40f), Row("婚姻 未婚", 60f, 380f, 40f), Row("身份证号 $id", 60f, 460f, 40f)),
            page(Row("年龄 36", 60f, 300f, 40f), Row("性别 女", 60f, 380f, 40f), Row("学历 本科", 60f, 460f, 40f), Row("证件号码 $id", 60f, 540f, 40f)),
        )
        forms.forEach { p ->
            assertThat(found("name", p)).containsNoneOf("手机号", "实名认证", "年龄")
            assertThat(found("address", p)).isEmpty()
        }
    }

    // ---------- 地址下面对齐的那几行 ----------

    /** 订单、地图页上地址下面对着它左沿的是按钮和送货备注。 */
    @Test fun `ui lines under an address`() {
        val p = page(
            Row("收货地址：浙江省杭州市西湖区文三路88号", 60f, 300f, 40f),
            Row("修改", 260f, 350f, 40f), Row("复制地址", 380f, 350f, 40f),
            Row("地址：上海市浦东新区世纪大道100号", 60f, 600f, 40f), Row("导航", 180f, 650f, 40f),
            Row("配送地址：北京市朝阳区建国路88号", 60f, 900f, 40f), Row("预计10月14日送达", 260f, 950f, 40f),
            Row("配送方式 快递 免邮", 260f, 1000f, 40f),
        )
        assertThat(found("address", p))
            .containsExactly("浙江省杭州市西湖区文三路88号", "上海市浦东新区世纪大道100号", "北京市朝阳区建国路88号")
    }
}
