package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 证件页上的姓名、住址、出生日期，按出厂规则表跑：身份证（合成卡和用户拍的那张的读法）、驾驶证、行驶证、户口簿、
 * 营业执照、出生医学证明、毕业证、学生证、中国护照、往来港澳通行证、台湾身份证。号码另有规则，这里不管。
 * 同一套认法放到聊天、订单、设置页上不该认出什么，见 IdRulesOnOrdinaryTextTest。
 *
 * 内容全是编的。用户那张照片只照抄 OCR 读坏的样子（「名」前丢了「姓」、「性新女民 族汉」、年份里夹空格、
 * 号码的头一位粘在「码」上），名字、号码、生日都换了。
 */
class IdDocumentPageTest {

    /** 一类在这一页上认出的；要旁证的猜测（从名单猜的人名）由 RuleClassifier 再定，这里不算。 */
    private fun found(id: String, page: Page): List<String> =
        DefaultRuleSet.rules.first { it.id == id }.findOnPage(page).filter { !it.match.needsAnchor }.map { page.text(it) }

    // ---------- 居民身份证人像面 ----------

    /**
     * 合成卡（tools/test-images/IdCardPage.java）上真 OCR 读出来的六行：字段名「姓 名」拉开，住址第二行和值左对齐、
     * 不带字段名，号码字距宽。坐标照着生成器的排版。
     */
    private val idCardRows = listOf(
        Row(84f, 36f, Segment("姓", 70f, 97f), Segment("名", 120f, 147f), Segment("林知夏", 180f, 288f)),
        Row(
            162f, 31f, Segment("性", 70f, 97f), Segment("别", 120f, 147f), Segment("女", 180f, 211f),
            Segment("民", 300f, 327f), Segment("族", 350f, 377f), Segment("漢", 410f, 441f),
        ),
        Row(
            240f, 31f, Segment("出", 70f, 97f), Segment("生", 120f, 147f), Segment("1993", 180f, 250f), Segment("年", 262f, 289f),
            Segment("7", 318f, 334f), Segment("月", 350f, 377f), Segment("18", 404f, 434f), Segment("日", 450f, 477f),
        ),
        Row(318f, 31f, Segment("住", 70f, 97f), Segment("址", 120f, 147f), Segment("浙江省杭州市西湖区文三路", 180f, 560f)),
        Row(366f, 31f, Segment("88号6幢2单元501", 180f, 440f), Segment("室", 452f, 483f)),
        Row(512f, 38f, Segment("公民身份号码", 70f, 260f), Segment("330106199307180423", 330f, 900f)),
    )
    private val idCard = page(*idCardRows.toTypedArray())

    @Test fun `the synthetic id card`() {
        assertThat(found("name", idCard)).containsExactly("林知夏")
        assertThat(found("birthdate", idCard)).containsExactly("1993 年 7 月 18 日")
        assertThat(found("address", idCard)).containsExactly("浙江省杭州市西湖区文三路", "88号6幢2单元501 室")
    }

    /**
     * 用户拍的那张的读法：「姓」读丢了，「性别」读成「性新」，年份里夹着空格，号码拆成几段、头一位粘在「码」上。
     * 0.15.0 上只认出了住址。
     */
    private val idPhotoRows = listOf(
        Row(84f, 34f, Segment("名", 120f, 147f), Segment("宋语桐", 180f, 282f), glued = true),
        Row(160f, 30f, Segment("性新女民", 70f, 330f), Segment("族汉", 350f, 440f)),
        Row(
            238f, 30f, Segment("出", 70f, 97f), Segment("生", 120f, 147f), Segment("201 2 ", 180f, 262f), Segment("年4 ", 262f, 334f),
            Segment("月17日", 350f, 477f), glued = true,
        ),
        Row(316f, 30f, Segment("住址", 70f, 147f), Segment("浙江省杭州市上城区解放路", 180f, 560f)),
        Row(364f, 30f, Segment("128号", 180f, 300f)),
        Row(510f, 36f, Segment("公民 身份号 码3 301 02201204174527", 70f, 900f)),
    )
    private val idPhoto = page(*idPhotoRows.toTypedArray())

    @Test fun `the mangled labels of a photographed id card`() {
        assertThat(found("name", idPhoto)).containsExactly("宋语桐")
        assertThat(found("birthdate", idPhoto)).containsExactly("201 2 年4 月17日")
        assertThat(found("address", idPhoto)).containsExactly("浙江省杭州市上城区解放路", "128号")
    }

