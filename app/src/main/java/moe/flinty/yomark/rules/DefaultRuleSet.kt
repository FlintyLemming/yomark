package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.rules.validator.Checksums
import moe.flinty.yomark.rules.validator.KnownTlds
import moe.flinty.yomark.R

/**
 * 全部规则（spec §6 + 2026-09-04 增补 §3）。17 条：12 条默认打码、5 条仅圈出。
 * 一种类型一条规则，设置页上一类一行；同一类型的几种认法合成一条（CompositeRule）。
 *
 * 「默认」按**误报率**划线，不按危害划线：高误报类型自动打码会让用户
 * 一直在跟 app 对着干；有校验位兜底的类型误报接近零，自动打码不打扰任何人。
 */
object DefaultRuleSet {

    // ---------- 高置信：有校验位兜底，误报接近零，默认打码 ----------

    private val CARD = RegexRule(
        id = "card",
        kind = SensitiveKind.PAYMENT_CARD,
        enabledByDefault = true,
        // 13–19 位数字，容忍空格与连字符分组；前后不能紧邻数字
        pattern = Regex("""(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.luhn(m.value.filter { it.isDigit() }) },
        // 机读码第二行里一长串数字，十回里有一回碰巧过得了 Luhn
        yieldsTo = setOf(SensitiveKind.PASSPORT),
    )

    private val IBAN = RegexRule(
        id = "iban",
        kind = SensitiveKind.IBAN,
        enabledByDefault = true,
        pattern = Regex("""(?<![A-Z0-9])[A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{1,4}){2,8}(?![A-Z0-9])"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.ibanValid(m.value) },
    )

    private val SSN = RegexRule(
        id = "ssn",
        kind = SensitiveKind.SSN,
        enabledByDefault = true,
        pattern = Regex("""(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)"""),
        confidence = 0.95f,
        validate = { _, m -> Checksums.ssnValid(m.value) },
    )

    private val MAC = RegexRule(
        id = "mac",
        kind = SensitiveKind.MAC_ADDR,
        enabledByDefault = true,
        pattern = Regex("""(?<![0-9A-Fa-f:-])(?:[0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}(?![0-9A-Fa-f:-])"""),
        confidence = 0.9f,
        validate = { _, m -> Checksums.macSeparatorConsistent(m.value) },
    )

    private val EMAIL = RegexRule(
        id = "email",
        kind = SensitiveKind.EMAIL,
        enabledByDefault = true,
        // RFC 5322 简化式
        pattern = Regex("""(?<![A-Za-z0-9._%+-])[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.([A-Za-z]{2,24})(?![A-Za-z0-9.-])"""),
        confidence = 0.9f,
        validate = { _, m -> KnownTlds.isKnown(m.groupValues[1]) },
    )

    /**
     * 护照号的形状：「E00007730」「123456789」「EA1234567」「C01X00T47」——至多三个字母开头，后面字母数字，6 到 12 位。
     * 只跟在字段名后面认，单独一串字母数字什么都可能是。
     */
    private const val DOCUMENT_NUMBER = """[A-Z]{0,3}\d[A-Z0-9]{4,11}"""
    private val DOCUMENT_NUMBER_ALONE = Regex("""(?<![A-Za-z0-9])$DOCUMENT_NUMBER(?![A-Za-z0-9])""")

    /**
     * 护照 = 机读码 + 资料页上的护照号。
     *
     * 机读码见 Mrz：去掉 OCR 补的空格、放宽填充符的个数，靠填充符或校验位认。护照号三种认法：
     * 同一行的「Passport No.: E12345678」；字段名在上、号码在下的资料页（StackedField）；
     * 机读码里读出的证件号在页上别处又出现（MrzEcho），字段名是哪国文字都不要紧。
     */
    private val PASSPORT = CompositeRule(
        id = "passport",
        kind = SensitiveKind.PASSPORT,
        enabledByDefault = true,
        MrzLines(confidence = 0.95f),
        LabeledField(
            labels = listOf(
                "Passport No.", "Passport No", "Passport Number", "Passport #", "Document No.", "Document No",
                "Document Number", "护照号码", "护照号",
            ),
            value = Regex("""$DOCUMENT_NUMBER(?![A-Za-z0-9])"""),
            confidence = 0.8f,
            ignoreCase = true,
        ),
        StackedField(
            StackedField.document(StackedField.DOCUMENT_NUMBER_LABELS),
            value = { cell -> DOCUMENT_NUMBER_ALONE.find(cell)?.range },
            confidence = 0.8f,
            sameLine = true,
        ),
        MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, confidence = 0.9f),
    )

