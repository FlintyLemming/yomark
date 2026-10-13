package moe.flinty.yomark.rules

import moe.flinty.yomark.rules.validator.Checksums

/**
 * 车架号（车辆识别代号，GB 16735-2019）和发动机号的认法：行驶证、登记证书、购车发票、保单上的。
 * 排列先后、默认打码见 DefaultRuleSet 的 VIN。
 */
internal object Vins {

    /**
     * 行驶证上中英文字段名并排：「车辆识别代号 VIN」「车辆识别代号(VIN)」「车辆识别代号 (VIN)」，
     * 连在一起时英文那个前面紧挨着汉字或括号，单独认不出。
     */
    val LABELS: List<String> = listOf(
        "车辆识别代号", "车辆识别代码", "车辆识别号", "车架号码", "车架号", "识别代号", "VIN码", "VIN",
        "Vehicle Identification Number",
    ).let { labels ->
        labels + listOf("车辆识别代号", "车辆识别代码", "车架号").flatMap { cn ->
            listOf("VIN", " VIN", "/VIN", "(VIN)", "（VIN）", " (VIN)", " （VIN）").map { cn + it }
        }
    }

    /**
     * 字段名后面的车架号：17 位，OCR 补的单个空格不算位数；不验校验位，I、O、Q 也收（认错的字），
     * 打了星的「LFWADRJF0******46」也收，至少要露出四位、有一位数字。
     */
    fun labeled(confidence: Float): Finder =
        Checked(LabeledField(LABELS, LOOSE, confidence, ignoreCase = true)) { text, r ->
            val v = text.substring(r)
            v.count { it.isLetterOrDigit() } >= 4 && v.any { it.isDigit() }
        }

    private val LOOSE = Regex("""[A-Z0-9*＊](?: ?[A-Z0-9*＊]){16}(?![A-Za-z0-9*＊])""")

    val ENGINE_LABELS: List<String> = listOf("发动机号码", "发动机号", "电动机号码", "电动机号", "Engine No.", "Engine Number").let { labels ->
        labels + listOf("发动机号码", "电动机号码").flatMap { cn -> listOf("Engine No.", " Engine No.").map { cn + it } }
    }

    /** 发动机号没有统一格式（「CSS123456」「4G15S12345」），只在字段名后面认：五到二十个字母数字或连字符、至少三位数字。 */
    fun engine(confidence: Float): Finder =
        Checked(LabeledField(ENGINE_LABELS, ENGINE, confidence, ignoreCase = true)) { text, r ->
            text.substring(r).count { it.isDigit() } >= 3
        }

    private val ENGINE = Regex("""[A-Z0-9](?:-?[A-Z0-9]){4,19}(?![A-Za-z0-9\-])""")

    /**
     * 没有字段名也认的车架号：17 位，第九位是校验位（国标强制，国内的车都有）。OCR 读成的 O、Q 当 0，I 当 1。
     * 只靠校验位的话，随手一串 17 位字母数字三十几个里就有一个过得去，所以再卡几处 VIN 自己的规矩：
     * 至少两个字母（纯数字的另有长数字串管），第十位是年份码、不会是 0、U、Z，最后四位是数字。
     * 跟在「订单号」这类提示词后面的不算：「DD」加十四位时间戳再加一位的订单号，十几个里就有一个过得了这几道关。
     */
    val STANDALONE: Finder = notAfterOrderHint(
        Finder { text ->
            SHAPE.findAll(text).filter { m ->
                val v = m.value.map { when (it) { 'O', 'Q' -> '0'; 'I' -> '1'; else -> it } }.joinToString("")
                v.count { it.isLetter() } >= 2 && v[9] !in "0UZ" && v.takeLast(4).all { it.isDigit() } && Checksums.vinValid(v)
            }.map { RuleMatch(it.range, CONFIDENCE) }.toList()
        }
    )

    private val SHAPE = Regex("""(?<![A-Za-z0-9])[A-Z0-9]{17}(?![A-Za-z0-9])""")

    private const val CONFIDENCE = 0.9f
}