    /** 「出生」整个读丢了，只剩日期：拿号码里的生日对出来。 */
    @Test fun `the birth date from the id number when its label is gone`() {
        val p = page(
            Row(84f, 34f, Segment("姓名", 70f, 147f), Segment("宋语桐", 180f, 282f)),
            Row(238f, 30f, Segment("2012年4月17日", 180f, 400f)),
            Row(510f, 36f, Segment("公民身份号码", 70f, 260f), Segment("330102201204174527", 330f, 900f)),
        )
        assertThat(found("birthdate", p)).containsExactly("2012年4月17日")
    }

    /** 「住址」读成了「佳址」：出生和号码之间、在值那一栏的就是住址。 */
    @Test fun `an address whose label was misread`() {
        val p = page(
            Row(160f, 30f, Segment("性别女民族汉", 70f, 440f)),
            Row(238f, 30f, Segment("出生", 70f, 147f), Segment("2012年4月17日", 180f, 400f), glued = true),
            Row(316f, 30f, Segment("佳址", 70f, 147f), Segment("浙江省杭州市上城区解放路", 180f, 560f), glued = true),
            Row(510f, 36f, Segment("公民身份号码", 70f, 260f), Segment("330102201204174527", 330f, 900f)),
        )
        assertThat(found("address", p)).containsExactly("浙江省杭州市上城区解放路")
    }

    /** 「姓名」读成了「娃名」：字段名那一栏的字按值那一栏去掉，不按字面。 */
    @Test fun `a misread name label is dropped by its column`() {
        val p = page(
            Row(84f, 36f, Segment("娃名", 70f, 147f), Segment("林知夏", 180f, 288f)),
            *idCardRows.drop(1).toTypedArray(),
        )
        assertThat(found("name", p)).containsExactly("林知夏")
    }

    /** 一页上拍了两张人像面：第二张的字段名也读坏了，照样各认各的。 */
    @Test fun `two id card fronts on one page`() {
        val second = idPhotoRows.map { r -> Row(r.y + 700f, r.h, *r.segments, glued = r.glued) }
        val p = page(*idCardRows.toTypedArray(), *second.toTypedArray())
        assertThat(found("name", p)).containsExactly("林知夏", "宋语桐")
        assertThat(found("address", p))
            .containsExactly("浙江省杭州市西湖区文三路", "88号6幢2单元501 室", "浙江省杭州市上城区解放路", "128号")
    }

    /** 住址第二行以「民族」「车辆」打头：那是路名（民族大道、车辆段），不是下一个字段。 */
    @Test fun `an address line that starts like a field name`() {
        val p = page(
            Row(266f, 39f, Segment("住址", 34f, 91f), Segment("Address", 91f, 160f)),
            Row(270f, 32f, Segment("广西南宁市青秀区", 380f, 636f)),
            Row(318f, 31f, Segment("民族大道136号", 379f, 600f)),
            Row(566f, 39f, Segment("住址", 34f, 91f), Segment("Address", 91f, 160f)),
            Row(570f, 32f, Segment("北京市丰台区", 380f, 572f)),
            Row(618f, 31f, Segment("车辆段宿舍3号楼", 379f, 627f)),
        )
        assertThat(found("address", p)).containsExactly("广西南宁市青秀区", "民族大道136号", "北京市丰台区", "车辆段宿舍3号楼")
    }

    /** 号码那一行不是住址的下半截，也不是名字。 */
    @Test fun `the id number row is not part of the address`() {
        assertThat(found("address", idCard)).doesNotContain("330106199307180423")
        assertThat(found("name", idPhoto)).doesNotContain("公民")
    }

    // ---------- 驾驶证、行驶证 ----------