    private val API_KEY = RegexRule(
        id = "apikey",
        kind = SensitiveKind.API_KEY,
        enabledByDefault = true,
        pattern = Regex("""(?<![A-Za-z0-9_-])(?:sk-|ghp_|gho_|ghs_|AKIA|eyJ)[A-Za-z0-9_\-.]{12,}"""),
        confidence = 0.9f,
        validate = { _, m -> Checksums.shannonEntropy(m.value) > 3.5 },
    )

    /**
     * 姓名规则拿它找锚点，所以单独拎出来、与规则表共用同一个实例。
     * 电话规则被用户关掉时，它仍然替姓名规则找锚——关的是「遮电话」，不是「认电话」。
     */
    private val PHONE = PhoneRule()

    // ---------- 姓名、地址、取件码：标签锚定为主，形状与邻居补漏 ----------

    /** 物流轨迹里「签收人：」后面常见的非人名：「本人」「菜鸟驿站」「门卫」。 */
    private const val NOT_A_NAME_VALUE = "本人|他人|家人|同事|邻居|门卫|保安|前台|物业|收发室|快递柜|驿站|代收"

    /** 名字后面紧跟着的下一个字段名（证件上一行几个字段、逐字切分拼回来时连在一起）：名字到它为止。 */
    private const val NEXT_FIELD = "电话|手机|性别|性別|民族|出生|地址|住址|身份|证件|联系|国籍|國籍|籍贯|户主|曾用名"

    /** 名字里的一个字：不是下一个字段名、也不是「驿站」「门卫」这类非人名的开头。 */
    private const val NAME_CHAR = """(?:(?!$NEXT_FIELD|$NOT_A_NAME_VALUE)[\u4e00-\u9fa5])"""

    /**
     * 姓名。值遇数字即停——同一行的电话归 PhoneRule 管，两条规则各出各的候选。
     *
     * 五个分支对应五种排版：带间隔点的少数民族名（「阿依古丽·买买提」，点常被读成「•」「.」）、连写的汉字名、
     * 字距拉开后被空格隔开的名（隔得不匀的「周 以宁」也算）、证件上「姓, 名」的拼音（「ZHANG, SAN」「SMITH,JOHN」，姓全大写）、拉丁名。
     * 连写分支在拆字分支前面，`张三 性别 男` 才会只取到 `张三`；它还会在下一个字段名前停下，
     * 逐字切分的行拼回来成了「张三手机」「张三户主或与户主关系」时，不把后面的也吞进去。
     *
     * 值里含 NOT_A_NAME_VALUE 的词、或者紧跟着它们，整个值作废，而不是停在它们前面——
     * 否则「菜鸟驿站」会剩下「菜鸟」两个字被当成名字。
     */
    private val NAME_VALUE = Regex(
        """[\u4e00-\u9fa5]{1,8}(?:[·•・.．‧](?:(?!$NEXT_FIELD)[\u4e00-\u9fa5]){1,8}){1,3}""" +
            """|$NAME_CHAR{2,6}(?!$NOT_A_NAME_VALUE)""" +
            """|$NAME_CHAR{1,2}(?: $NAME_CHAR{1,2}){1,4}""" +
            """|[A-Z][A-Z'\-]* ?, ?[A-Z][A-Za-z.'\-]*(?:[ ][A-Z][A-Za-z.'\-]*){0,2}""" +
            """|[A-Za-z][A-Za-z.'\-]*(?:[ ][A-Za-z][A-Za-z.'\-]*){0,2}"""
    )

