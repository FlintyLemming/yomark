package moe.flinty.yomark.rules

import moe.flinty.yomark.rules.validator.Checksums

/**
 * 车牌号和车架号的认法：停车缴费、网约车、违章记录、行驶证、保单、购车发票上的。
 * 排列先后、默认打码见 DefaultRuleSet 的 LICENSE_PLATE、VIN。号牌的规矩见 GA 36-2018，车架号见 GB 16735-2019。
 */
internal object Vehicles {

    /** 31 个省级简称（号牌上用川贵云甘，不用蜀黔滇陇）。 */
    private const val PROVINCES = "京津冀晋蒙辽吉黑沪苏浙皖闽赣鲁豫鄂湘粤桂琼渝川贵云藏陕甘青宁新"

    /** 发牌机关代号和序号之间的间隔符：号牌上压的「·」，印在单据上常写成点、连字符、空格，或者干脆没有。 */
    private const val SEP = "[ ·•・.．\\-]{0,2}"

    // ---------- 车牌 ----------

    val PLATE_LABELS = listOf(
        "号牌号码", "车牌号码", "车牌号", "车牌", "号牌", "Plate No.", "Plate Number", "License Plate",
    )

    /**
     * 字段名后面的车牌：形状放宽，军牌「VA·12345」、打了星的「粤B***45」都收，五到八个字母数字、至少两位数字或星号。
     * 行驶证上中英文字段名并排（「号牌号码 Plate No. 沪AA1234」），中文那个后面跟着英文字，值认不出，交给「Plate No.」。
     */
    fun labeledPlate(confidence: Float): Finder =
        Checked(LabeledField(PLATE_LABELS, PLATE_LOOSE, confidence, ignoreCase = true)) { text, r ->
            text.substring(r).count { it.isDigit() || it in "*＊" } >= 2
        }

    private val PLATE_LOOSE = Regex(
        """(?:WJ|[$PROVINCES])?$SEP[A-Z0-9](?:$SEP[A-Z0-9*＊]){4,7}(?:应急|[学警挂港澳试超领使])?(?![A-Za-z0-9*＊])"""
    )

