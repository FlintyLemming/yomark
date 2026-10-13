package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
            Row(600f, 40f, Segment("出生日期 Date of Birth", 60f, 440f)), Row(600f, 40f, Segment("国籍 Nationality", 360f, 900f)),
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

    /** 收货地址下面对着值的左沿的商家配送说明：「杭州市内次日达」也有「市」，不是住址的下半截。 */
    @Test fun `shipping notes under an address`() {
        val p = page(
            Row(300f, 34f, Segment("收货地址：", 48f, 150f), Segment("浙江省杭州市西湖区文三路88号", 160f, 640f)),
            Row("杭州市内次日达", 160f, 360f, 34f),
            Row(500f, 34f, Segment("收货地址：", 48f, 150f), Segment("江苏省南京市玄武区北京东路41号", 160f, 640f)),
            Row("江浙沪包邮区 当日发货", 160f, 560f, 34f),
            Row(700f, 34f, Segment("配送地址：", 48f, 150f), Segment("上海市浦东新区张江路100号", 160f, 640f)),
            Row("门店自提点：张江路店", 160f, 760f, 34f),
        )
        assertThat(found("address", p))
            .containsExactly("浙江省杭州市西湖区文三路88号", "江苏省南京市玄武区北京东路41号", "上海市浦东新区张江路100号")
    }

    // ---------- 第二轮评审：没有字段名的号码，字段名在别的行 ----------

    /** 两栏的账单详情、审批单：「商家订单号」「关联单据」在左边一栏，号码在右边一栏，OCR 读成两行。 */
    @Test fun `order numbers in the right column are not vins`() {
        val p = page(
            Row("商家订单号", 48f, 300f, 34f), Row("LFWADRJF011002346", 360f, 300f, 34f),
            Row("关联单据", 48f, 380f, 34f), Row("1HGCM82633A004352", 360f, 380f, 34f),
        )
        assertThat(found("vin", p)).isEmpty()
        val control = page(Row("车辆", 48f, 300f, 34f), Row("LFWADRJF011002346", 360f, 300f, 34f))
        assertThat(found("vin", control)).containsExactly("LFWADRJF011002346")
    }

    /**
     * 政务 app 的办件详情：「办件编号」在号码左边一栏或者正上方，号码碰巧过得了身份证的三道关（地区码、日期、校验码），
     * 原先遮成了证件号码，旁边那个同一天的「受理日期」也跟着遮成了生日。
     */
    @Test fun `a case number beside or under its label`() {
        val beside = page(
            Row("办件编号", 48f, 300f, 34f), Row("44030420250915001X", 360f, 300f, 34f),
            Row("受理日期", 48f, 380f, 34f), Row("2025-09-15", 360f, 380f, 34f),
        )
        val under = page(
            Row("统一受理编号", 48f, 300f, 30f), Row("44030420250915001X", 48f, 345f, 34f),
            Row("受理时间", 48f, 420f, 30f), Row("2025-09-15 09:30", 48f, 465f, 34f),
        )
        val transfer = page(Row("交易流水号", 48f, 300f, 34f), Row("44030420250915001X", 360f, 300f, 34f))
        listOf(beside, under, transfer).forEach { p ->
            assertThat(found("idnumber", p)).isEmpty()
            assertThat(found("birthdate", p)).isEmpty()
        }
        // 上面那一行自己是个带号码的值，不是字段名
        val list = page(Row("订单号 E12345678 已完成", 48f, 300f, 30f), Row("44030420250915001X", 48f, 345f, 34f))
        assertThat(found("idnumber", list)).containsExactly("44030420250915001X")
    }

    /** 号码里的「生日」就是这几天：办件号、流水号里的当天日期。没有字段名的不算证件号，有字段名的照遮。 */
    @Test fun `a number dated this week is not an id on its own`() {
        val number = idBornOn(LocalDate.now().minusDays(2))
        assertThat(found("idnumber", lines(number))).isEmpty()
        assertThat(found("idnumber", lines("公民身份号码 $number"))).containsExactly(number)
    }

    /** 孩子的号码（有字段名，照遮）里的生日碰巧和受理日期是同一天：受理日期不是生日。 */
    @Test fun `an event date that matches the birth date`() {
        val p = lines("公民身份号码 330106202503110013", "受理日期 2025-03-11", "出生日期 2025-03-11")
        assertThat(found("birthdate", p)).containsExactly("2025-03-11")
    }

    /** 12306 的订单号、电子客票号，日期打头的退款编号：字段名在左边一栏，页上又写着台湾、韩国，也不是证件号。 */
    @Test fun `ticket and refund numbers beside their labels`() {
        val ticket = page(
            Row("台湾居民来往大陆通行证", 48f, 200f, 34f),
            Row("订单号", 48f, 300f, 34f), Row("E875196458", 360f, 300f, 34f),
            Row("电子客票号", 48f, 380f, 34f), Row("E875196458 1 05 012", 360f, 380f, 34f),
        )
        assertThat(found("idnumber", ticket)).isEmpty()
        val refunds = page(
            Row("KOREA", 48f, 200f, 34f),
            Row("订单编号", 48f, 300f, 34f), Row("261013-1234567", 360f, 300f, 34f),
            Row("退款编号", 48f, 380f, 34f), Row("251231-5551234", 360f, 380f, 34f),
        )
        assertThat(found("idnumber", refunds)).isEmpty()
        // 订单列表上单独一行的订单号：页上没说是台湾的证件
        assertThat(found("idnumber", lines("我的订单", "E875196458", "G1234 北京南-上海虹桥"))).isEmpty()
    }

    /** 天猫的商品参数、3C 证书：「证书编号」后面是产品证书的号；个人证书上写着姓名。 */
    @Test fun `product certificate numbers`() {
        val tmall = lines("产品参数：", "品牌：美的", "型号：MK-SH15", "证书编号：2020010703372854", "证书状态：有效")
        val ccc = page(
            Row("强制性产品认证证书", 48f, 200f, 34f),
            Row("证书编号", 48f, 300f, 34f), Row("2020010703372854", 360f, 300f, 34f),
            Row("产品名称", 48f, 380f, 34f), Row("电热水壶", 360f, 380f, 34f),
        )
        assertThat(found("idnumber", tmall)).isEmpty()
        assertThat(found("idnumber", ccc)).isEmpty()
        assertThat(found("idnumber", lines("姓名 张三", "证书编号 1033512026050012"))).containsExactly("1033512026050012")
    }

    /** 电费、水费的户号：页上没有户主、户别这些户口簿上的字。 */
    @Test fun `utility account numbers`() {
        assertThat(found("idnumber", lines("户号 3301234567 你自己在网上交也行", "户号：0012345678 张*"))).isEmpty()
        assertThat(found("idnumber", lines("户别 家庭户", "户号 330106123"))).containsExactly("330106123")
    }

    // ---------- 第二轮评审：字段名、值 ----------

    /** 斜杠、竖线后面的又是一个字段名：那个字段名不是值。 */
    @Test fun `a second field name after a slash or a bar`() {
        val p = lines(
            "签收人/签收时间：", "收件人/Consignee：", "寄件人/Shipper：", "持卡人/Card Holder：", "姓名/学号：",
            "户名/账号：", "申请人/日期：", "联系人/职务：", "姓名 | 学号 | 成绩", "姓名|部门|职位",
        )
        assertThat(found("name", p)).isEmpty()
        assertThat(found("name", lines("收件人/Consignee：李四", "姓名/学号：王五/2021001"))).containsExactly("李四", "王五")
        assertThat(found("address", lines("收货地址 | 朝阳公园南门东侧小区"))).containsExactly("朝阳公园南门东侧小区")
    }

    /** 「住所」「营业场所」「出生地」后面隔着冒号，值也不一定是地方：要有省市区县、路、门牌或者数字。 */
    @Test fun `common word address labels with a colon but no place`() {
        val p = lines(
            "住所：已经找好了，下周搬", "住所：公司宿舍，离地铁近", "住所：详见附件一", "营业场所：暂不对外开放",
            "营业场所：一楼大厅", "经营场所：线上经营", "居住地 附近有地铁吗", "出生地：保密", "出生地：北京？",
        )
        assertThat(found("address", p)).isEmpty()
        assertThat(found("address", lines("住所 北京市朝阳区建国路88号", "营业场所：杭州市西湖区文三路90号", "出生地 江苏")))
            .containsExactly("北京市朝阳区建国路88号", "杭州市西湖区文三路90号", "江苏")
    }

    /** 繁体、英文界面上没填的格子：「請填寫地址」「Edit address」「Change」不是值。 */
    @Test fun `prompts in traditional chinese and english`() {
        val p = page(
            Row("地址 Address", 48f, 300f, 34f), Row("請填寫地址", 360f, 300f, 34f),
            Row("姓名 Name", 48f, 380f, 34f), Row("請輸入姓名", 360f, 380f, 34f),
            Row("地址 Address", 48f, 460f, 34f), Row("Edit address", 360f, 460f, 34f),
            Row("姓名 Name", 48f, 540f, 34f), Row("Change", 360f, 540f, 34f),
        )
        assertThat(found("name", p)).isEmpty()
        assertThat(found("address", p)).isEmpty()
    }

    /** 名片、订单上名字下面一行的职务、会员等级拼不成拼音，不是名字的拼音。 */
    @Test fun `a job title under a labelled name`() {
        val p = page(
            Row("联系人：张伟", 48f, 300f, 34f), Row("SALES MANAGER", 130f, 345f, 34f),
            Row("收货人：李雷", 48f, 500f, 34f), Row("VIP MEMBER", 130f, 545f, 34f),
        )
        assertThat(found("name", p)).containsExactly("张伟", "李雷")
    }

    /** 政策条文里「……出生的宝宝」是一批人，一百一十年前的是史料；家里人的生日照遮。 */
    @Test fun `birth dates of a group or of history`() {
        val p = lines("2025年1月1日出生的宝宝可享受育儿补贴", "2022年1月1日出生的孩子是否可以申领？", "鲁迅，1881年9月25日生于浙江绍兴。")
        assertThat(found("birthdate", p)).isEmpty()
        assertThat(found("birthdate", lines("你外婆1942年3月5日生的", "我家宝宝2025年1月1日出生的")))
            .containsExactly("1942年3月5日", "2025年1月1日")
    }

    /** 优惠券上的「VALID THRU」：页上没有卡。 */
    @Test fun `a coupon valid thru date`() {
        val yy = "%02d".format((LocalDate.now().year + 2) % 100)
        assertThat(found("card", lines("满100减20", "VALID THRU 12/$yy", "GOOD THRU 11/$yy"))).isEmpty()
        assertThat(found("card", lines("4111 1111 1111 1111", "VALID THRU 12/$yy"))).containsExactly("4111 1111 1111 1111", "12/$yy")
    }

    /** 地区码 330106、生日 [date]、顺序码 001，校验码算出来的身份证号。 */
    private fun idBornOn(date: LocalDate): String {
        val body = "330106" + date.format(DateTimeFormatter.BASIC_ISO_DATE) + "001"
        val sum = body.mapIndexed { i, c -> (c - '0') * WEIGHTS[i] }.sum()
        return body + "10X98765432"[sum % 11]
    }

    private companion object {
        val WEIGHTS = intArrayOf(7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2)
    }
}