    /** 人名字段名。姓名规则拿它锚定取值，从字面猜出的名字也拿它当旁证（见 nameAnchors）。 */
    private val NAME_LABELS = listOf(
        "收货人", "收件人", "寄件人", "发件人", "签收人", "取件人", "提货人", "联系人",
        "收款人", "付款人", "持卡人", "开户人", "申请人", "真实姓名", "姓名", "户名", "开户名",
        "快递员", "派件员", "配送员", "收派员", "Name", "Recipient",
        // 户口簿、出生医学证明、结婚证，日本的在留卡、驾照
        "户主姓名", "新生儿姓名", "母亲姓名", "父亲姓名", "配偶姓名", "氏名",
    )

    /**
     * 也是常用词的人名字段名：行驶证的「所有人 Owner」，营业执照的「法定代表人」「经营者」「投资人」「执行事务合伙人」，
     * 结婚证的「持证人」，户口簿的「曾用名」，「监护人」。群聊里的「@所有人」「所有人都到了」「曾用名也要写吗」、
     * 条文里的「经营者应当」「法定代表人签字」也是它们，所以后面要隔着空格、冒号或英文译名才算（LabeledField 的 spaced），
     * 也不当旁证。
     */
    private val COMMON_WORD_NAME_LABELS =
        listOf("所有人", "持证人", "法定代表人", "经营者", "投资人", "执行事务合伙人", "监护人", "曾用名")

    /**
     * 跟在名字后面的称呼。认名字时拿它补全「赵」+「师傅」、放过「张三老师」「张老师好」（见 PersonNameRecognizer）；
     * 紧挨着一个猜出来的名字，它就是这个名字的旁证（见 nameAnchors）。
     */
    internal val PERSON_TITLES = listOf(
        "先生", "女士", "小姐", "师傅", "老师", "同学", "经理", "总监", "主任", "医生", "大夫", "护士",
        "律师", "教授", "博士", "阿姨", "叔叔", "伯伯", "大爷", "大妈", "老板", "同志",
    )

    /**
     * 姓名 = 字段名后面的值 + 电话前面的名字 + 从字面认出的名字。
     *
     * 后两种都是真机漏检补的。电话前面的：菜鸟快递详情页的收件人一行是「沐晨冉 86-186****3392」，
     * 没有任何字段名，见 NameBeforePhone。从字面认的（HanLP，见 PersonNameRecognizer）补既没有字段名、
     * 旁边也没有电话的：滴滴出票页的乘车人一行是「沐晨冉 成人」，下面是打了星的身份证号。
     *
     * 从字面认的误报率比前两种高一个量级：药名、品牌名、规格词都会被认成人名（真机上一整页商品规格被圈满）。
     * 所以它排在最后，只补前两种够不着的地方，而且只算猜测（NeedsAnchor）：同一行或上下相邻一行有
     * 电话、证件号、地址、「乘车人」这类字段的才算数，照这一行的设置打码；附近什么都没有的，出厂丢掉，
     * 设置 › 文字 › 人名里打开「没有旁证的也圈出」才只圈不打码。
     * 它原先单独成一条规则，设置页上另有一行「人名（无字段名）」；两行都是人名，用户分不清关的是哪一个，
     * 于是并了回来：一行「人名」管这几种认法，一起开关。
     *
     * 英文的两种（2026-10）：[EnglishNameCues] 是英文界面上自己说明「这是人」的写法（「Ship to:」「Hi David,」
     * 「Sarah Johnson <…@…>」），与字段名同一档；[EnglishNameGuess] 按名单从字面猜「Emily Carter」，
     * 与 HanLP 同一档，要旁证。调研与评测见 docs/english-recognition-research.md。
     *
     * 证件上的（2026-10，用户反馈身份证遮不全）：身份证人像面上字段名读坏了的那一行（[IdCardFront]），
     * 毕业证、学位证正文里的「张三，男，……生」（[DiplomaName]）。
     */
    private val NAME = CompositeRule(
        id = "name",
        kind = SensitiveKind.PERSON_NAME,
        enabledByDefault = true,
        LabeledField(labels = NAME_LABELS, value = NAME_VALUE, confidence = 0.8f, spaced = COMMON_WORD_NAME_LABELS),
        NameBeforePhone(PHONE, confidence = 0.7f),
        EnglishNameCues(confidence = 0.8f, weakConfidence = 0.7f),
        StackedField(StackedField.document(StackedField.NAME_LABELS), ::documentNameIn, confidence = 0.8f),
        // 中国护照的「姓名/Name」下面两行：汉字名、拼音
        StackedField(StackedField.document(StackedField.CHINESE_NAME_LABELS), ::documentNameIn, confidence = 0.8f, maxLines = 2),
        StackedField(StackedField.header(StackedField.NAME_HEADER), ::formNameIn, confidence = 0.7f),
        MrzEcho(MrzEcho.What.NAME, confidence = 0.8f),
        IdCardFront(IdCardFront.What.NAME, confidence = 0.8f),
        DiplomaName(confidence = 0.8f),
        NeedsAnchor(PersonNameRecognizer(confidence = 0.6f, titles = PERSON_TITLES)),
        NeedsAnchor(EnglishNameGuess(confidence = 0.6f)),
    )