    /**
     * 没有字段名也认的车牌，形状照 GA 36：省份简称、发牌机关的字母、序号。
     * - 普通号牌五位序号，字母至多两个；
     * - 新能源六位：小型的头一位是字母（「京A·D12345」），大型的末一位是字母（「京A·12345D」）；
     * - 学、警、挂、港、澳、试、超、领四位序号加这个字，应急号牌四位数字加「应急」；
     * - 使馆「224·001使」，领馆「粤204·01领」，武警「WJ京1234X」。
     * 序号里不会有 I、O，OCR 读成的 I、O 当 1、0 看。「京A1234」少一位，「粤ICP备…」的「CP备」不是序号，都不算。
     * 后面带专用字的号牌，那个字后面不能紧跟着汉字：「京A1234学校」是一句话，不是教练车。
     */
    val PLATE: Finder = Finder { text ->
        val out = ArrayList<RuleMatch>()
        fun add(r: IntRange) {
            if (out.none { it.range.overlaps(r) }) out += RuleMatch(r, CONFIDENCE)
        }
        CIVIL.findAll(text).filter { serialOk(it.groupValues[1]) }.forEach { add(it.range) }
        SUFFIXED.findAll(text).filter { suffixedOk(it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4]) }
            .forEach { add(it.range) }
        SPECIAL.forEach { p -> p.findAll(text).forEach { add(it.range) } }
        out.sortedBy { it.range.first }
    }

    /** 民用号牌：省份、发牌机关、五到六位序号。 */
    private val CIVIL = Regex("""(?<![A-Za-z0-9])[$PROVINCES][A-Z]$SEP([A-Z0-9]{5,6})(?![A-Za-z0-9])""")

    /** 带专用字的号牌：四位序号加学警挂港澳试超领，或者加「应急」。 */
    private val SUFFIXED = Regex(
        """(?<![A-Za-z0-9])([$PROVINCES])([A-Z])$SEP([A-Z0-9]{4})(应急|[学警挂港澳试超领])(?![A-Za-z0-9\u4e00-\u9fff])"""
    )

    private val SPECIAL = listOf(
        // 使馆，2017 年以后的写法在后面带「使」，以前的在前面
        Regex("""(?<![A-Za-z0-9])\d{3}$SEP\d{3} ?使(?![\u4e00-\u9fff])"""),
        Regex("""(?<![A-Za-z0-9\u4e00-\u9fff])使 ?\d{3}$SEP\d{3}(?![A-Za-z0-9])"""),
        // 领馆：省份、三位国别代码、两位序号、「领」
        Regex("""(?<![A-Za-z0-9])[$PROVINCES]\d{3}$SEP\d[A-HJ-NP-Z0-9] ?领(?![\u4e00-\u9fff])"""),
        // 武警：2013 式「WJ京1234X」，2019 式「WJ·A00001」（没有省份的要有间隔符）
        Regex("""(?<![A-Za-z0-9])WJ$SEP[$PROVINCES]$SEP\d{4}[A-Z0-9](?![A-Za-z0-9])"""),
        Regex("""(?<![A-Za-z0-9])WJ[·•・.．\-] ?[A-Z0-9]\d{4,5}(?![A-Za-z0-9])"""),
        // 应急摩托车：「应急京123X」
        Regex("""(?<![A-Za-z0-9])应急[$PROVINCES]\d{3}[A-Z](?![A-Za-z0-9])"""),
    )

    /** 五位的普通序号，或六位的新能源序号。 */
    private fun serialOk(raw: String): Boolean {
        val s = plateSerial(raw) ?: return false
        return if (s.length == 5) s.count { it.isLetter() } <= 2 else SMALL_NEV.matches(s) || LARGE_NEV.matches(s)
    }

    private fun suffixedOk(province: String, issuer: String, raw: String, suffix: String): Boolean {
        val s = plateSerial(raw) ?: return false
        return when (suffix) {
            "应急" -> s.all { it.isDigit() }
            // 港澳车只挂粤 Z
            "港", "澳" -> province == "粤" && issuer == "Z" && s.count { it.isLetter() } <= 2
            else -> s.count { it.isLetter() } <= 2
        }
    }

    /** 序号里的 I、O 换成 1、0；换完还有不该出现的字母就不是序号。 */
    private fun plateSerial(raw: String): String? =
        raw.map { when (it) { 'I' -> '1'; 'O' -> '0'; else -> it } }.joinToString("")
            .takeIf { s -> s.all { it in '0'..'9' || it in 'A'..'Z' } }

    private val SMALL_NEV = Regex("""[A-HJ-NP-Z][A-HJ-NP-Z0-9]\d{3}[0-9Z]""")
    private val LARGE_NEV = Regex("""\d{5}[A-HJ-NP-Z]""")

    // ---------- 车架号、发动机号 ----------

    /** 行驶证上中英文字段名并排：「车辆识别代号 VIN」「车辆识别代号(VIN)」，连在一起时英文那个前面紧挨着汉字，单独认不出。 */
    val VIN_LABELS: List<String> = listOf(
        "车辆识别代号", "车辆识别代码", "车辆识别号", "车架号码", "车架号", "识别代号", "VIN码", "VIN",
        "Vehicle Identification Number",
    ).let { labels ->
        labels + listOf("车辆识别代号", "车辆识别代码", "车架号").flatMap { cn ->
            listOf("VIN", " VIN", "/VIN", "(VIN)", "（VIN）").map { cn + it }
        }
    }

    /**
     * 字段名后面的车架号：17 位，OCR 补的单个空格不算位数；不验校验位，I、O、Q 也收（认错的字），
     * 打了星的「LFWADRJF0******46」也收，至少要露出四位、有一位数字。
     */
    fun labeledVin(confidence: Float): Finder =
        Checked(LabeledField(VIN_LABELS, VIN_LOOSE, confidence, ignoreCase = true)) { text, r ->
            val v = text.substring(r)
            v.count { it.isLetterOrDigit() } >= 4 && v.any { it.isDigit() }
        }

    private val VIN_LOOSE = Regex("""[A-Z0-9*＊](?: ?[A-Z0-9*＊]){16}(?![A-Za-z0-9*＊])""")

    val ENGINE_LABELS: List<String> = listOf("发动机号码", "发动机号", "电动机号码", "电动机号", "Engine No.", "Engine Number").let { labels ->
        labels + listOf("发动机号码", "电动机号码").flatMap { cn -> listOf("Engine No.", " Engine No.").map { cn + it } }
    }

    /** 发动机号没有统一格式（「CSS123456」「4G15S12345」），只在字段名后面认：五到二十个字母数字或连字符、至少三位数字。 */
    fun labeledEngine(confidence: Float): Finder =
        Checked(LabeledField(ENGINE_LABELS, ENGINE, confidence, ignoreCase = true)) { text, r ->
            text.substring(r).count { it.isDigit() } >= 3
        }

    private val ENGINE = Regex("""[A-Z0-9](?:-?[A-Z0-9]){4,19}(?![A-Za-z0-9\-])""")

    /**
     * 没有字段名也认的车架号：17 位，第九位是校验位（国标强制，国内的车都有）。OCR 读成的 O、Q 当 0，I 当 1。
     * 只靠校验位的话，随手一串 17 位字母数字三十几个里就有一个过得去，所以再卡几处 VIN 自己的规矩：
     * 至少两个字母（纯数字的另有长数字串管），第十位是年份码、不会是 0、U、Z，最后四位是数字。
     */
    val VIN: Finder = Finder { text ->
        VIN_SHAPE.findAll(text).filter { m ->
            val v = m.value.map { when (it) { 'O', 'Q' -> '0'; 'I' -> '1'; else -> it } }.joinToString("")
            v.count { it.isLetter() } >= 2 && v[9] !in "0UZ" && v.takeLast(4).all { it.isDigit() } && Checksums.vinValid(v)
        }.map { RuleMatch(it.range, CONFIDENCE) }.toList()
    }

    private val VIN_SHAPE = Regex("""(?<![A-Za-z0-9])[A-Z0-9]{17}(?![A-Za-z0-9])""")

    private const val CONFIDENCE = 0.9f
}
