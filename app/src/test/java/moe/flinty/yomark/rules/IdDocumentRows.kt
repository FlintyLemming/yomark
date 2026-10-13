package moe.flinty.yomark.rules

/**
 * 几张证件页的版面：IdDocumentPageTest 逐类看认出了什么，RotatedPageRuleTest 把它们转过来再看一遍。内容全是编的。
 */
internal object IdDocumentRows {

    /**
     * 合成卡（tools/test-images/IdCardPage.java）上真 OCR 读出来的六行：字段名「姓 名」拉开，住址第二行和值左对齐、
     * 不带字段名，号码字距宽。坐标照着生成器的排版。
     */
    val idCard = listOf(
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

    /**
     * 用户拍的那张的读法：「姓」读丢了，「性别」读成「性新」，年份里夹着空格，号码拆成几段、头一位粘在「码」上。
     * 只照抄 OCR 读坏的样子，名字、号码、生日都换了。0.15.0 上只认出了住址。
     */
    val idPhoto = listOf(
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

    /**
     * 驾驶证正页（tools/test-images/DriverLicensePage.java）上真 OCR 读出来的样子：中文字段名后面跟着小字英文，
     * 值在右边一栏，隔得远，字段名和值成了同一排的两行；「周以宁」读成了「周 以宁」，住址折成两行。
     */
    val drivingLicence = listOf(
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

    /** 行驶证：中英文字段名，值在右边，住址折行，车架号认错了一位（校验位对不上）。 */
    val vehicleLicence = listOf(
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

    /**
     * 中国护照资料页：字段名是「中文/English」、值在下面；「姓名/Name」下面两行，汉字名和拼音。
     * 值和中文左对齐，从英文那里量这一栏，值的头一个字就落在栏外了。
     */
    val chinesePassport = listOf(
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

    /** 往来港澳通行证：「姓名 李晓明」下面一行和名字左对齐的拼音。 */
    val travelPermit = listOf(
        Row(120f, 28f, Segment("姓名", 300f, 356f), Segment("李晓明", 380f, 464f)),
        Row(152f, 22f, Segment("LI, XIAOMING", 380f, 520f)),
        Row(190f, 28f, Segment("出生日期", 300f, 412f), Segment("1990.01.01", 436f, 576f)),
        Row(230f, 28f, Segment("性别", 300f, 356f), Segment("男", 380f, 408f)),
    )
}