    /**
     * 证件上「Surname」「Given names」下面的名字：没有数字，六个词以内，第一个词大写开头（护照上全大写），
     * 可夹着汉字（「张三 ZHANG SAN」）。字段名已经说明了这是名字，不再对名单。
     */
    private val DOCUMENT_NAME = Regex("""\p{L}[\p{L}'’.\-]*(?:[ ,/]+\p{L}[\p{L}'’.\-]*){0,5}""")

    private fun documentNameIn(cell: String): IntRange? =
        cell.indices.takeIf { DOCUMENT_NAME.matches(cell) && !cell.first().isLowerCase() }

    /** 表单上单独一行的「Name」「Customer」下面那个：与「Name: …」同一套值的形状，「Order Details」、品牌名不算。 */
    private fun formNameIn(cell: String): IntRange? =
        cell.indices.takeIf { EnglishNameCues.VALUE.matches(cell) && EnglishNameCues.looksLikeNameValue(cell) }

    /**
     * 地址。**不能用姓名那套「遇数字即停」**——门牌号、楼层、邮编本来就是地址的一部分。
     * 所以取到行尾，上限 60 字符；首尾都卡非空白，免得把标签后的空格也遮进去。
     *
     * 因此「收货地址 …… 电话 138……」会把电话一起遮掉。这是过遮，方向是安全的
     * （spec §13：多遮一块只是麻烦，漏遮一块是事故），何况电话本来也该遮。
     */
    private val ADDRESS_VALUE = Regex("""[^\s/／|｜].{2,58}\S""")

    private val ADDRESS_LABELS = listOf(
        "收货地址", "收件地址", "寄件地址", "发货地址", "退货地址", "取件地址", "寄送地址",
        "送货地址", "配送地址", "送达地址", "详细地址", "联系地址", "通讯地址", "家庭地址",
        "居住地址", "户籍地址", "家庭住址", "现住址", "所在地区", "住址", "地址",
        "配送至", "送货至", "送至", "寄至", "Address", "Ship to", "Deliver to",
        // 营业执照（「住　　所」两端对齐）、户口簿、居住证，日本的在留卡、驾照
        "住所", "经营场所", "主要经营场所", "营业场所", "户籍所在地", "现居住地址", "现居住地", "居住地", "住居地",
        "本市（县）其他住址", "本市(县)其他住址", "何时由何地迁来本市（县）", "何时由何地迁来本市(县)", "何时由何地迁来本址",
    )

    /** 地址字段名后面那一段。证件上折到下一行的那几行由 [FollowingLines] 从它往下接。 */
    private val ADDRESS_FIELD =
        LabeledField(labels = ADDRESS_LABELS, value = ADDRESS_VALUE, confidence = 0.8f, nextField = ::startsWithField)

    /**
     * 证件、表格上姓名、地址、出生日期以外的字段名。户口簿一行有好几个字段（「出生地 江苏省南京市 民族 汉」），
     * 出生地遮到它们前面为止；住址折行时，以它们打头的一行是下一个字段（见 FollowingLines）。
     */
    private val OTHER_FIELDS = listOf(
        "公民身份号码", "身份证", "证件号", "证号", "号码", "性别", "民族", "国籍", "籍贯", "签发机关", "签发日期", "有效期",
        "档案编号", "准驾车型", "初次领证", "发证", "注册日期", "使用性质", "品牌型号", "车辆", "发动机", "号牌",
        "户号", "户别", "宗教", "文化程度", "婚姻", "兵役", "服务处所", "职业", "身高", "血型", "登记日期", "承办人",
        "经营范围", "成立日期", "注册资本", "类型", "名称", "营业期限", "电话", "手机", "邮编", "邮政编码", "备注",
    )

