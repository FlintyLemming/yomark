package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.rules.validator.Checksums
import moe.flinty.yomark.rules.validator.KnownTlds
import moe.flinty.yomark.R
import java.time.LocalDate

/**
 * 全部规则（spec §6 + 2026-09-04 增补 §3）。19 条：15 条出厂打码、4 条出厂关着（误报率高，原先只圈出，2026-10-14 起没有这一档）。
 * 一种类型一条规则，设置页上一类一行；同一类型的几种认法合成一条（CompositeRule）。
 *
 * 「默认」按**误报率**划线，不按危害划线：高误报类型自动打码会让用户
 * 一直在跟 app 对着干；有校验位兜底的类型误报接近零，自动打码不打扰任何人。
 */
object DefaultRuleSet {

    // ---------- 高置信：有校验位兜底，误报接近零，默认打码 ----------

    /** 13–19 位数字，容忍空格与连字符分组；前后不能紧邻数字。 */
    private val CARD_NUMBER = Regex("""(?<![\d-])(?:\d[ -]?){12,18}\d(?![\d-])""")

    /** 卡面上的「月/年」：「12/28」「09/2031」「12 / 28」。 */
    private const val CARD_MONTH_YEAR = """(?:0[1-9]|1[0-2]) ?/ ?(?:20)?\d{2}"""

    /**
     * 银行卡 = Luhn 校验过的卡号 + 字段名后面的安全码和有效期。
     *
     * 卡背面的「CVV2 123」、正面的「VALID THRU 12/28」单凭形状什么都不是，跟在字段名后面才认。
     * 字段名照卡面上印的认，英文的只认大写：网址里的「?cid=1234」、一句话里的「Offer valid thru 12/31」、
     * 游戏里的「EXP 08/20」都不是卡。「CID」「CSC」大写的也常是别的意思（客户号、来电号码），不认。
     * 中文的「有效期」「有效期至」不认：优惠券上的「有效期至 06/30」是月/日，和卡的月/年分不开，到处都是。
     * 有效期只认「月/年」，年份在去年到十二年后之间，后面不接连字符（「10/12-10/19」是一段日期）：
     * 身份证背面的「有效期限 2015.08.20-2035.08.20」不是卡。「VALID FROM/THRU」后面是起止两个，一起遮。
     * 优惠券上也印「VALID THRU 12/26」：有效期还要页上有卡（Luhn 对得上的卡号、打了星的卡号，或者 VISA、银联、信用卡这类字）。
     *
     * 机读码第二行里一长串数字，十回里有一回碰巧过得了 Luhn；身份证号也是，十个里有一个，
     * 同一处让给护照和证件号码，不在身份证号上标「银行卡号」。
     */
    private val CARD = CompositeRule(
        id = "card",
        kind = SensitiveKind.PAYMENT_CARD,
        enabledByDefault = true,
        Finder { text ->
            CARD_NUMBER.findAll(text).filter { Checksums.luhn(it.value.filter(Char::isDigit)) }
                .map { RuleMatch(it.range, 0.95f) }.toList()
        },
        LabeledField(
            labels = listOf("CVV2", "CVC2", "CVN2", "CVV", "CVC", "安全码", "卡验证码", "卡片验证码"),
            value = Regex("""\d{3,4}(?!\d)"""),
            confidence = 0.8f,
        ),
        cardExpiry(listOf("VALID THRU", "VALIDTHRU", "GOOD THRU", "EXPIRES END", "EXP DATE"), CARD_MONTH_YEAR),
        cardExpiry(listOf("VALID FROM/THRU"), "(?:$CARD_MONTH_YEAR ?[-–] ?)?$CARD_MONTH_YEAR"),
        yieldsTo = setOf(SensitiveKind.PASSPORT, SensitiveKind.ID_NUMBER),
    )

    /** 字段名后面卡的有效期，最后那个年份在去年到十二年后之间；页上要有卡。 */
    private fun cardExpiry(labels: List<String>, value: String) = OnPageWith(
        Checked(LabeledField(labels, Regex("""$value(?![\d/\-])"""), confidence = 0.8f, bareRow = true)) { text, r ->
            val year = 2000 + text.substring(r).takeLast(2).toInt()
            val now = LocalDate.now().year
            r.takeIf { year in now - 1..now + 12 }
        },
        cue = { text -> CARD_WORDS.containsMatchIn(text) || CARD_NUMBER.findAll(text).any { Checksums.luhn(it.value.filter(Char::isDigit)) } },
    )

    /** 卡上、刷卡小票上的字，和打了星的卡号「6222 **** 1234」。 */
    private val CARD_WORDS = Regex(
        cue("VISA", "Visa", "MASTERCARD", "Mastercard", "UnionPay", "AMERICAN EXPRESS", "AMEX", "JCB", "DISCOVER", "CARD NO",
            "CARDHOLDER", "DEBIT", "CREDIT", "银联", "信用卡", "借记卡", "储蓄卡", "银行卡", "卡号").pattern +
            """|\d{4} ?[*＊•●]{4}|[*＊•●]{4} ?\d{4}"""
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
     * 大陆通行证的机读码和号码归证件号码（MrzDocument.PERMIT）。
     */
    private val PASSPORT = CompositeRule(
        id = "passport",
        kind = SensitiveKind.PASSPORT,
        enabledByDefault = true,
        MrzLines(confidence = 0.95f, MrzDocument.PASSPORT),
        LabeledField(
            labels = listOf(
                "Passport No.", "Passport No", "Passport Number", "Passport #", "Document No.", "Document No",
                "Document Number", "护照号码", "护照号", "護照號碼", "護照號",
            ),
            value = Regex("""$DOCUMENT_NUMBER(?![A-Za-z0-9])"""),
            confidence = 0.8f,
            ignoreCase = true,
            // 台湾居留证上「護照號碼」和号码隔着一栏
            bareRow = true,
        ),
        StackedField(
            StackedField.document(StackedField.DOCUMENT_NUMBER_LABELS),
            value = { cell -> DOCUMENT_NUMBER_ALONE.find(cell)?.range },
            confidence = 0.8f,
            sameLine = true,
        ),
        MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, confidence = 0.9f, MrzDocument.PASSPORT),
    )