    /**
     * 驾驶证正页（tools/test-images/DriverLicensePage.java）上真 OCR 读出来的样子：中文字段名后面跟着小字英文，
     * 值在右边一栏，隔得远，字段名和值成了同一排的两行；「周以宁」读成了「周 以宁」，住址折成两行。
     */
    private val drivingLicence = page(
        Row(37f, 42f, Segment("中华人民共和国机动车驾驶证", 265f, 833f)),
        Row(90f, 25f, Segment("Driving License of the People's Republic of China", 335f, 766f)),
        Row(144f, 43f, Segment("证号", 32f, 99f)),
        Row(150f, 30f, Segment("320105198602143729", 379f, 749f)),
        Row(
            203f, 45f, Segment("周", 375f, 405f), Segment("以宁", 412f, 475f), Segment("性别", 554f, 607f), Segment("Sex", 607f, 650f),
            Segment("女", 670f, 700f), Segment("国籍", 733f, 811f), Segment("Nationality", 811f, 893f), Segment("中国", 954f, 1027f),
        ),
        Row(206f, 38f, Segment("姓名", 33f, 93f), Segment("Name", 93f, 146f)),
        Row(266f, 39f, Segment("住址", 34f, 91f), Segment("Address", 91f, 160f)),
        Row(270f, 32f, Segment("江苏省南京市玄武区北京东路", 380f, 776f)),
        Row(318f, 31f, Segment("41号3栋2单元602室", 379f, 642f)),
        Row(378f, 35f, Segment("出生日期", 39f, 143f), Segment("Date of Birth", 143f, 237f)),
        Row(379f, 33f, Segment("1986-02-14", 379f, 538f)),
        Row(438f, 34f, Segment("初次领证日期", 39f, 196f), Segment("Date of First Issue", 196f, 330f), Segment("2008-05-20", 377f, 537f)),
        Row(496f, 39f, Segment("准驾车型", 36f, 146f), Segment("Class", 146f, 198f)),
        Row(496f, 39f, Segment("C1", 371f, 416f)),
        Row(558f, 36f, Segment("有效期限", 38f, 145f), Segment("Valid Periad", 145f, 235f)),
        Row(561f, 29f, Segment("2018-05-20 至 2028-05-20", 379f, 754f)),
    )

    @Test fun `a driving licence with bilingual labels and values in their own column`() {
        assertThat(found("name", drivingLicence)).containsExactly("周 以宁")
        assertThat(found("address", drivingLicence)).containsExactly("江苏省南京市玄武区北京东路", "41号3栋2单元602室")
        assertThat(found("birthdate", drivingLicence)).containsExactly("1986-02-14")
    }

    /** 字段名和值在同一行（字段名短、值离得近）：译名跳过去。 */
    @Test fun `a driving licence row read as one line`() {
        val p = page(
            Row(180f, 26f, Segment("姓名 Name", 60f, 200f), Segment("周以宁", 230f, 308f), Segment("性别 Sex", 420f, 530f), Segment("女", 560f, 586f)),
            Row(230f, 26f, Segment("住址 Address", 60f, 210f), Segment("江苏省南京市玄武区北京东路", 230f, 568f)),
            Row(270f, 26f, Segment("41号3栋2单元602室", 230f, 450f)),
            Row(320f, 26f, Segment("出生日期 Date of Birth", 60f, 220f), Segment("1986-02-14", 230f, 400f)),
        )
        assertThat(found("name", p)).containsExactly("周以宁")
        assertThat(found("address", p)).containsExactly("江苏省南京市玄武区北京东路", "41号3栋2单元602室")
        assertThat(found("birthdate", p)).containsExactly("1986-02-14")
    }

    /** 英文小字读错了一个字母（「Narne」「Adress」「Date of Brith」）：照样认出是译名，跳过去接右边的值。 */
    @Test fun `a misread english sub-label`() {
        val p = page(
            Row(203f, 45f, Segment("周以宁", 375f, 475f)),
            Row(206f, 38f, Segment("姓名", 33f, 93f), Segment("Narne", 93f, 150f)),
            Row(266f, 39f, Segment("住址", 34f, 91f), Segment("Adress", 91f, 156f)),
            Row(270f, 32f, Segment("江苏省南京市玄武区北京东路", 380f, 776f)),
            Row(318f, 31f, Segment("41号3栋2单元602室", 379f, 642f)),
            Row(378f, 35f, Segment("出生日期", 39f, 143f), Segment("Date of Brith", 143f, 237f)),
            Row(379f, 33f, Segment("1986-02-14", 379f, 538f)),
        )
        assertThat(found("name", p)).containsExactly("周以宁")
        assertThat(found("address", p)).containsExactly("江苏省南京市玄武区北京东路", "41号3栋2单元602室")
        assertThat(found("birthdate", p)).containsExactly("1986-02-14")
    }