    /**
     * 出生地：户口簿、出生医学证明、护照上常常只写一个省（「江苏」），比住址短。
     * 出生医学证明上省市县之间隔着空格（「山东省 青岛市 黄岛区」），一段一段接，到下一个字段名为止。
     */
    private val BIRTH_PLACE_FIELD = LabeledField(
        labels = listOf("出生地点", "出生地"),
        value = Regex("""[^\s/／|｜]\S{1,29}(?: (?!${OTHER_FIELDS.joinToString("|")})\S{1,29}){0,3}"""),
        confidence = 0.8f,
        nextField = ::startsWithField,
    )

    /**
     * 地址 = 字段名后面的值 + 长得像门牌的串。
     *
     * 后一种是真机漏检补的：菜鸟快递详情页只写「送至」，淘宝收货卡片什么都不写，
     * 驿站地址夹在一句通知中间。见 AddressShape；英文的门牌、城市州邮编、各国邮编见 EnglishAddressShape。
     *
     * 证件上的住址一行写不下，折到下面不带字段名的几行，见 FollowingLines；
     * 身份证上「住址」读坏了的，按人像面的排版认，见 IdCardFront。
     */
    private val ADDRESS = CompositeRule(
        id = "address",
        kind = SensitiveKind.POSTAL_ADDRESS,
        enabledByDefault = true,
        ADDRESS_FIELD,
        FollowingLines(ADDRESS_FIELD, ::startsWithField, confidence = 0.8f),
        BIRTH_PLACE_FIELD,
        IdCardFront(IdCardFront.What.ADDRESS, confidence = 0.8f),
        StackedField(StackedField.document(StackedField.BIRTH_PLACE_LABELS), ::placeIn, confidence = 0.8f),
        StackedField(StackedField.header(StackedField.ADDRESS_HEADER), ::addressLineIn, confidence = 0.7f, maxLines = 4),
        AddressShape(confidence = 0.7f),
        EnglishAddressShape(confidence = 0.7f),
    )

    /** 出生地：护照上是「WASHINGTON, D.C., U.S.A.」「广东 GUANGDONG」。有字母、没有邮箱，八个词以内，整段遮。 */
    private fun placeIn(cell: String): IntRange? =
        cell.indices.takeIf { cell.count(Char::isLetter) >= 2 && '@' !in cell && cell.split(' ').size <= 8 }

    /**
     * 表单上「Shipping Address」下面的一行：整行遮，到邮箱、电话那一行为止（那两样有自己的规则），
     * 也到地址块下面那些界面上的字为止（[NOT_AN_ADDRESS_LINE]）。
     */
    private fun addressLineIn(cell: String): IntRange? {
        if ('@' in cell || cell.length > 60 || NOT_AN_ADDRESS_LINE.containsMatchIn(cell)) return null
        val digits = cell.count(Char::isDigit)
        if (digits >= 7 && digits * 2 >= cell.count { !it.isWhitespace() }) return null
        return cell.indices.takeIf { cell.any(Char::isLetterOrDigit) }
    }

    /**
     * 地址块下面常跟着的、不是地址的行（英文名址基准上量出来的）：勾选项「Same as billing address」、
     * 链接「Get directions」、送餐备注「Meet at door」、联系人卡片上地址的类型「Home」。
     */
    private val NOT_AN_ADDRESS_LINE = Regex(
        """(?i)^(?:(?:same as|get directions|directions|view (?:on )?map|open in|meet at|leave at|hand it|""" +
            """(?:add )?(?:delivery )?instructions|edit|change|copy)\b|(?:home|work|personal|office|other|main|primary|default)$)"""
    )