    /**
     * 证件号码 = 通行证的机读码 + 字段名后面的号码 + 没有字段名也敢认的几种。
     *
     * 往来港澳台通行证、回乡证、台胞证上的机读码（见 Mrz），和机读码里读出的号码在卡上别处又出现的
     * （往来港澳通行证右上角那个「C00000000」没有字段名），同护照一样认，归这一类。
     * 字段名（「公民身份号码」「证号」「档案编号」「ID No.」……见 IdNumbers.labeled）后面的不验校验位：
     * 字段名已经说明了是证件号，OCR 认错一两位照样该遮；同姓名、住址一样认法（LabeledField），字段名读散了、
     * 带着英文译名（「证件号码/Card No.」）、号码在右边一栏的都认。没有字段名的要有校验位或者形状够特别：
     * 大陆身份证（省份、生日、校验码，OCR 补的空格也认，见 ResidentIdNumber）、带括号的香港身份证、带点的巴西 CPF；
     * 台湾身分证与居留证、新加坡 NRIC，韩国居民登录号、马来西亚 MyKad（生日对得上），日本在留卡，页上还要写着是哪儿的证件。
     * 前面、左边一栏、正上方写着「订单号」「编号」这类提示词的不算（单据号常是同一个形状，见 NotAfterOrderHint）。
     * 一代身份证、澳门身份证、日本个人番号、各种通行证号没有校验位或太像别的号码，只在字段名后面认。
     *
     * 真机漏检补的：身份证正面的号码原先只圈成「长数字串」，过了 Luhn 的还标成「银行卡号」，末位是 X 的什么都不是；
     * 照片上 OCR 把字段名拆成几段、在号码中间补了空格（「公民 身份号 码3 202 01201109254526」），一处都没认出来。
     * 同一处让给护照：护照的机读码、护照号由护照一类遮。
     */
    private val ID_NUMBER = CompositeRule(
        id = "idnumber",
        kind = SensitiveKind.ID_NUMBER,
        enabledByDefault = true,
        MrzLines(confidence = 0.95f, MrzDocument.PERMIT),
        *IdNumbers.labeled(confidence = 0.8f),
        IdNumbers.RESIDENT,
        IdNumbers.HKID,
        IdNumbers.TAIWAN,
        IdNumbers.NRIC,
        IdNumbers.KOREA_RRN_CHECKED,
        IdNumbers.KOREA_RRN,
        IdNumbers.MYKAD,
        IdNumbers.ZAIRYU,
        IdNumbers.CPF,
        MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, confidence = 0.9f, MrzDocument.PERMIT),
        yieldsTo = setOf(SensitiveKind.PASSPORT),
    )

    /**
     * 车架号 = 字段名（「车辆识别代号」「车架号」「VIN」）后面的 17 位 + 发动机号 + 第九位校验位对得上的 17 位。
     * 行驶证、登记证书、购车发票、保单上都有；发动机号没有格式，只在字段名后面认。见 Vins。
     */
    private val VIN = CompositeRule(
        id = "vin",
        kind = SensitiveKind.VIN,
        enabledByDefault = true,
        Vins.labeled(confidence = 0.8f),
        Vins.engine(confidence = 0.8f),
        Vins.STANDALONE,
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

    /**
     * 名字后面紧跟着的下一个字段名（证件上一行几个字段、逐字切分拼回来时连在一起）：名字到它为止。
     * 表头里跟在「姓名」后面的「学号 姓名 班级 备注」也是它们，不是名字。OCR 连成一串的「姓名张三车牌苏A12345」到「车牌」为止。
     */
    private const val NEXT_FIELD =
        "电话|手机|性别|性別|民族|出生|年龄|地址|住址|身份|证件|联系|国籍|國籍|籍贯|户主|曾用名|学号|工号|班级|部门|职位|职务|备注|成绩|" +
            "车牌|号牌|車牌"

    /** 名字里的一个字：不是下一个字段名、也不是「驿站」「门卫」这类非人名的开头。 */
    private const val NAME_CHAR = """(?:(?!$NEXT_FIELD|$NOT_A_NAME_VALUE)[\u4e00-\u9fa5])"""

    /**
     * 带间隔点的名字里的一个字：音译的名里没有地名、称呼的字。「签收人：菜鸟驿站·文三路店」「收件人 王先生.北京市朝阳区」
     * 里的点不是名字的间隔点。
     */
    private const val DOTTED_NAME_CHAR = """(?:(?![省市区县镇乡村路街巷号楼室店]|先生|女士|小姐)$NAME_CHAR)"""

    /** 复姓和名之间隔着空格：「欧阳 娜娜」。 */
    private val COMPOUND_SURNAME_SPACED =
        """(?:${PersonNameRecognizer.COMPOUND_SURNAMES.joinToString("|")}) $NAME_CHAR{1,2}(?![\u4e00-\u9fa5])"""

    /**
     * 姓名。值遇数字即停——同一行的电话归 PhoneRule 管，两条规则各出各的候选。
     *
     * 六个分支对应六种排版：带间隔点的少数民族名（「阿依古丽·买买提」，点常被读成「•」「.」「-」「。」，两边还会多出空格）、
     * 复姓后面隔着空格的名（「欧阳 娜娜」）、连写的汉字名、字距拉开后被空格隔开的名（隔得不匀的「周 以宁」也算）、
     * 证件上「姓, 名」的拼音（「ZHANG, SAN」「SMITH,JOHN」，姓全大写）、拉丁名。
     * 连写分支在拆字分支前面，`张三 性别 男` 才会只取到 `张三`；它还会在下一个字段名前停下，
     * 逐字切分的行拼回来成了「张三手机」「张三户主或与户主关系」时，不把后面的也吞进去。
     * 空格隔得不匀、后半截在后面的（「王小 明」「周以 宁 性别 Sex 女」「山田 太郎」）连写分支带上后半截：
     * 后半截一两个字，后面是行尾、下一个字段或者性别；「收货人 张三 已签收」不带。
     *
     * 值里含 NOT_A_NAME_VALUE 的词、或者紧跟着它们，整个值作废，而不是停在它们前面——
     * 否则「菜鸟驿站」会剩下「菜鸟」两个字被当成名字。
     */
    private val NAME_VALUE = Regex(
        """$DOTTED_NAME_CHAR{1,8}(?:(?:${HanNames.SPACED_DOT}|${HanNames.DOT})$DOTTED_NAME_CHAR{1,8}){1,3}(?!$NOT_A_NAME_VALUE)""" +
            """|$COMPOUND_SURNAME_SPACED""" +
            """|$NAME_CHAR{2,6}(?: $NAME_CHAR{1,2}(?=[ ]*$|[ ]+(?:$NEXT_FIELD|[男女](?![\u4e00-\u9fa5])|Sex|SEX|Gender)))?""" +
            """(?!$NOT_A_NAME_VALUE)""" +
            """|$NAME_CHAR{1,2}(?: $NAME_CHAR{1,2}){1,4}""" +
            """|[A-Z][A-Z'\-]* ?, ?[A-Z][A-Za-z.'\-]*(?:[ ][A-Z][A-Za-z.'\-]*){0,2}""" +
            """|[A-Za-z][A-Za-z.'\-]*(?:[ ][A-Za-z][A-Za-z.'\-]*){0,2}"""
    )

    /** 人名字段名。姓名规则拿它锚定取值，从字面猜出的名字也拿它当旁证（见 nameAnchors）。 */
    private val NAME_LABELS = listOf(
        "收货人", "收件人", "寄件人", "发件人", "签收人", "取件人", "提货人", "联系人",
        "收款人", "付款人", "持卡人", "开户人", "申请人", "真实姓名", "姓名", "户名", "开户名",
        "快递员", "派件员", "配送员", "收派员", "Name", "Recipient",
        // 户口簿、出生医学证明、结婚证，日本的在留卡、驾照；停车、违章、ETC 页上的车主
        "户主姓名", "新生儿姓名", "母亲姓名", "父亲姓名", "配偶姓名", "氏名", "车主姓名",
    )

    /**
     * 也是常用词的人名字段名：行驶证的「所有人 Owner」，营业执照的「法定代表人」「经营者」「投资人」「执行事务合伙人」，
     * 结婚证的「持证人」，户口簿的「曾用名」，「监护人」，停车、违章页上的「车主」。群聊里的「@所有人」「所有人都到了」
     * 「曾用名也要写吗」「车主请挪车」、条文里的「经营者应当」「法定代表人签字」也是它们，所以后面要隔着空格、冒号或英文译名才算
     * （LabeledField 的 spaced），值还要像个名字（[nameAfterCommonWord]），也不当旁证。
     */
    private val COMMON_WORD_NAME_LABELS =
        listOf("所有人", "持证人", "法定代表人", "负责人", "经营者", "投资人", "执行事务合伙人", "监护人", "曾用名", "车主")

    /**
     * 也是常用词的字段名后面的值。隔着空格、冒号的照样可能是一句话（「所有人：今晚八点开会，别迟到」
     * 「经营者 注意了」「投资人 都很满意」「监护人 签字确认」「曾用名 没有」），所以：
     * - 值到行尾为止，或者后面隔着空格是下一个字段、电话；后面还连着字、跟着逗号的是一句话；
     * - 汉字名两三个字（复姓四个），头一个字是姓，不以「明天」「马上」这样的词打头（PersonNameRecognizer.startsLikeName），
     *   后面没有「了」「都」「很」这类句子里的字。带间隔点的少数民族名照收；
     * - 字段名和值之间只隔着空格的，值不能整个是一个寻常词（PersonNameRecognizer.isOrdinaryWord）：车主 app 上的「车主 福利」
     *   「车主 余额 120.00」「车主 车险」是并排的两个词。隔着冒号、英文译名的（「车主：高峰」「所有人 Owner 高峰」）是证件、表单，照收；
     * - 拉丁字母的名字要像证件上的拼音（[ROMANISED_NAME]）：「所有人 OK」不算。
     */
    private fun nameAfterCommonWord(line: String, r: IntRange): Boolean {
        val rest = line.substring(r.last + 1)
        if (rest.isNotBlank()) {
            if (!rest.first().isWhitespace()) return false
            val next = rest.trimStart()
            if (!next.first().isDigit() && next.first() != '+' && !startsWithField(next)) return false
        }
        val v = line.substring(r)
        if (v.none { it.isHan() }) return ROMANISED_NAME.matches(v)
        if (v.any { it in HanNames.DOT_CHARS }) return true
        val name = v.filterNot { it.isWhitespace() }
        val max = if (PersonNameRecognizer.COMPOUND_SURNAMES.any { name.startsWith(it) }) 4 else 3
        if (name.length !in 2..max || name.drop(1).any { it in HanNames.SENTENCE_CHARS } || !PersonNameRecognizer.startsLikeName(name)) {
            return false
        }
        // 字段名都以汉字结尾：值前面紧挨着（隔着空格）的还是汉字，中间就只有空格
        val spaceOnly = line.substring(0, r.first).trimEnd().lastOrNull()?.isHan() == true
        return !spaceOnly || !PersonNameRecognizer.isOrdinaryWord(name)
    }

    /** 人名字段名领着的值：「姓名 张三」。下面一行的拼音由 [PinyinBelow] 从它往下接。 */
    private val NAME_FIELD =
        LabeledField(labels = NAME_LABELS, value = NAME_VALUE, confidence = 0.8f, nextField = ::startsWithField)

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
     * 设置 › 文字 › 人名里打开「没有旁证的也打码」才照样打码。
     * 它原先单独成一条规则，设置页上另有一行「人名（无字段名）」；两行都是人名，用户分不清关的是哪一个，
     * 于是并了回来：一行「人名」管这几种认法，一起开关。
     *
     * 英文的两种（2026-10）：[EnglishNameCues] 是英文界面上自己说明「这是人」的写法（「Ship to:」「Hi David,」
     * 「Sarah Johnson <…@…>」），与字段名同一档；[EnglishNameGuess] 按名单从字面猜「Emily Carter」，
     * 与 HanLP 同一档，要旁证。调研与评测见 docs/english-recognition-research.md。
     *
     * 证件上的（2026-10，用户反馈身份证遮不全）：身份证人像面上字段名读坏了的那一行（[IdCardFront]），香港身份证上没有字段名的
     * 中文名、英文名和电码（[HongKongIdCard]），
     * 毕业证、学位证正文里的「张三，男，……生」（[DiplomaName]），通行证上名字下面一行的拼音（[PinyinBelow]）。
     */
    private val NAME = CompositeRule(
        id = "name",
        kind = SensitiveKind.PERSON_NAME,
        enabledByDefault = true,
        NAME_FIELD,
        LabeledField(
            labels = emptyList(),
            value = NAME_VALUE,
            confidence = 0.8f,
            spaced = COMMON_WORD_NAME_LABELS,
            nextField = ::startsWithField,
            spacedValue = ::nameAfterCommonWord,
        ),
        PinyinBelow(NAME_FIELD, confidence = 0.8f),
        NameBeforePhone(PHONE, confidence = 0.7f),
        EnglishNameCues(confidence = 0.8f, weakConfidence = 0.7f),
        StackedField(StackedField.document(StackedField.NAME_LABELS), ::documentNameIn, confidence = 0.8f),
        // 中国护照的「姓名/Name」下面两行：汉字名、拼音
        StackedField(StackedField.document(StackedField.CHINESE_NAME_LABELS), ::chineseDocumentNameIn, confidence = 0.8f, maxLines = 2),
        StackedField(StackedField.header(StackedField.NAME_HEADER), ::formNameIn, confidence = 0.7f),
        MrzEcho(MrzEcho.What.NAME, confidence = 0.8f),
        IdCardFront(IdCardFront.What.NAME, confidence = 0.8f),
        HongKongIdCard(confidence = 0.8f),
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

    /** 汉字名：两到八个字，可带间隔点，字间可隔空格。 */
    private val HAN_NAME = Regex("""[\u4e00-\u9fa5](?: ?(?:${HanNames.ANY_DOT} ?)?[\u4e00-\u9fa5]){1,7}""")

    /**
     * 「姓名/Name」下面的：汉字名、[ROMANISED_NAME] 的拼音（「ZHOU, YINING」），或者同一行先汉字后拼音。
     * 双语表单上也是这个字段名（斜杠读丢了的「姓名 Name」更是），下面一行可能是下一个字段「电话/Phone」，
     * 所以比 [documentNameIn] 严。
     */
    private fun chineseDocumentNameIn(cell: String): IntRange? {
        val han = HAN_NAME.matchAt(cell, 0)?.range?.let { it.last + 1 } ?: 0
        val rest = cell.substring(han).trim()
        val ok = ROMANISED_NAME.matches(rest) || (han > 0 && (rest.isEmpty() || rest.all { it in 'A'..'Z' }))
        return cell.indices.takeIf { ok }
    }

    /** 表单上单独一行的「Name」「Customer」下面那个：与「Name: …」同一套值的形状，「Order Details」、品牌名不算。 */
    private fun formNameIn(cell: String): IntRange? =
        cell.indices.takeIf { EnglishNameCues.VALUE.matches(cell) && EnglishNameCues.looksLikeNameValue(cell) }

    /**
     * 地址。**不能用姓名那套「遇数字即停」**——门牌号、楼层、邮编本来就是地址的一部分。
     * 所以取到行尾，上限 60 字符；首尾都卡非空白，免得把标签后的空格也遮进去。
     *
     * 因此「收货地址 …… 电话 138……」会把电话一起遮掉。这是过遮，方向是安全的
     * （spec §13：多遮一块只是麻烦，漏遮一块是事故），何况电话本来也该遮。
     *
     * 开头不是标点：增值税发票上「地 址、电 话：」的「地 址」后面是「、电 话：」，不是地址。
     */
    private val ADDRESS_VALUE = Regex("""[^\s/／|｜、，,。；;：:].{2,58}[^\s/／|｜]""")

    private val ADDRESS_LABELS = listOf(
        "收货地址", "收件地址", "寄件地址", "发货地址", "退货地址", "取件地址", "寄送地址",
        "送货地址", "配送地址", "送达地址", "详细地址", "联系地址", "通讯地址", "家庭地址",
        "居住地址", "户籍地址", "家庭住址", "现住址", "所在地区", "住址", "地址",
        "配送至", "送货至", "送至", "寄至", "Address", "Ship to", "Deliver to",
        // 户口簿、居住证；增值税发票的「地址、电话」
        "现居住地址", "本市（县）其他住址", "本市(县)其他住址", "何时由何地迁来本市（县）", "何时由何地迁来本市(县)",
        "何时由何地迁来本址", "地址、电话",
    )

    /**
     * 也是常用词的地址字段名：营业执照（「住　　所」两端对齐）、户口簿、居住证，日本的在留卡、驾照。
     * 合同条文里的「住所地人民法院」、聊天里的「居住地在北京」「户籍所在地在哪里呀」也是它们，
     * 后面要隔着空格、冒号或英文译名才算（LabeledField 的 spaced），值也不能以「是」「在」「不」这类字打头（[notASentence]），
     * 还要有省市区县、路、号或者数字（[PLACE]）：「住所：详见附件一」「营业场所：一楼大厅」「住所：公司宿舍，离地铁近」不是地址。
     */
    private val SPACED_ADDRESS_LABELS =
        listOf("住所", "经营场所", "主要经营场所", "营业场所", "户籍所在地", "现居住地", "居住地", "住居地")

    /** 地址里一定有的：行政区划、路街、门牌号，或者数字。 */
    private val PLACE = Regex("""[省市区县州旗镇乡村路街道巷弄号]|\d""")

    /**
     * 句子接在字段名后面的头一个字：「出生地 不重要」「居住地 在北京」。地名不以它们打头；
     * 「都」「来」「会」不在里面：都匀、来宾、会理是地名。
     */
    private const val SENTENCE_START = "是在不没也就还很要能把被给让跟到去说呢吗吧啊"

    private fun notASentence(line: String, r: IntRange): Boolean = line[r.first] !in SENTENCE_START

    /** 地址字段名后面那一段。证件上折到下一行的那几行由 [FollowingLines] 从它往下接。 */
    private val ADDRESS_FIELD = LabeledField(
        labels = ADDRESS_LABELS,
        value = ADDRESS_VALUE,
        confidence = 0.8f,
        spaced = SPACED_ADDRESS_LABELS,
        nextField = ::startsWithField,
        spacedValue = { line, r -> notASentence(line, r) && PLACE.containsMatchIn(line.substring(r)) },
    )

    /**
     * 证件、表格上姓名、地址、出生日期以外的字段名：证件号码、车架号、车牌的那些，再加上没有规则的。
     * 户口簿一行有好几个字段（「出生地 江苏省南京市 民族 汉」），出生地遮到它们前面为止；
     * 住址折行时，以它们打头的一行是下一个字段（见 FollowingLines）。
     */
    private val OTHER_FIELDS = IdNumbers.LABELS + Vins.LABELS + Vins.ENGINE_LABELS + Plates.CJK_LABELS + listOf(
        "身份证", "号码", "户号", "性别", "民族", "国籍", "籍贯", "签发机关", "签发日期", "有效期限", "有效期",
        "准驾车型", "初次领证日期", "初次领证", "发证", "注册日期", "使用性质", "品牌型号", "车辆类型", "车辆",
        "户别", "宗教", "文化程度", "婚姻", "兵役", "服务处所", "职业", "身高", "血型", "登记日期", "承办人",
        "经营范围", "成立日期", "注册资本", "类型", "名称", "营业期限", "电话", "手机", "邮编", "邮政编码", "备注",
    )

    /**
     * 出生地：户口簿、出生医学证明、护照上常常只写一个省（「江苏」），比住址短。
     * 出生医学证明上省市县之间隔着空格，表上还印着「省」「市」「县（区）」（「山东 省 青岛 市 黄岛 县（区）」），
     * 一段一段接，到下一个字段名为止，至多八段。
     * 「出生地是哪儿来着」「出生地呢？」是聊天：字段名后面要隔着空格、冒号或英文译名（同 [SPACED_ADDRESS_LABELS]）；
     * 带着逗号问号的、「保密」「不详」这种也不是。
     */
    private val BIRTH_PLACE_FIELD = LabeledField(
        labels = emptyList(),
        value = Regex("""[^\s/／|｜、，,。；;：:]\S{1,29}(?: (?!${OTHER_FIELDS.joinToString("|") { Regex.escape(it) }})\S{1,29}){0,7}"""),
        confidence = 0.8f,
        spaced = listOf("出生地点", "出生地"),
        nextField = ::startsWithField,
        spacedValue = { line, r ->
            val v = line.substring(r)
            notASentence(line, r) && !SENTENCE_PUNCTUATION.containsMatchIn(v) && !NOT_A_PLACE.matches(v)
        },
    )

    private val SENTENCE_PUNCTUATION = Regex("[，,。？?！!…]")

    private val NOT_A_PLACE = Regex("保密|不详|未知|无|暂无|略|[-—]+")

    /**
     * 户口簿「何时由何地迁来本市（县）」那一栏的值：「2010年09月由江苏省南京市玄武区迁来」。字段名长，常折成两行
     * （「何时由何地」/「迁来本址」），字段名接不上值，按值自己的样子认：年月、「由」、地方、「迁来」。
     */
    private val MOVED_FROM = Regex("""(?:18|19|20)\d{2} *年 *\d{1,2} *月(?: *\d{1,2} *日)? *由 *[\u4e00-\u9fa5].{1,38}?迁来""")

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
        regexFinder(MOVED_FROM, 0.8f),
        BIRTH_PLACE_FIELD,
        IdCardFront(IdCardFront.What.ADDRESS, confidence = 0.8f),
        StackedField(StackedField.document(StackedField.BIRTH_PLACE_LABELS), ::placeIn, confidence = 0.8f),
        StackedField(StackedField.header(StackedField.ADDRESS_HEADER), ::addressLineIn, confidence = 0.7f, maxLines = 4),
        AddressShape(confidence = 0.7f),
        EnglishAddressShape(confidence = 0.7f),
        // 车牌像英文地址的尾巴：「苏A12345D」像爱尔兰邮编，「B SA 1234」像澳洲的州加邮编
        yieldsTo = setOf(SensitiveKind.LICENSE_PLATE),
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

    /** 取件码的样子：至多三个字母打头，有数字，可用连字符分段（「1-58908」「A12-3-4567」「B3-1107」「36920471」）。 */
    private const val PICKUP_VALUE = """[A-Za-z]{0,3}\d[A-Za-z0-9]*(?:\s?[-－]\s?[A-Za-z0-9]+)*"""

    /**
     * 短信里不写字段名的取件码：「请凭8-5-2001于21:00前取件」「请凭3-2-1234到东门驿站领取」。码紧跟在「凭」后面，
     * 接着说怎么取（「于」「到」「及时」「取件」……），这一句里要说到取、领、提货，码至少四位数字（[collectWithCode]）：
     * 「凭2024年发票领取」「凭1000积分领取」「凭8888到店享八折」都不是。
     */
    private val COLLECT_WITH_CODE = Regex(
        """凭 ?($PICKUP_VALUE)(?![A-Za-z0-9])(?= ?(?:于|到|至|在|前往|前来|及时|尽快|尽早|速|来|去|开|取|领|提货))""" +
            """(?=[^。；;！!？?\n]*?(?:取(?!消)|领|提货))"""
    )

    /** [COLLECT_WITH_CODE] 里的码，逐字切开的行也认。 */
    private fun collectWithCode(confidence: Float) = Finder { text ->
        val view = HanView.of(text)
        COLLECT_WITH_CODE.findAll(view.text).map { it.groups[1]!! }
            .filter { code -> code.value.count(Char::isDigit) >= 4 }
            .map { RuleMatch(view.toSource(it.range), confidence) }.toList()
    }

    /**
     * 取件码（真机漏检补的）。驿站、快递柜凭它取件，截图里露出来，
     * 看到的人就能去把包裹拿走——危害比快递单号直接得多。
     *
     * 认字段名后面的值和短信里「凭」后面的码，误报率低，所以默认打码。值至少含一位数字，
     * 「请凭取货码及时领取」这种说明文字不会被当成值。
     *
     * 短信、聊天里字段名写在句子中间（测评页上漏的）：「凭取件码36920471取件」「柜子取餐码 5-0318」前面紧挨着汉字，
     * 「您的取件码为 6-2-3001」隔着「为」，照认（LabeledField 的 inSentence）。
     */
    private val PICKUP_CODE = CompositeRule(
        id = "pickup",
        kind = SensitiveKind.PICKUP_CODE,
        enabledByDefault = true,
        LabeledField(
            labels = listOf("取件码", "取货码", "提货码", "取件号", "自提码", "取餐码"),
            value = Regex(PICKUP_VALUE),
            confidence = 0.8f,
            inSentence = true,
        ),
        collectWithCode(confidence = 0.7f),
    )

    private val BIRTH_DATE_LABELS = listOf(
        "出生日期", "出生年月日", "出生年月", "出生时间", "出生", "生年月日", "生日",
        "Date of birth", "Birth date", "Birthdate", "Birthday", "DOB", "D.O.B.", "Born",
    )

    /**
     * 只写到月的生日：「1990年01月」「2002.03」「2002-03」。年要像年，月在 1 到 12；
     * 后面再跟着数字、分隔符的是带日的写法或者别的编号，不算。
     */
    private const val YEAR_MONTH =
        """(?:18|19|20)\d{2} *(?:年 *(?:0?[1-9]|1[0-2]) *月|[.．/\-](?:0?[1-9]|1[0-2]))(?![\d.．/\-])"""

    /**
     * 出生日期：字段名指明了的那个日期，误报少，默认打码；没有字段名的日期仍归日期时间（出厂关着）。
     *
     * 五种认法：同一行的「出生日期：1990-01-01」「DOB 01/07/1973」「出生 1990年1月1日」（身份证）；
     * 字段名在上、日期在下的护照资料页（「Date of birth」下面的「05 FEB 1965」）；机读码里的生日在页上别处出现
     * （幽灵像旁边那行「05 02 65」）；身份证号里的生日在页上别处出现（IdBirthEcho：「出生」读坏了的那一行也遮得上）；
     * 毕业证、日本证件上没有字段名、后面跟着「生」的「一九九八年三月五日生」（BornOn）。
     * 前面写着字段名，日期可以写得随意些：两位年、空格分隔、数字里夹空格、民国和年号纪年都认（Dates.LENIENT）；
     * 后面不能紧跟着数字（「出生 202001011234」是一串编号），连写的八位要是日历上有的一天（「生日 20240230」不是）。
     * 学生证、工作证、简历上只写到月的（「出生年月 1990年01月」「出生日期 2002.03」）另认，见 [YEAR_MONTH]。
     */
    private val BIRTH_DATE = CompositeRule(
        id = "birthdate",
        kind = SensitiveKind.BIRTH_DATE,
        enabledByDefault = true,
        LabeledField(
            labels = BIRTH_DATE_LABELS,
            value = Regex("""(?:${Dates.LENIENT})(?!\d)"""),
            confidence = 0.8f,
            ignoreCase = true,
            nextField = ::startsWithField,
            valid = { v -> !v.all(Char::isDigit) || Dates.readings(v).any { (y, m, d) -> Dates.isRealDate(y, m, d) } },
            // 日期的形状够严：出生医学证明的「出生时间」、12123 电子驾照的「出生日期」，值都在右边一栏
            bareRow = true,
        ),
        LabeledField(labels = listOf("出生年月", "出生日期"), value = Regex(YEAR_MONTH), confidence = 0.8f),
        StackedField(
            StackedField.document(StackedField.BIRTH_DATE_LABELS),
            value = ::birthDateIn,
            confidence = 0.8f,
            sameLine = true,
        ),
        MrzEcho(MrzEcho.What.BIRTH_DATE, confidence = 0.9f),
        IdBirthEcho(confidence = 0.9f),
        BornOn(confidence = 0.8f),
    )

    /**
     * 「Date of birth」下面那一格里的日期。日期前面是另一个中英文字段名的不算：驾驶证上「出生日期」那一栏往下，
     * 拍斜了接到的是「初次领证日期 Date of First Issue 2008-05-20」。只有汉字的不算字段名：水印压着时生日常和水印的字
     * 读成一串（「务 使用 8-02-14」），那还是生日。
     */
    private fun birthDateIn(cell: String): IntRange? = Dates.findLenient(cell)?.takeUnless { r ->
        BILINGUAL_LABEL.containsMatchIn(cell.substring(0, r.first))
    }

    /** 汉字后面跟着带小写字母的英文词：「签发日期 Date of Issue」。 */
    private val BILINGUAL_LABEL = Regex("""[\u4e00-\u9fa5][ 　/／|｜]*[A-Z]?[a-z]""")

    /**
     * 住址折行时，以字段名（OTHER_FIELDS，姓名、地址、出生日期的字段名，英文译名）打头的一行是下一个字段，
     * 不是住址的下半截（见 FollowingLines）；同一排右边那一段以它打头，也不是左边那个字段的值（LabeledField 的 nextField）。
     *
     * 字段名后面紧跟着汉字的不算：「民族大道136号」「车辆段宿舍3号楼」「职业学院路」是路名，不是「民族」「车辆」「职业」。
     * 后面是空格、冒号、数字（「公民身份号码330106…」）才是。字段名的字与字之间可以夹空格（「公民 身份号 码3…」）。
     * 英文的照写法或者全大写：「Vin Diesel」不是「VIN」。
     */
    private val FIELD_START = run {
        val all = (OTHER_FIELDS + NAME_LABELS + COMMON_WORD_NAME_LABELS + ADDRESS_LABELS + SPACED_ADDRESS_LABELS +
            listOf("出生地点", "出生地") + BIRTH_DATE_LABELS + LabeledField.Translations.ALL)
            .flatMap { if (it.any(Char::isLowerCase)) listOf(it, it.uppercase()) else listOf(it) }
            .distinct().sortedByDescending { it.length }
        fun spaced(fields: List<String>) = fields.joinToString("|") { f ->
            f.filterNot { it == ' ' }.map { Regex.escape(it.toString()) }.joinToString("[ 　]*")
        }
        val (han, latin) = all.partition { it.last().isHan() || it.last() in "）)" }
        Regex("""^[ 　]*(?:(?:${spaced(han)})(?![\u4e00-\u9fa5])|(?:${spaced(latin)})(?![A-Za-z]))""")
    }

    private fun startsWithField(line: String): Boolean = FIELD_START.containsMatchIn(line)

    /**
     * 车牌（真机漏检：车的照片上的「苏B·1LE22」，OCR 读成「苏B·ILE22」，没有一条规则认）。写法与分档见 [Plates]。
     *
     * 字段名后面的、写法独特的（中国大陆、日本、英国……）打码，上下两行的牌子拼起来认；
     * 德国、西班牙这些写法不够独特的，照片上孤零零一行时认出来也不打码（[LonePlate]）：车的照片上没有字段名，
     * 牌子自己成一行、周围没有别的字；一句话里、一张截图里的多半不是车牌。
     */
    private val PLATE = CompositeRule(
        id = "plate",
        kind = SensitiveKind.LICENSE_PLATE,
        enabledByDefault = true,
        PlateLabel(confidence = 0.8f),
        StackedField(StackedField.header(Plates.HEADER), Plates::valueIn, confidence = 0.8f),
        PlateShapes(confidence = 0.9f),
        TwoRowPlate(confidence = 0.85f),
        LonePlate(confidence = 0.6f),
        // 机读码、IBAN、车架号、证件号里的一段字母数字碰巧像车牌：OCR 在车架号中间补了空格的「LFV2A21 K6D4123456」，头一段像巴西的牌
        yieldsTo = setOf(SensitiveKind.PASSPORT, SensitiveKind.IBAN, SensitiveKind.VIN, SensitiveKind.ID_NUMBER),
    )

    // ---------- 出厂关着：误报率高，要的话在设置里打开，打开就打码（spec §6） ----------

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

    /** 长数字串拿它认出日期，所以单独拎出来、与规则表共用同一个实例（同 PHONE）。 */
    private val DATE_TIME = DateTimeRule()

    /**
     * 长数字串兜底：至少 8 位数字连成的一串，中间可以夹着连字符和大写字母。快递单号（「SF1234567890123」
     * 「1Z12345E0205271688」）、订单号（「231016-091234567890123」「123-4567890-1234567」）、交易号都归这一类。
     *
     * 起初（spec §15 第 5 条的实测漏检：淘宝订单页上 28 位的支付宝交易号没有一条规则认）只认 16 位以上的纯数字，
     * 快递单号另成一类，只认 UPS 和几种固定长度。2026-10-16 起快递单号并进来，下限降到 8 位：
     * 单号、订单号的写法太多，按快递公司、按长度认总有漏的。
     *
     * 它是兜底，同一处别的类型认出了东西就让开（yieldsTo 是其余各类）：电话、证件号码、银行卡号……
     * 让开只在那一类开着时做得到（引擎里只有开着的规则），所以有两样不管那一类开没开都不算：
     * - 日期时间：「2026-10-16」也是 8 位数字夹着连字符，日期时间出厂关着，不能从这里遮上；
     * - 13–19 位、过得了 Luhn 的：那是银行卡号的地盘。只在卡号的长度里让——别处碰巧过得了 Luhn（约十分之一）不该让兜底失灵。
     *
     * 没有任何校验位，误报天然多（时间戳、序号、金额都会撞上），
     * 因此按 §6「默认按误报率划线」出厂关着。
     */
    private val LONG_NUMBER = object : Rule {
        override val id = "longnum"
        override val kind = SensitiveKind.LONG_NUMBER
        override val enabledByDefault = false
        override val yieldsTo = SensitiveKind.entries.toSet() - SensitiveKind.LONG_NUMBER

        /**
         * 数字、大写字母、连字符连成的一段：头尾不是连字符，前后不挨着字母数字和下划线
         * （「abc2026…」是哈希、变量名，「IMG_20260312_100512.jpg」是文件名）。
         */
        private val run = Regex("""(?<![A-Za-z0-9_])[0-9A-Z](?:[0-9A-Z－-]*[0-9A-Z])?(?![A-Za-z0-9_])""")
        private val minDigits = 8

        override fun findIn(text: String): List<RuleMatch> {
            val runs = run.findAll(text).filter { m -> m.value.count(Char::isDigit) >= minDigits }.toList()
            if (runs.isEmpty()) return emptyList()
            val dates = DATE_TIME.findIn(text).map { it.range }
            return runs
                .filterNot { m -> dates.any { m.range.first >= it.first && m.range.last <= it.last } }
                .filterNot { m ->
                    val digits = m.value.filter(Char::isDigit)
                    m.value.none(Char::isLetter) && digits.length in 13..19 && Checksums.luhn(digits)
                }
                .map { RuleMatch(it.range, 0.4f) }      // 无校验位 → 低置信度
        }
    }

    val rules: List<Rule> = listOf(
        CARD, IBAN, SSN, MAC, EMAIL, PHONE, PASSPORT, ID_NUMBER, VIN, API_KEY,
        NAME, ADDRESS, PICKUP_CODE, BIRTH_DATE, PLATE,
        URL, IP, LONG_NUMBER, DATE_TIME,
    )

    // ---------- 人名的旁证 ----------

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
        // 香港身份证上的英文名「CHAN, Tai Man」从名单里猜，旁证是下面那个带括号校验位的号码
        NameAnchor(R.string.anchor_id, NameAnchor.Reach.NEARBY, Finder { t -> IdNumbers.RESIDENT.findIn(t) + IdNumbers.HKID.findIn(t) }),
        NameAnchor(R.string.anchor_masked_number, NameAnchor.Reach.NEARBY, regexFinder(MASKED_NUMBER, 1f)),
        NameAnchor(
            R.string.anchor_field, NameAnchor.Reach.NEARBY,
            Words(NAME_LABELS + PERSON_FIELDS, boundedBefore = true),
        ),
        NameAnchor(R.string.anchor_ticket, NameAnchor.Reach.NEARBY, Words(TICKET_TYPES, boundedBefore = true, boundedAfter = true)),
        NameAnchor(R.string.anchor_english_field, NameAnchor.Reach.NEARBY, regexFinder(ENGLISH_PERSON_FIELDS, 1f)),
        // 车牌不当旁证：停车、ETC、违章页上挨着车牌的多是停车场、地名、店名（「苏A12345 汤山」「万象城 B2层」），猜出来也像人名
    )
}