    /** 英文小字自己成了一段：「姓名」|「Name」|「周以宁」。 */
    @Test fun `the english sub-label as its own box`() {
        val p = page(
            Row(203f, 45f, Segment("周以宁", 375f, 475f)),
            Row(206f, 38f, Segment("姓名", 33f, 93f)),
            Row(212f, 26f, Segment("Name", 96f, 146f)),
            Row(266f, 39f, Segment("住址", 34f, 91f)),
            Row(272f, 26f, Segment("Address", 94f, 160f)),
            Row(270f, 32f, Segment("江苏省南京市玄武区北京东路", 380f, 776f)),
            Row(318f, 31f, Segment("41号3栋2单元602室", 379f, 642f)),
        )
        assertThat(found("name", p)).containsExactly("周以宁")
        assertThat(found("address", p)).containsExactly("江苏省南京市玄武区北京东路", "41号3栋2单元602室")
    }

    /** 酒店入住登记表这样的双语表单，没填的格子里是提示语。 */
    @Test fun `a prompt in an empty form field is not a value`() {
        val p = page(
            Row(300f, 36f, Segment("姓名 Name", 60f, 220f)),
            Row(300f, 36f, Segment("请输入", 400f, 508f)),
            Row(540f, 36f, Segment("地址 Address", 60f, 260f)),
            Row(540f, 36f, Segment("请填写详细地址", 400f, 652f)),
        )
        assertThat(found("name", p)).isEmpty()
        assertThat(found("address", p)).isEmpty()
    }

    /** 表头一样的一排：单单一个「姓名」，右边隔得老远的是下一格的表头，不是值。 */
    @Test fun `a bare label does not take the next cell on its row`() {
        val p = page(
            Row(100f, 30f, Segment("姓名", 60f, 120f)),
            Row(100f, 30f, Segment("学号", 300f, 360f)),
            Row(100f, 30f, Segment("成绩", 540f, 600f)),
        )
        assertThat(found("name", p)).isEmpty()
    }

    @Test fun `a vehicle licence`() {
        val p = page(
            Row(
                100f, 24f, Segment("号牌号码 Plate No.", 40f, 190f), Segment("浙A12345", 200f, 320f),
                Segment("车辆类型 Vehicle Type", 420f, 600f), Segment("小型轿车", 620f, 720f),
            ),
            Row(150f, 24f, Segment("所有人 Owner", 40f, 190f), Segment("沈墨白", 200f, 272f)),
            Row(200f, 24f, Segment("住址 Address", 40f, 190f), Segment("浙江省杭州市拱墅区湖墅南路", 200f, 530f)),
            Row(240f, 24f, Segment("186号2幢1101室", 200f, 380f)),
            Row(
                290f, 24f, Segment("使用性质 Use Character", 40f, 190f), Segment("非营运", 200f, 272f),
                Segment("品牌型号 Model", 420f, 560f), Segment("大众牌FV7152FBDWG", 580f, 800f),
            ),
            Row(340f, 24f, Segment("车辆识别代号 VIN", 40f, 190f), Segment("LFV2A21K6D4123456", 200f, 480f)),
        )
        assertThat(found("name", p)).containsExactly("沈墨白")
        assertThat(found("address", p)).containsExactly("浙江省杭州市拱墅区湖墅南路", "186号2幢1101室")
    }

    // ---------- 户口簿、营业执照、出生医学证明 ----------

    /** 常住人口登记卡：一行两个字段，长字段名「本市（县）其他住址」「何时由何地迁来本市（县）」。 */
    @Test fun `a household register page`() {
        val p = page(
            Row(
                100f, 24f, Segment("姓名", 40f, 88f), Segment("张三", 120f, 168f), Segment("户主或与户主关系", 400f, 592f),
                Segment("户主", 620f, 668f),
            ),
            Row(140f, 24f, Segment("曾用名", 40f, 112f), Segment("张小三", 120f, 192f), Segment("性别", 400f, 448f), Segment("男", 620f, 644f)),
            Row(180f, 24f, Segment("出生地", 40f, 112f), Segment("江苏省南京市", 120f, 264f), Segment("民族", 400f, 448f), Segment("汉", 620f, 644f)),
            Row(
                220f, 24f, Segment("籍贯", 40f, 88f), Segment("江苏省南京市", 120f, 264f), Segment("出生日期", 400f, 496f),
                Segment("1990年01月01日", 620f, 800f),
            ),
            Row(260f, 24f, Segment("本市（县）其他住址", 40f, 256f), Segment("南京市鼓楼区中山北路8号", 280f, 560f)),
            Row(
                300f, 24f, Segment("宗教信仰", 40f, 136f), Segment("无", 160f, 184f), Segment("公民身份号码", 400f, 544f),
                Segment("320106199001011238", 560f, 800f),
            ),
            Row(340f, 24f, Segment("何时由何地迁来本市（县）", 40f, 328f), Segment("2010年09月由江苏省苏州市迁来", 340f, 680f)),
        )
        assertThat(found("name", p)).containsExactly("张三", "张小三")
        assertThat(found("address", p))
            .containsExactly("江苏省南京市", "南京市鼓楼区中山北路8号", "2010年09月由江苏省苏州市迁来")
        assertThat(found("birthdate", p)).containsExactly("1990年01月01日")
    }