    /**
     * 取件码（真机漏检补的）。驿站、快递柜凭它取件，截图里露出来，
     * 看到的人就能去把包裹拿走——危害比快递单号直接得多。
     *
     * 只认字段名后面的值，误报率低，所以默认打码。值至少含一位数字，
     * 「请凭取货码及时领取」这种说明文字不会被当成值。
     */
    private val PICKUP_CODE = CompositeRule(
        id = "pickup",
        kind = SensitiveKind.PICKUP_CODE,
        enabledByDefault = true,
        LabeledField(
            labels = listOf("取件码", "取货码", "提货码", "取件号", "自提码"),
            value = Regex("""[A-Za-z]{0,3}\d[A-Za-z0-9]*(?:\s?[-－]\s?[A-Za-z0-9]+)*"""),
            confidence = 0.8f,
        ),
    )

    private val BIRTH_DATE_LABELS = listOf(
        "出生日期", "出生年月日", "出生年月", "出生时间", "出生", "生年月日", "生日",
        "Date of birth", "Birth date", "Birthdate", "Birthday", "DOB", "D.O.B.", "Born",
    )

    /**
     * 出生日期：字段名指明了的那个日期，误报少，默认打码；没有字段名的日期仍归日期时间，只圈出。
     *
     * 五种认法：同一行的「出生日期：1990-01-01」「DOB 01/07/1973」「出生 1990年1月1日」（身份证）；
     * 字段名在上、日期在下的护照资料页（「Date of birth」下面的「05 FEB 1965」）；机读码里的生日在页上别处出现
     * （幽灵像旁边那行「05 02 65」）；身份证号里的生日在页上别处出现（IdBirthEcho：「出生」读坏了的那一行也遮得上）；
     * 毕业证、日本证件上没有字段名、后面跟着「生」的「一九九八年三月五日生」（BornOn）。
     * 前面写着字段名，日期可以写得随意些：两位年、空格分隔、数字里夹空格、民国和年号纪年都认（Dates.LENIENT）。
     */
    private val BIRTH_DATE = CompositeRule(
        id = "birthdate",
        kind = SensitiveKind.BIRTH_DATE,
        enabledByDefault = true,
        LabeledField(labels = BIRTH_DATE_LABELS, value = Regex(Dates.LENIENT), confidence = 0.8f, ignoreCase = true),
        StackedField(
            StackedField.document(StackedField.BIRTH_DATE_LABELS),
            value = Dates::findLenient,
            confidence = 0.8f,
            sameLine = true,
        ),
        MrzEcho(MrzEcho.What.BIRTH_DATE, confidence = 0.9f),
        IdBirthEcho(confidence = 0.9f),
        BornOn(confidence = 0.8f),
    )

    /**
     * 住址折行时，以字段名（OTHER_FIELDS，姓名、地址、出生日期的字段名，英文译名）打头的一行是下一个字段，
     * 不是住址的下半截（见 FollowingLines）。
     */
    private val FIELD_START = Regex(
        "(?i)^(?:" + (OTHER_FIELDS + NAME_LABELS + COMMON_WORD_NAME_LABELS + ADDRESS_LABELS + BIRTH_DATE_LABELS +
            LabeledField.TRANSLATIONS).map { it.replace(" ", "") }.distinct().sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) } + ")"
    )

    private fun startsWithField(line: String): Boolean = FIELD_START.containsMatchIn(line.filterNot { it.isWhitespace() })

    // ---------- 仅圈出：误报率高，靠导出拦截兜底（spec §6 / §7.4） ----------

    private val URL_PATTERN = Regex(
        """(?<![A-Za-z0-9@._-])(?:https?://)?[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.([A-Za-z]{2,24})(/[^\s]*)?"""
    )

    private val URL = RegexRule(
        id = "url",
        kind = SensitiveKind.URL,
        enabledByDefault = false,
        pattern = URL_PATTERN,
        confidence = 0.7f,
        validate = { _, m -> KnownTlds.isKnown(m.groupValues[1]) && m.groupValues[2].length > 1 },
        // 只遮 path 与 query，域名保留
        select = { m ->
            val path = m.groups[2]!!
            path.range
        },
    )

    private val IP = RegexRule(
        id = "ip",
        kind = SensitiveKind.IP_ADDR,
        enabledByDefault = false,
        pattern = Regex(
            """(?<![\w.:])(?:(?:\d{1,3}\.){3}\d{1,3}|(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4})(?![\w.:])"""
        ),
        confidence = 0.6f,
        validate = { text, m -> validIpAndNotAVersion(text, m) },
    )

    private fun validIpAndNotAVersion(text: String, m: MatchResult): Boolean {
        val v = m.value
        if (v.contains(':')) return true                      // IPv6：正则本身已足够严格

        // 各段数值范围
        val parts = v.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.length > 1 && it.startsWith("0") }) return false
        if (parts.any { (it.toIntOrNull() ?: return false) !in 0..255 }) return false

        // 排除版本号误报：紧邻的 v / version 前缀
        val before = text.substring(0, m.range.first).takeLast(12).lowercase()
        if (before.trimEnd().endsWith("v") || before.contains("version")) return false
        return true
    }

    private val TRACKING = object : Rule {
        override val id = "tracking"
        override val kind = SensitiveKind.TRACKING_NO
        override val enabledByDefault = false
        override val yieldsTo = setOf(SensitiveKind.PASSPORT)

        private val ups = Regex("""(?<![A-Za-z0-9])1Z[0-9A-Za-z]{16}(?![A-Za-z0-9])""")
        // FedEx 12/15 位、USPS 20/22 位：无校验位，置信度调低
        private val plainDigits = Regex("""(?<![\d-])(?:\d{12}|\d{15}|\d{20}|\d{22})(?![\d-])""")

        override fun findIn(text: String): List<RuleMatch> {
            val out = ArrayList<RuleMatch>()
            ups.findAll(text)
                .filter { Checksums.upsCheckDigit(it.value) }
                .forEach { out += RuleMatch(it.range, 0.85f) }
            plainDigits.findAll(text)
                .filter { d -> out.none { it.range.first <= d.range.last && d.range.first <= it.range.last } }
                .forEach { out += RuleMatch(it.range, 0.5f) }   // 无校验位 → 低置信度
            return out
        }
    }

    /**
     * 长数字串兜底（spec §15 第 5 条的实测漏检）。
     *
     * 实测：真实淘宝订单页上的 28 位支付宝交易号不被任何规则命中——
     * CARD 限 13–19 位、TRACKING 限 12/15/20/22 位，28 位落在所有窗口之外。
     * 漏检是事故，所以补一条不看语义、只看长度的兜底。
     *
     * 三条边界，都是为了不跟已有规则抢同一串数字（合并只在同 kind 之间做，
     * 抢起来会在同一处叠出两个候选）：
     * - 下限 16 位：E.164 电话最长 15 位，兜底与 PhoneRule 完全不重叠；
     * - 排除 20/22 位：那是 TRACKING 的 USPS 窗口；
     * - 排除 13–19 位里 Luhn 通过的：那是 CARD 的地盘。
     *   只在 CARD 的窗口内让位——别处 Luhn 碰巧通过（约十分之一）不该让兜底失灵。
     *
     * 没有任何校验位，误报天然多（时间戳拼接、纯序号都会撞上），
     * 因此按 §6「默认按误报率划线」默认仅圈出，靠导出拦截兜底。
     */
    private val LONG_NUMBER = object : Rule {
        override val id = "longnum"
        override val kind = SensitiveKind.LONG_NUMBER
        override val enabledByDefault = false
        override val yieldsTo = setOf(SensitiveKind.PASSPORT)

        private val run = Regex("""(?<![A-Za-z0-9])\d{16,}(?![A-Za-z0-9])""")
        private val trackingLengths = setOf(20, 22)

        override fun findIn(text: String): List<RuleMatch> =
            run.findAll(text)
                .filter { m ->
                    val v = m.value
                    when {
                        v.length in trackingLengths -> false
                        v.length in 13..19 && Checksums.luhn(v) -> false
                        else -> true
                    }
                }
                .map { RuleMatch(it.range, 0.4f) }      // 无校验位 → 低置信度
                .toList()
    }

    val rules: List<Rule> = listOf(
        CARD, IBAN, SSN, MAC, EMAIL, PHONE, PASSPORT, API_KEY, NAME, ADDRESS, PICKUP_CODE, BIRTH_DATE,
        URL, IP, TRACKING, LONG_NUMBER, DateTimeRule(),
    )

    // ---------- 人名的旁证 ----------

    /** 18 位身份证号，查校验码。 */
    private val ID_NUMBER = Regex("""(?<![0-9A-Za-z])\d{17}[\dXx](?![0-9A-Za-z])""")

    /**
     * 打了星的号码：证件号「3201**********1234」、卡号「6222****1234」、手机号「138****6612」。
     * 打星本身就说明 app 认为这是某个人的号码。
     */
    private val MASKED_NUMBER = Regex("""(?<![\d*＊])\d{2,6}\s?[*＊•●]{3,}\s?\d{2,4}[\dXx]?(?![\d*＊])""")

    /**
     * 说明「这里是一个人」的字段名，姓名规则的字段名之外的：出行、住宿、就诊、保险、转账页上的。
     * 它们不拿来锚定取值——「乘客 1 位」「抄送 3 人」后面跟的不是名字——只当旁证。
     */
    private val PERSON_FIELDS = listOf(
        "乘车人", "乘客", "旅客", "乘机人", "出行人", "入住人", "住客", "预订人", "订票人", "购票人",
        "就诊人", "患者", "投保人", "被保险人", "受益人", "审批人", "经办人", "抄送", "对方账户", "收款方",
    )

    /**
     * 英文的人名字段名：只当旁证，同 PERSON_FIELDS。不分大小写，前后要是词边界（「Username」「Guestbook」不算）。
     * 收货卡片、表单上字段名常单独占一行，名字在下一行：「Passenger」下面是「Emily Carter」。
     */
    private val ENGLISH_PERSON_FIELDS = Regex(
        """(?i)\b(?:full name|first name|last name|legal name|name|recipient|passenger|traveler|traveller|guest|""" +
            """customer|sender|payee|beneficiary|patient|policy ?holder|card ?holder|account holder|applicant|tenant|""" +
            """ship to|bill to|deliver to|attn|emergency contact)\b"""
    )

    /** 票种：滴滴出票页那一行就是「沐晨冉 成人」。 */
    private val TICKET_TYPES = listOf("成人票", "儿童票", "学生票", "婴儿票", "成人", "儿童", "学生", "婴儿")

    /** 正则找形状，[valid] 再验一道。 */
    private fun shape(pattern: Regex, valid: (String) -> Boolean = { true }) = Finder { text ->
        pattern.findAll(text).filter { valid(it.value) }.map { RuleMatch(it.range, 1f) }.toList()
    }

    /**
     * 人名的旁证表。从字面猜出的人名（NeedsAnchor）要有其中一处才算数，见 NameAnchor、RuleClassifier。
     * **加一种旁证就是在这里加一行**，再到 NameAnchorPageTest 的例子表里配一个例子。
     *
     * 与姓名规则拿电话找锚同一个道理：电话、地址规则被用户关掉时照样算旁证——关的是「遮」，不是「认」。
     */
    val nameAnchors: List<NameAnchor> = listOf(
        NameAnchor(R.string.anchor_title, NameAnchor.Reach.ATTACHED, Words(PERSON_TITLES)),
        NameAnchor(R.string.anchor_phone, NameAnchor.Reach.NEARBY, PHONE),
        NameAnchor(R.string.anchor_email, NameAnchor.Reach.NEARBY, EMAIL),
        NameAnchor(R.string.anchor_address, NameAnchor.Reach.NEARBY, ADDRESS),
        NameAnchor(R.string.anchor_id, NameAnchor.Reach.NEARBY, shape(ID_NUMBER, Checksums::chineseIdValid)),
        NameAnchor(R.string.anchor_masked_number, NameAnchor.Reach.NEARBY, shape(MASKED_NUMBER)),
        NameAnchor(
            R.string.anchor_field, NameAnchor.Reach.NEARBY,
            Words(NAME_LABELS + PERSON_FIELDS, boundedBefore = true),
        ),
        NameAnchor(R.string.anchor_ticket, NameAnchor.Reach.NEARBY, Words(TICKET_TYPES, boundedBefore = true, boundedAfter = true)),
        NameAnchor(R.string.anchor_english_field, NameAnchor.Reach.NEARBY, shape(ENGLISH_PERSON_FIELDS)),
    )
}