    /** 营业执照：两端对齐的「住　　　所」在右边一栏，折行的下一行对着值；左边一栏的经营范围不算。 */
    @Test fun `a business licence`() {
        val p = page(
            Row(
                200f, 26f, Segment("名　　　称", 60f, 190f), Segment("杭州某某科技有限公司", 220f, 480f),
                Segment("注册资本", 560f, 664f), Segment("壹佰万元整", 700f, 830f),
            ),
            Row(
                250f, 26f, Segment("法定代表人", 60f, 190f), Segment("陈一鸣", 220f, 298f), Segment("成立日期", 560f, 664f),
                Segment("2020年05月18日", 700f, 880f),
            ),
            Row(300f, 26f, Segment("经营范围", 60f, 164f), Segment("技术开发、技术服务", 220f, 460f)),
            Row(300f, 26f, Segment("住　　　所", 560f, 690f), Segment("浙江省杭州市西湖区三墩镇", 720f, 1032f)),
            Row(340f, 26f, Segment("软件开发；信息咨询", 220f, 460f)),
            Row(340f, 26f, Segment("西园八路3号1幢301室", 720f, 960f)),
        )
        assertThat(found("name", p)).containsExactly("陈一鸣")
        assertThat(found("address", p)).containsExactly("浙江省杭州市西湖区三墩镇", "西园八路3号1幢301室")
        assertThat(found("birthdate", p)).isEmpty()
    }

    @Test fun `a birth certificate`() {
        val p = page(
            Row(
                100f, 24f, Segment("新生儿姓名 Name", 40f, 230f), Segment("林小满", 260f, 332f), Segment("性别 Sex", 500f, 600f),
                Segment("女", 620f, 644f),
            ),
            Row(150f, 24f, Segment("出生时间 Date of Birth", 40f, 230f), Segment("2020年06月19日17时00分", 260f, 560f)),
            Row(200f, 24f, Segment("出生地点 Place of Birth", 40f, 230f), Segment("山东省 青岛市 黄岛区", 260f, 520f)),
            Row(300f, 24f, Segment("母亲姓名 Name", 40f, 230f), Segment("李四", 260f, 308f), Segment("年龄 Age", 400f, 500f), Segment("28", 520f, 548f)),
            Row(350f, 24f, Segment("父亲姓名 Name", 40f, 230f), Segment("林大海", 260f, 332f)),
        )
        assertThat(found("name", p)).containsExactly("林小满", "李四", "林大海")
        assertThat(found("birthdate", p)).containsExactly("2020年06月19日17时00分")
        assertThat(found("address", p)).containsExactly("山东省 青岛市 黄岛区")
    }

    /** 出生医学证明上预印着「省」「市」「县（区）」，填的字夹在中间，一段一段接到底。 */
    @Test fun `a birth place between preprinted units`() {
        val p = page(Row("出生地点 Place of Birth 山东 省 青岛 市 黄岛 县（区）", 40f, 200f, 24f))
        assertThat(found("address", p)).containsExactly("山东 省 青岛 市 黄岛 县（区）")
    }

    // ---------- 学生证、工作证、简历 ----------

    /** 只写到月的出生年月。 */
    @Test fun `a year and month of birth`() {
        val p = page(
            Row("姓名 张三", 60f, 100f, 30f),
            Row("出生年月 2002.03", 60f, 150f, 30f),
            Row("出生年月：1990年01月", 60f, 200f, 30f),
            Row("出生日期 1993-07", 60f, 250f, 30f),
            Row("学号 2020123456", 60f, 300f, 30f),
        )
        assertThat(found("birthdate", p)).containsExactly("2002.03", "1990年01月", "1993-07")
    }

    // ---------- 通行证 ----------

    /** 往来港澳通行证：「姓名 李晓明」下面一行和名字左对齐的拼音。 */
    @Test fun `the pinyin under a name on a travel permit`() {
        val p = page(
            Row(120f, 28f, Segment("姓名", 300f, 356f), Segment("李晓明", 380f, 464f)),
            Row(152f, 22f, Segment("LI, XIAOMING", 380f, 520f)),
            Row(190f, 28f, Segment("出生日期", 300f, 412f), Segment("1990.01.01", 436f, 576f)),
            Row(230f, 28f, Segment("性别", 300f, 356f), Segment("男", 380f, 408f)),
        )
        assertThat(found("name", p)).containsExactly("李晓明", "LI, XIAOMING")
    }

    /** 拼音只认整行大写的名字：名字下面的「OK」「Thanks a lot」是聊天。 */
    @Test fun `latin text under a name is not pinyin`() {
        val p = page(
            Row(200f, 30f, Segment("姓名", 60f, 120f), Segment("张三", 136f, 196f)),
            Row(236f, 26f, Segment("OK", 136f, 170f)),
            Row(320f, 30f, Segment("姓名", 60f, 120f), Segment("李四", 136f, 196f)),
            Row(356f, 26f, Segment("Thanks a lot", 136f, 280f)),
        )
        assertThat(found("name", p)).containsExactly("张三", "李四")
    }

    // ---------- 毕业证、学位证 ----------

    @Test fun `names and birth dates in the sentences of a diploma`() {
        val p = page(
            Row("学生 张三，性别 男，一九九八年三月五日生，于二〇一六年九月至", 60f, 300f, 30f),
            Row("二〇二〇年六月在本校计算机科学与技术专业四年制本科学习，", 60f, 340f, 30f),
            Row("李四，女，一九九九年十二月二十一日生。在本校", 60f, 500f, 30f),
        )
        assertThat(found("name", p)).containsExactly("张三", "李四")
        assertThat(found("birthdate", p)).containsExactly("一九九八年三月五日", "一九九九年十二月二十一日")
    }

    /** 学位证的一句在「男，」后面折了行。 */
    @Test fun `a degree sentence broken after the sex`() {
        val p = page(Row("王小明，男，", 60f, 300f, 30f), Row("2000年1月1日生。", 60f, 340f, 30f))
        assertThat(found("name", p)).containsExactly("王小明")
        assertThat(found("birthdate", p)).containsExactly("2000年1月1日")
    }

    // ---------- 护照 ----------

    /**
     * 中国护照资料页：字段名是「中文/English」、值在下面；「姓名/Name」下面两行，汉字名和拼音。
     * 值和中文左对齐，从英文那里量这一栏，值的头一个字就落在栏外了。
     */
    @Test fun `a chinese passport data page`() {
        val p = page(
            Row(80f, 14f, Segment("类型/Type", 300f, 370f), Segment("国家码/Country Code", 420f, 540f), Segment("护照号/Passport No.", 600f, 740f)),
            Row(98f, 22f, Segment("P", 305f, 318f), Segment("CHN", 425f, 480f), Segment("E12345678", 600f, 760f)),
            Row(130f, 14f, Segment("姓名/Name", 300f, 370f)),
            Row(148f, 22f, Segment("周以宁", 300f, 366f)),
            Row(174f, 22f, Segment("ZHOU, YINING", 300f, 480f)),
            Row(206f, 14f, Segment("性别/Sex", 300f, 360f), Segment("国籍/Nationality", 520f, 640f)),
            Row(224f, 22f, Segment("女/F", 300f, 350f), Segment("中国/CHINESE", 520f, 680f)),
            Row(256f, 14f, Segment("出生日期/Date of birth", 300f, 450f), Segment("出生地点/Place of birth", 520f, 680f)),
            Row(274f, 22f, Segment("14 FEB 1986", 300f, 450f), Segment("江苏/JIANGSU", 520f, 680f)),
            Row(306f, 14f, Segment("签发日期/Date of issue", 300f, 450f), Segment("签发地点/Place of issue", 520f, 680f)),
            Row(324f, 22f, Segment("15 3月 /MAR 2019", 300f, 470f), Segment("江苏/JIANGSU", 520f, 680f)),
        )
        assertThat(found("name", p)).containsExactly("周以宁", "ZHOU, YINING")
        assertThat(found("birthdate", p)).containsExactly("14 FEB 1986")
        assertThat(found("address", p)).containsExactly("江苏/JIANGSU")
        assertThat(found("passport", p)).containsExactly("E12345678")
    }

    /** 中国护照上「姓名/Name」的斜杠读成了「1」，或者读丢了。 */
    @Test fun `a chinese passport label whose slash was misread`() {
        listOf("姓名1Name", "姓名Name").forEach { label ->
            val p = page(
                Row(130f, 14f, Segment(label, 300f, 370f)),
                Row(148f, 22f, Segment("周以宁", 300f, 366f)),
                Row(174f, 22f, Segment("ZHOU, YINING", 300f, 480f)),
                Row(256f, 14f, Segment("出生日期1Date of birth", 300f, 450f)),
                Row(274f, 22f, Segment("14 FEB 1986", 300f, 450f)),
            )
            assertThat(found("name", p)).containsExactly("周以宁", "ZHOU, YINING")
            assertThat(found("birthdate", p)).containsExactly("14 FEB 1986")
        }
    }

    // ---------- 台湾身份证 ----------

    @Test fun `a taiwan id card with a roc date`() {
        val p = page(
            Row(100f, 24f, Segment("姓名", 40f, 88f), Segment("陳小華", 120f, 192f)),
            Row(150f, 24f, Segment("出生年月日", 40f, 160f), Segment("民國79年1月1日", 180f, 360f)),
        )
        assertThat(found("name", p)).containsExactly("陳小華")
        assertThat(found("birthdate", p)).containsExactly("民國79年1月1日")
    }

    // ---------- 不该认的 ----------

    /** 住址下面一行和字段名左对齐：那是下一个字段（「公民身份号码」）、或者聊天里下一句话。 */
    @Test fun `a line under the label is not the rest of the address`() {
        val p = page(
            Row(100f, 30f, Segment("住址", 70f, 130f), Segment("浙江省杭州市西湖区文三路", 180f, 560f)),
            Row(148f, 30f, Segment("记得带身份证原件过来", 70f, 370f)),
        )
        assertThat(found("address", p)).containsExactly("浙江省杭州市西湖区文三路")
    }

    /** 表格里字段名、值各成一行：「入住时间」右边那个「10月12日14:00后」对着地址的值，也不是地址。 */
    @Test fun `a value beside the next label is not the rest of the address`() {
        val p = page(
            Row(760f, 40f, Segment("酒店地址 南京市鼓楼区汉中路2号", 60f, 700f)),
            Row(830f, 40f, Segment("入住时间", 60f, 220f)),
            Row(830f, 40f, Segment("10月12日14:00后", 220f, 520f)),
        )
        assertThat(found("address", p)).containsExactly("南京市鼓楼区汉中路2号")
    }

    /** 聊天里提到身份证、有一行以「名」打头：认不成人像面，什么都不补。 */
    @Test fun `a chat about id cards is not an id card`() {
        val p = page(
            Row("名额已满，下次早点", 190f, 980f, 38f),
            Row("公民身份证号码要填吗", 190f, 1050f, 38f),
            Row("性别那栏填女", 190f, 1120f, 38f),
            Row("出生日期写身份证上的", 190f, 1190f, 38f),
        )
        assertThat(found("name", p)).isEmpty()
        assertThat(found("address", p)).isEmpty()
        assertThat(found("birthdate", p)).isEmpty()
    }

    /** 群聊里的「所有人」、条文里的「经营者」「法定代表人」不是字段名。 */
    @Test fun `common words that are also labels`() {
        val p = page(
            Row("@所有人 明天下午三点开会", 190f, 350f, 38f),
            Row("所有人都到齐了吗", 190f, 420f, 38f),
            Row("法定代表人签字的那份合同放哪了", 190f, 490f, 38f),
            Row("经营者应当依法纳税", 190f, 560f, 38f),
            Row("监护人陪同未成年人入场", 190f, 630f, 38f),
            Row("好的，男，女都可以", 190f, 700f, 38f),
            Row("学生会主席 李四，性别不限", 190f, 770f, 38f),
        )
        assertThat(found("name", p)).isEmpty()
    }

    @Test fun `a date followed by a word starting with sheng is not a birth date`() {
        val p = page(Row("新规2026年9月3日生效", 60f, 100f), Row("本品2026年8月1日生产", 60f, 140f))
        assertThat(found("birthdate", p)).isEmpty()
    }
}
