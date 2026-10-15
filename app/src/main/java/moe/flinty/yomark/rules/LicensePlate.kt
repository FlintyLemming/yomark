package moe.flinty.yomark.rules

import kotlin.math.max
import kotlin.math.min

/**
 * 车牌的写法（真机漏检：车的照片上的「苏B·1LE22」，OCR 读成「苏B·ILE22」、置信度 0.99，没有一条规则认）。
 *
 * 按误报率分三档：
 * - [CHINA]、[DISTINCT] 写法独特的：中国大陆（省份简称 + 发牌机关字母）、日本（地名 + 分类号 + 假名）、英国、法国意大利、巴西、
 *   俄罗斯、乌克兰保加利亚、印度、新加坡（校验字母）、比利时、荷兰、爱尔兰、越南。一行里哪儿都认，打码；
 * - [ALONE] 写法不够独特的：德国、西班牙、「ABC-1234」……一句话里出现的多半是型号、航班号、金额，
 *   只认照片上孤零零的一行（[LonePlate]），认了也不打码；
 * - 字段名后面的（[PlateLabel]）：「车牌号」「License plate」「Kennzeichen」后面的，哪国写法都行，打码。
 *
 * 上下两行的牌子（大车尾牌、摩托、日本的牌子、方牌）OCR 读成两行，见 [TwoRowPlate]。
 *
 * 认不了的：韩国、泰国（PP-OCR 的字典里没有谚文、泰文，牌上那个字读丢或读成别的字，剩下的数字和电话号码分不开）；
 * 香港的「AB 1234」、瑞典的「ABC 123」这类和航班号、金额一个样的，只认字段名后面的。
 *
 * 正则里写 [0-9] 和空格，不写 \d、\s：手机上的正则是 ICU，\d 认全角数字、\s 认全角空格，单测跑的 JVM 不认，两边会不一样。
 */
internal object Plates {

    /** 牌上的分隔符：「·」常读成「•」「・」「.」、空格、连字符，也常读丢。 */
    private const val SEP = "[ ·•・.．\\-－–—]"

    // ---------- 中国大陆 ----------

    /** 31 个省级简称，加 OCR 读成的繁体和形近字（冀→翼、沪→泸）。 */
    private const val PROVINCE = "[京津沪渝冀晋蒙辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新翼泸蘇閩贛遼滬魯陝貴雲寧瓊晉粵]"

    /** 简称和字母之间：小字的牌子上「·」后面还会多读出一个空格（「苏B· ILE22」），「·」也会读成冒号。 */
    private const val CN_SEP = "[ ·•・.．\\-－–—:：]{0,2}"

    /** 发牌机关字母：没有 I；O 是部分省份的公安、政府机关。 */
    private const val CITY = "[A-HJ-Z]"

    private const val CN_HEAD = "$PROVINCE$CN_SEP$CITY$CN_SEP"

    /**
     * 五位序号，最多两个字母。序号里不用 I、O（像 1、0），OCR 读出来的 I、O 按数字数：「ILE22」就是「1LE22」；
     * 小写的 l 也是读错的 1。
     */
    private const val SERIAL5 = "(?=(?:[A-HJ-NP-Z]{0,2}[0-9IOl]){3})[A-Z0-9l]{5}"

    /** 四位序号加一个字（挂、学），最多两个字母。 */
    private const val SERIAL4 = "(?=(?:[A-HJ-NP-Z]{0,2}[0-9IOl]){2})[A-Z0-9l]{4}"

    private const val END = "(?![A-Za-z0-9])"
    private const val NOT_HAN_AFTER = "(?![\\u4e00-\\u9fff])"

    /**
     * 中国大陆的牌，一行里哪儿都认。前面跟着什么由 [chinaLeadOk] 再看：「银川CA1234」「张宁 E10234」「全新X12345」里的
     * 「川」「宁」「新」是词的末一个字，不是简称。
     */
    val CHINA: List<PlateShape> = listOf(
        // 蓝牌、黄牌前牌：苏B·1LE22
        PlateShape("$CN_HEAD$SERIAL5$END", ::chinaLeadOk),
        // 小型新能源：京A·D12345。序号头一位是能源类型：D、A、B、C、E 纯电，F、G、H、J、K 混动，P 是 2026 起的纯电新号段
        PlateShape("$CN_HEAD[A-HJKP][A-HJ-NP-Z0-9IOl][0-9IOl]{3}[0-9IOlZ]$END", ::chinaLeadOk),
        // 大型新能源：京A·12345D
        PlateShape("$CN_HEAD[0-9IOl]{5}$SEP?[DF]$END", ::chinaLeadOk),
        // 教练、警、挂、试验、超限、旧式领馆、港澳入境车：苏A·1234学、粤Z·7C59港。后面紧跟着汉字的不算（学生、警方、领取）
        PlateShape("$CN_HEAD$SERIAL4$SEP?[学警挂试超领港澳]$NOT_HAN_AFTER", ::chinaLeadOk),
        // 打了星的：京A·***45、苏B·1L***
        PlateShape("$CN_HEAD(?=[A-Z0-9]{0,4}[*＊][A-Z0-9]{0,4}[*＊])[A-Z0-9*＊]{5,6}(?![A-Za-z0-9*＊])", ::chinaLeadOk),
        // 应急救援：京·X2345应急
        PlateShape("$PROVINCE$SEP?[XS0-9][0-9]{4}$SEP?应急", ::chinaLeadOk),
        // 领馆（2017 起）：沪204·01领；使馆：224·001使
        PlateShape("$PROVINCE$SEP?[0-9]{3}$SEP?[0-9]{2}$SEP?领$NOT_HAN_AFTER", ::chinaLeadOk),
        PlateShape("(?<![0-9])[0-9]{3}$SEP?[0-9]{3}$SEP?使$NOT_HAN_AFTER"),
    )

    /** 引出车牌的字：「车牌号京A12345」「车牌是浙A88X88」「一辆苏A12345」「入场苏A12345」。 */
    private const val LEAD_INS = "号牌是为辆车主照场定请停和与及或到了被把给等"

    /** 省份全称写在简称前面：「湖南湘A12345」「上海沪A·D12345」。 */
    private val FULL_NAMES = mapOf(
        '京' to listOf("北京"), '津' to listOf("天津"), '沪' to listOf("上海"), '渝' to listOf("重庆"), '冀' to listOf("河北"),
        '晋' to listOf("山西"), '蒙' to listOf("内蒙古", "内蒙"), '辽' to listOf("辽宁"), '吉' to listOf("吉林"), '黑' to listOf("黑龙江"),
        '苏' to listOf("江苏"), '浙' to listOf("浙江"), '皖' to listOf("安徽"), '闽' to listOf("福建"), '赣' to listOf("江西"),
        '鲁' to listOf("山东"), '豫' to listOf("河南"), '鄂' to listOf("湖北"), '湘' to listOf("湖南"), '粤' to listOf("广东"),
        '桂' to listOf("广西"), '琼' to listOf("海南"), '川' to listOf("四川"), '贵' to listOf("贵州"), '云' to listOf("云南"),
        '藏' to listOf("西藏"), '陕' to listOf("陕西"), '甘' to listOf("甘肃"), '青' to listOf("青海"), '宁' to listOf("宁夏"),
        '新' to listOf("新疆"),
    )

    /** 平常也是个词头的简称：「新款」「新RTX5090」「云ECS123」「云 RTX4090 实例」。 */
    private const val COMMON_WORD_PROVINCES = "新云"

    /**
     * 以简称结尾的词：城市接航班号（「银川CA1234」「仁川KE0890」「南京MU2817」）、云服务（「阿里云ECS1234」）、
     * 「全新」「最新」这类型号前的字。截图里车牌前面几乎从不是这些。
     */
    private val WORD_ENDINGS = listOf(
        "北京", "南京", "东京", "東京", "西京", "燕京", "天津", "通辽", "延吉", "普吉", "姑苏", "银川", "仁川", "旭川", "白云", "风云", "彩云",
        "阿里云", "华为云", "腾讯云", "百度云", "天翼云", "京东云", "移动云", "联通云", "算力云", "收藏", "富贵", "宝贵",
        "南宁", "西宁", "济宁", "海宁", "普宁", "长宁", "遂宁", "咸宁", "江宁", "伊宁", "集宁", "永宁",
        "全新", "最新", "更新", "创新", "重新", "上新", "焕新", "崭新", "清新", "刷新", "一新", "推新", "出新",
    )

    /**
     * 简称是不是一个车牌的开头：
     * - 前面紧挨着字母数字的不是；
     * - 前面是以简称结尾的词（[WORD_ENDINGS]）的不是，除非带着牌上的「·」；
     * - 前面紧挨着汉字（「刚才苏B1LE22闯红灯」）时，简称和字母之间不能有空格：「张宁 E10234」是人名接工号，
     *   「曜石黑 V2309A」是颜色接型号；引出车牌的字（[LEAD_INS]）、省份全称后面不管；
     * - 「新」「云」平常也是个词头，还得有引出车牌的字、牌上的「·」，或者这一行就是这块牌。
     */
    private fun chinaLeadOk(text: String, m: MatchResult): Boolean {
        val start = m.range.first
        val province = text[start]
        val before = text.getOrNull(start - 1)
        val dotted = m.value.drop(1).any { it in "·•・" }
        if (before != null && !before.isHan() && before.isLetterOrDigit()) return false
        val word = WORD_ENDINGS.any { text.startsWith(it, start + 1 - it.length) }
        if (word && !dotted) return false
        val ledIn = before != null && before.isHan() &&
            (before in LEAD_INS || FULL_NAMES[province]?.any { text.startsWith(it, start - it.length) } == true)
        if (before != null && before.isHan() && !ledIn && text.getOrNull(start + 1)?.isWhitespace() == true) return false
        if (province !in COMMON_WORD_PROVINCES || ledIn || dotted) return true
        return text.isBlank(0 until start) && text.isBlank(m.range.last + 1 until text.length)
    }

    // ---------- 日本 ----------

    /** 日本的地名，2025 年的 138 个。 */
    private const val JP_REGION = "札幌|函館|旭川|室蘭|釧路|帯広|北見|知床|苫小牧|十勝|青森|八戸|弘前|岩手|盛岡|平泉|宮城|仙台|秋田|山形|" +
        "庄内|福島|会津|郡山|白河|いわき|水戸|土浦|つくば|宇都宮|とちぎ|那須|日光|群馬|前橋|高崎|大宮|川口|所沢|川越|熊谷|" +
        "春日部|越谷|千葉|成田|習志野|市川|船橋|袖[ヶケが]浦|市原|野田|柏|松戸|品川|世田谷|練馬|杉並|板橋|足立|江東|葛飾|" +
        "江戸川|八王子|多摩|横浜|川崎|湘南|相模|山梨|富士山|新潟|長岡|上越|長野|松本|諏訪|安曇野|南信州|富山|石川|金沢|福井|" +
        "岐阜|飛騨|静岡|浜松|沼津|伊豆|名古屋|豊橋|三河|岡崎|豊田|尾張小牧|一宮|春日井|三重|鈴鹿|四日市|伊勢志摩|滋賀|京都|" +
        "なにわ|大阪|和泉|堺|神戸|姫路|奈良|飛鳥|和歌山|鳥取|島根|出雲|岡山|倉敷|広島|福山|山口|下関|徳島|香川|高松|愛媛|" +
        "高知|福岡|北九州|久留米|筑豊|佐賀|長崎|佐世保|厳原|熊本|大分|宮崎|鹿児島|奄美|沖縄"

    /** 地名 + 分类号：「品川 330」「品川 30A」。 */
    private const val JP_TOP = "(?:$JP_REGION) ?[0-9][0-9ACFHKLMPXY]{0,2}"

    /** 牌上的平假名（不用お、し、へ、ん），驻日美军的牌上是拉丁字母。 */
    private const val JP_KANA = "(?:[あいうえかきくけこさすせそたちつてとなにぬねのはひふほまみむめもやゆよらりるれろわを]|[YAEHKMBT])"

    private const val DASH = "[-‐‑‒–—―−ー一－]"
    private const val JP_DOT = "[・·•.∙･…‧⋅]"

    /** 番号：「12-34」，不满四位的前面补点「・123」「・・12」。 */
    private const val JP_NUMBER = "(?:[1-9][0-9] ?$DASH? ?[0-9]{2}|(?:$JP_DOT ?){1,3}[0-9]{1,3})"

    /** 地名前后不能紧挨着汉字、片假名、字母数字；平假名可以，「お車は品川330さ1234です」。 */
    private const val JP_EDGE = "\\u30a0-\\u30ff\\u3400-\\u9fffA-Za-z0-9"

    private const val JP_ONE_LINE = "(?<![$JP_EDGE])$JP_TOP ?$JP_KANA ?[:：]? ?$JP_NUMBER(?![$JP_EDGE])"

    // ---------- 其余写法独特的 ----------

    /** 俄罗斯的牌只用长得像拉丁字母的 12 个西里尔字母，OCR 读成拉丁字母；О 常读成 0，У、Р 常读成小写的 y、p。 */
    private const val RU = "[ABEKMHOPCTYX0py]"

    /** 荷兰的牌只用辅音，不用 C、Q。 */
    private const val NL = "[BDFGHJKLMNPRSTVWXZ]"
    private const val NL_SEP = "[-·•.−–—－]"

    private const val IN_STATE = "A[NPRS]|BR|C[GH]|D[DLN]|GA|GJ|H[PR]|J[HK]|K[AL]|L[AD]|M[HLNPZ]|NL|O[DR]|P[BY]|RJ|SK|T[GNRS]|U[AKP]|WB"
    private const val IN_SEP = "[ .·•・\\-]{0,3}"

    private const val LATIN_START = "(?<![A-Za-z0-9])"

    /** 一行里哪儿都认、认了就打码的写法，中国大陆以外的。排在前面的先占位置。 */
    val DISTINCT: List<PlateShape> = listOf(
        // 日本，排成一行的：品川 330 さ 12-34
        PlateShape(JP_ONE_LINE),
        // 印度：MH 12 AB 1234（州代码是定的），全国通用的 21 BH 1234 AA
        PlateShape("$LATIN_START(?:2[1-9]|[3-9][0-9])${IN_SEP}BH$IN_SEP[0-9]{4}$IN_SEP[A-HJ-NP-Z]{1,2}$END"),
        PlateShape("$LATIN_START(?:$IN_STATE)$IN_SEP[0-9]{1,2}[CSEPRTVYGL]?$IN_SEP[A-HJ-NP-Z]{1,2}$IN_SEP[0-9]{4}$END", ::latinOk),
        // 英国（2001 起）：AB12 CDE，中间两位是年份。「BA15 LHR」是航班号接机场，「FY24 EPS」「SS25 NEW」是财年、季节
        PlateShape("$LATIN_START([A-HJ-PR-Y]{2})(?:0[2-9]|[12][0-9]|5[1-9]|[67][0-9])$SEP?([A-HJ-PR-Z]{3})$END") { t, m ->
            latinOk(t, m) && m.groupValues[1] !in SEASONS && m.groupValues[2] !in WORDS && m.groupValues[2] !in AIRPORTS &&
                m.groupValues[2] !in COLOURS
        },
        // 法国（AB-123-CD）、意大利（AB 123CD）、阿根廷、斯洛伐克。至少要有一处分隔：连写的「AB123CD」常是订单号；
        // 「XL 128GB」「NW 250 KG」两头是尺码、单位
        PlateShape("$LATIN_START(?!SS)([A-NP-Z]{2})($SEP?)(?!000)[0-9]{3}($SEP?)([A-HJ-NP-Z]{2})$END") { t, m ->
            val seps = m.groupValues[2] + m.groupValues[3]
            latinOk(t, m) && seps.isNotEmpty() &&
                (seps.any { it != ' ' } || m.groupValues[1] !in UNITS && m.groupValues[4] !in UNITS)
        },
        // 巴西（南方共同市场）：ABC1D23
        PlateShape("$LATIN_START([A-Z]{3})[ \\-·•.]?[0-9][A-J][0-9]{2}$END") { t, m -> latinOk(t, m) && m.groupValues[1] !in WORDS },
        // 俄罗斯，带地区码：A123BC 77
        PlateShape("$LATIN_START($RU)$SEP?(?!000)[0-9]{3}$SEP?($RU)($RU)[ |]{0,2}(?:[0-9]{2}|[1-9][0-9]{2})(?: ?RUS)?$END") { t, m ->
            latinOk(t, m) && (1..3).count { m.groupValues[it] != "0" } >= 2
        },
        // 乌克兰、保加利亚：AA 1234 BB
        PlateShape("$LATIN_START[ABCEHIKMOPTX]{2}$SEP?[0-9]{4}$SEP?([ABCEHIKMOPTX]{2})$END") { t, m ->
            latinOk(t, m) && m.groupValues[1] !in UNITS
        },
        // 新加坡：SBA 1234 G，最后一个字母是校验字母。前缀的头一个字母是 S（私家车）、E、F、G、P、Q、R、T、W、X、Y 之一
        PlateShape("(?<![A-Za-z0-9\\-])([EFGPQRSTWXY][A-HJ-NP-Z]{1,2})$SEP{0,2}([1-9I][0-9IO]{2,3})$SEP{0,2}([A-EGHJ-MPR-UX-Z])$END") { t, m ->
            latinOk(t, m) && singaporeCheck(m.groupValues[1], m.groupValues[2], m.groupValues[3][0])
        },
        // 比利时：1-ABC-123
        PlateShape("$LATIN_START[1-3][\\-·•.][A-Z]{3}[\\-·•.][0-9]{3}$END", ::latinOk),
        // 荷兰（1978 起的 4–14 号版式）：GB-001-B、12-GBB-1……三段之间一定有连字符。「BLK-10-L」是颜色代号
        PlateShape(
            "$LATIN_START(?:$NL{2}$NL_SEP[0-9]{2}$NL_SEP$NL{2}|$NL{2}$NL_SEP$NL{2}$NL_SEP[0-9]{2}|[0-9]{2}$NL_SEP$NL{2}$NL_SEP$NL{2}|" +
                "[0-9]{2}$NL_SEP$NL{3}$NL_SEP[0-9]|[0-9]$NL_SEP$NL{3}$NL_SEP[0-9]{2}|$NL{2}$NL_SEP[0-9]{3}$NL_SEP$NL|$NL$NL_SEP[0-9]{3}$NL_SEP$NL{2}|" +
                "$NL{3}$NL_SEP[0-9]{2}$NL_SEP$NL|$NL$NL_SEP[0-9]{2}$NL_SEP$NL{3}|[0-9]$NL_SEP$NL{2}$NL_SEP[0-9]{3}|[0-9]{3}$NL_SEP$NL{2}$NL_SEP[0-9])$END"
        ) { t, m -> latinOk(t, m) && m.value.split(Regex("[^A-Z]+")).none { it in COLOURS } },
        // 爱尔兰（2013 起）：241-D-12345，中间是郡代码，两段之间是连字符
        PlateShape(
            "$LATIN_START(?:1[3-9]|2[0-9])[12][\\-–](?:C|CE|CN|CW|D|DL|G|KE|KK|KY|L|LD|LH|LM|LS|MH|MN|MO|OY|RN|SO|T|W|WH|WW|WX)" +
                "[\\-–][1-9][0-9]{0,5}$END(?![.,][0-9])",
            ::latinOk,
        ),
        // 越南：51F-123.45，连字符和点都在
        PlateShape("(?<![A-Za-z0-9.\\-])(?:1[1-9]|[2-9][0-9])[A-HK-NPR-VX-Z]{1,2}[\\-–—][0-9]{3}[.·][0-9]{2}(?![0-9.])", ::latinOk),
    )

    /** 一行里写着货号、型号、批号的，里面那串字母数字是编号，不是车牌：「SKU KB-750-BK」「Colour code BLK-10-L」「LOT 2B15」。 */
    private val CODE_CONTEXT = Regex(
        "(?i)(?<![a-z])(?:sku|ref|art|art\\.?-?nr|item|model|modell|modèle|p/n|pn|part|lot|batch|s/n|sn|serial|order|bestell|" +
            "coupon|promo|voucher|code|err|error|commit|head|sha|hash|build|version|ver)(?![a-z])"
    )

    /** 这一行别处没写着货号、批号这类字（[CODE_CONTEXT]）。牌子自己不算：「SN22 OHM」打头的就是「SN」。 */
    private fun latinOk(text: String, m: MatchResult): Boolean =
        !CODE_CONTEXT.containsMatchIn(text.replaceRange(m.range, " ".repeat(m.value.length)))

    /**
     * 头一段读进了欧盟蓝条上的国别字母（[head] 是那一段打头的几个字母：「PAB」「FAB」）：连着字母是个词、单位（「SKU-123-CD」），
     * 哪一段是颜色代号（「BLK-100-WH」），或者这一行写着货号、批号的，不是。
     */
    fun gluedBandOk(text: String, head: String): Boolean =
        head !in WORDS && head !in UNITS && text.split(NOT_LETTERS).none { it in COLOURS } && !CODE_CONTEXT.containsMatchIn(text)

    private val NOT_LETTERS = Regex("[^A-Z]+")

    // ---------- 写法不够独特的：只认照片上孤零零的一行 ----------

    /** 照片上孤零零一行才认、认了也不打码的写法（[LonePlate]）。整行对得上才算。 */
    val ALONE: List<PlateShape> = listOf(
        // 德国：B MW 1234，地区码和字母之间贴着年检标，OCR 读成「:」或空格。数字至少两位：「BMW X5」「LG C3」是型号
        PlateShape("([A-ZÄÖÜ]{1,3})[ \\-·•・.:]{1,3}([A-Z]{1,2})[ \\-·•・.]?[1-9][0-9]{1,3}[EH]?") { _, m ->
            m.groupValues[1] !in NOT_A_PREFIX && m.groupValues[2] !in WORDS
        },
        // 西班牙：1234 BCD，只用辅音
        PlateShape("[0-9]{4}$SEP?([BCDFGHJKLMNP][BCDFGHJKLMNPRSTVWXYZ]{2})") { _, m -> m.groupValues[1] !in UNITS },
        // 台湾、美国纽约等州、巴西旧牌：ABC-1234
        PlateShape("([A-HJ-NP-Z]{3})[\\-·•][0-9]{4}") { _, m -> m.groupValues[1] !in UNITS && m.groupValues[1] !in WORDS },
        // 美国加州：7ABC123
        PlateShape("[1-9]([A-Z]{3})[0-9]{3}") { _, m -> m.groupValues[1] !in WORDS },
        // 印尼：B 1234 ABC
        PlateShape("[A-Z]{1,2} [1-9][0-9]{3} ([A-Z]{2,3})") { _, m -> m.groupValues[1] !in UNITS && m.groupValues[1] !in WORDS },
        // 波兰、挪威、丹麦：WA 12345、WPI 1234A、AB 12 345。美国的州加邮编「CA 94105」不算，华沙的 WA、WI 例外
        PlateShape("([A-Z]{2,3}) (?:([0-9]{5})|[0-9]{4}[A-Z]|[0-9]{2} [0-9]{3})") { _, m ->
            val prefix = m.groupValues[1]
            prefix !in NOT_A_PREFIX && prefix !in WORDS && (m.groupValues[2].isEmpty() || prefix !in US_STATES)
        },
        // 捷克：1A2 3456
        PlateShape("[1-9]([A-Z][0-9A-Z]) ?[0-9]{4}") { _, m -> m.groupValues[1] !in UNITS },
        // 土耳其：34 ABC 123，头两位是省份代码，组之间有空格
        PlateShape("(?:0[1-9]|[1-7][0-9]|8[01]) ([A-Z]{1,3}) [0-9]{2,4}") { _, m ->
            m.groupValues[1] !in UNITS && m.groupValues[1] !in WORDS
        },
        // 俄罗斯，地区码读丢了：A123BC
        PlateShape("$RU ?(?!000)[0-9]{3} ?$RU{2}"),
        // 澳门、葡萄牙、荷兰旧牌：MA-12-34、AA-00-AA、12-34-AB。「10-30-AM」是时间
        PlateShape("(?:M|[A-Z]{2})-[0-9]{2}-[0-9]{2}|[A-Z]{2}-[0-9]{2}-[A-Z]{2}|[0-9]{2}-[A-Z]{2}-[0-9]{2}|[0-9]{2}-[0-9]{2}-([A-Z]{2})") { _, m ->
            m.groupValues[1] !in UNITS
        },
    )

    // ---------- 上下两行 ----------

    /**
     * 上下两行拼起来才认得出的（[TwoRowPlate]），打码：中国大陆的「苏B」/「12345」「鲁H」/「1234挂」，
     * 日本的「品川 300」/「あ 12-34」。其余写法独特的照 [DISTINCT] 对。
     */
    val TWO_ROW: List<PlateShape> = listOf(
        PlateShape("$PROVINCE$CN_SEP$CITY (?:$SERIAL5|$SERIAL4$SEP?[挂学])"),
        PlateShape("$JP_TOP (?:$JP_KANA ?$JP_NUMBER|$JP_KANA? ?[1-9][0-9] ?$DASH? ?[0-9]{2}|$JP_KANA ?[1-9][0-9]{0,2})"),
    )

    /**
     * 上下两行拼起来、写法不够独特的，认了也不打码：越南的「51F」/「123.45」、意大利摩托的「AB」/「12345」、香港方牌，
     * 日本牌假名和补位的点都读丢了的「品川 300」/「12」。不收三个字母压四位数字（巴西旧摩托牌）：表格里「PIN」下面是「1234」。
     */
    val TWO_ROW_LOOSE: List<PlateShape> = listOf(
        PlateShape("(?:1[1-9]|[2-9][0-9])-?[A-HK-NPR-VX-Z][A-HK-NPR-VX-Z0-9]? (?:[0-9]{3}[.·]?[0-9]{2}|[0-9]{4})"),
        PlateShape("[A-HJ-NPR-Z]{2} (?:[1-9][0-9]{0,3}|[0-9]{5})"),
        PlateShape("$JP_TOP (?:$JP_DOT ?){0,3}[1-9][0-9]{0,2}"),
    )

    // ---------- 字段名 ----------

    /**
     * 车牌的字段名。不收单独的「Plate」「Placa」「Targa」（盘子、主板、保时捷 Targa），「VRM」（主板的供电模块）、
     * 「VRN」（英国的增值税号），「车号」（火车票上是车厢号）。
     */
    private val LATIN_LABELS = listOf(
        "license plate number", "licence plate number", "license plate no", "licence plate no", "license plate", "licence plate",
        "plate number", "plate no", "plate #", "number plate", "registration plate", "registration mark", "vehicle registration number",
        "vehicle registration mark", "vehicle registration", "vehicle reg no", "vehicle reg", "vehicle number", "vehicle no", "car plate",
        "kfz-kennzeichen", "amtliches kennzeichen", "kennzeichen", "nummernschild", "kontrollschild",
        "plaque d'immatriculation", "n° d'immatriculation", "numéro d'immatriculation", "numéro de plaque", "immatriculation",
        "kentekenplaat", "kenteken", "nummerplaat", "numero di targa", "número de matrícula", "matrícula", "matricula",
        "número de placa", "placa do veículo", "placa del vehículo", "placa de rodaje", "placas", "patente", "dominio", "ppu",
        "rendszám", "numer rejestracyjny", "nr rej", "spz", "registrační značka", "evidenčné číslo", "număr de înmatriculare",
        "nr. auto", "registarska oznaka", "registrska številka", "registreringsnummer", "reg.nr", "regnr", "kjennemerke",
        "nummerplade", "rekisterinumero", "rekisteritunnus", "skráningarnúmer", "registreerimismärk", "reģistrācijas numurs",
        "valstybinis numeris", "numru tal-karozza", "uimhir chláraithe", "registrasienommer", "plaka no", "plaka", "rego",
        "nomor polisi", "nopol", "no. pendaftaran", "nombor pendaftaran", "biển số xe", "biển số", "bien so xe", "bien so",
    )

    /**
     * 中日文的字段名。「ナンバー」前面紧挨着片假名的不算：「シリアルナンバー」「マイナンバー」。
     * 证件、表格上也是下一个字段的字段名（DefaultRuleSet.OTHER_FIELDS）：「所有人 张三 号牌号码 苏A12345」的名字到它为止。
     */
    internal val CJK_LABELS = listOf(
        "车牌号码", "车牌号", "车牌", "号牌号码", "号牌", "车辆号牌", "机动车号牌", "牌照号码", "牌照", "车牌照",
        "車牌號碼", "車牌號", "車牌", "車輛號牌",
        "ナンバープレート", "車のナンバー", "車両ナンバー", "ナンバー", "車両番号", "車番", "自動車登録番号",
    )

    private fun alternation(words: List<String>) = words.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    /** 字段名。前面不能紧挨着字母数字和假名；英文的后面也不能紧跟着字母（「Plakat」「Plates」）。 */
    val LABEL = Regex(
        "(?<![A-Za-z0-9\\u3040-\\u30ff])(?:(?:" + alternation(LATIN_LABELS) + ")(?![A-Za-z])|" + alternation(CJK_LABELS) + ")",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 字段名和值之间：冒号、井号、等号、破折号、「is」「是」「为」「は」，可带一段括注（「License plate (front):」）。
     * 法文冒号前有空格（「Immatriculation : …」）。
     */
    private val FILLER = Regex("[ #.]*(?:[(（][^)）]{1,16}[)）])? *(?:[:：=＝#\\-–]|is(?= )|是|为|は)? *", RegexOption.IGNORE_CASE)

    /**
     * 一段字末尾的字段名，连同后面的冒号、括注：地址取到了行尾、车牌在后半截（「…中山路1号 车牌号：」「…1号 号牌号码 Plate No」），
     * 地址让给车牌时截到它前面（RuleClassifier）。
     */
    val LABEL_AT_END = Regex("(?:${LABEL.pattern})${FILLER.pattern}$", RegexOption.IGNORE_CASE)

    /** 写明了这是字段名的分隔：冒号这类、「is」「是」，缩写的点（「Nr rej.」「Reg.」）。 */
    private val EXPLICIT = Regex("[:：=＝#.\\-–]|is|是|为|は", RegexOption.IGNORE_CASE)

    /** 本身就说了「号码」的字段名：「Plate no.」「License plate number」「Nr rej.」。 */
    private val NUMBERED = Regex("(?i)(?:number|no|nr|nummer|numero|número|numer|numurs|nomor|nombor|#)$")

    /** 字段名后面任何写法的车牌：大写字母数字，组之间至多隔一个分隔符，至多 16 个字符。 */
    private val GENERIC = Regex(
        "[A-Z0-9ÄÖÜÅÆØČŠŽĆĐÞ](?:[A-Z0-9ÄÖÜÅÆØČŠŽĆĐÞ]|[ ·•・.\\-–—](?=[A-Z0-9ÄÖÜÅÆØČŠŽĆĐÞ])){1,15}$END"
    )

    private val CN_VALUE = Regex("$CN_HEAD[A-Z0-9l*＊]{4,6}(?:$SEP?[学警挂试超领港澳])?$END")

    /**
     * 字段名后面、从 [start] 起的车牌。先认各种写法（越长越好）；字段名写明了的（[explicit]），对不上写法的也认，
     * 只要像个号码：至少三个字母数字、有数字；纯数字的至少五位或者带分隔（「12-34」）；至多两组纯数字
     * （「552 120 222」是公司注册号）；不夹着「OF」这类词（「PLATE NO. 1 OF 4」）。
     * 没写明的英文字段名后面常是普通的字：「LICENSE PLATE FRAME 2PCS」「Vehicle Registration 2025 Renewal」。
     */
    fun valueAt(text: String, start: Int, explicit: Boolean): IntRange? {
        if (start >= text.length) return null
        val shapes = sequenceOf(CN_VALUE).map { it.matchAt(text, start)?.range } +
            (DISTINCT + ALONE + TWO_ROW_LOOSE).asSequence().map { it.matchAt(text, start) }
        shapes.filterNotNull().maxByOrNull { it.last }?.let { return it }
        if (!explicit) return null
        val r = GENERIC.matchAt(text, start)?.range ?: return null
        val value = text.substring(r)
        val groups = value.split(' ', '-', '–', '—', '.', '·', '•', '・').filter { it.isNotEmpty() }
        val alnum = value.count { it.isLetterOrDigit() }
        val digits = value.count { it.isDigit() }
        if (alnum < 3 || digits == 0) return null
        if (digits == alnum && digits < 5 && groups.size < 2) return null
        // 以色列的「123-45-678」三段都是数字，用连字符连着；空格隔开的三段数字是别的号码
        if (value.split(' ').count { g -> g.isNotEmpty() && g.all { it.isDigit() } } > 2 || groups.any { it in GENERIC_STOP }) return null
        return r
    }

    /** 字段名后面的值从哪里起、字段名写明了没有（分隔符、本身说了「号码」、中日文的字段名）。 */
    fun afterLabel(text: String, label: MatchResult): Pair<Int, Boolean> {
        val f = FILLER.matchAt(text, label.range.last + 1)
        val start = f?.range?.let { it.last + 1 } ?: (label.range.last + 1)
        val explicit = (f != null && EXPLICIT.containsMatchIn(f.value)) || NUMBERED.containsMatchIn(label.value) ||
            label.value.any { it.isHan() || it in '぀'..'ヿ' }
        return start to explicit
    }

    /** 表单上单独一行的车牌字段名，车牌在它下面一行（StackedField.header）。 */
    val HEADER = Regex(
        "^[ *·•]*(?:" + alternation(LATIN_LABELS + CJK_LABELS) + ") *[:：#]? *[*]?",
        RegexOption.IGNORE_CASE,
    )

    /** 字段名下面那一行里的车牌：整段就是一个车牌。 */
    fun valueIn(cell: String): IntRange? {
        val start = cell.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return null
        val end = cell.indexOfLast { !it.isWhitespace() }
        return valueAt(cell, start, explicit = true)?.takeIf { it.last == end }
    }

    // ---------- 校验与排除词 ----------

    /**
     * 新加坡的校验字母：前缀的后两个字母（A=1……Z=26）和补足四位的数字，按 9、4、5、4、3、2 加权求和，除以 19 的余数查表。
     * 数字里读成 I、O 的是 1、0：前缀不用 I、O。
     */
    fun singaporeCheck(prefix: String, number: String, check: Char): Boolean {
        val letters = prefix.takeLast(2).map { it - 'A' + 1 }
        val digits = number.replace('I', '1').replace('O', '0').padStart(4, '0').map { it - '0' }
        val sum = letters[0] * 9 + letters[1] * 4 + digits[0] * 5 + digits[1] * 4 + digits[2] * 3 + digits[3] * 2
        return SINGAPORE_CHECK[sum % 19] == check
    }

    private const val SINGAPORE_CHECK = "AZYXUTSRPMLKJHGEDCB"

    /** 和车牌的字母组撞上的币种、单位、时区、月份（几种语言的）：「1000 CNY」「16 GB 512」「1530 GMT」「24 DEZ 2025」。 */
    private val UNITS = setOf(
        // 币种（ISO 4217，常见的）
        "AED", "AFN", "ALL", "AMD", "ARS", "AUD", "AZN", "BAM", "BDT", "BGN", "BHD", "BND", "BOB", "BRL", "BTC", "BWP", "BYN",
        "CAD", "CHF", "CLP", "CNY", "COP", "CRC", "CZK", "DKK", "DOP", "DZD", "EGP", "ETB", "ETH", "EUR", "GBP", "GEL", "GHS",
        "GTQ", "HKD", "HNL", "HUF", "IDR", "ILS", "INR", "IQD", "IRR", "ISK", "JMD", "JOD", "JPY", "KES", "KGS", "KHR", "KRW",
        "KWD", "KZT", "LAK", "LBP", "LKR", "MAD", "MDL", "MKD", "MMK", "MNT", "MOP", "MUR", "MXN", "MYR", "NGN", "NOK", "NPR",
        "NZD", "OMR", "PEN", "PHP", "PKR", "PLN", "PYG", "QAR", "RMB", "RON", "RSD", "RUB", "SAR", "SEK", "SGD", "THB", "TND",
        "TRY", "TWD", "TZS", "UAH", "UGX", "USD", "USDT", "UYU", "UZS", "VES", "VND", "XAF", "XOF", "ZAR", "ZMW",
        // 单位
        "MHZ", "GHZ", "KHZ", "KWH", "MWH", "GWH", "BPM", "PCS", "PTS", "HDR", "FPS", "DPI", "PPI", "RPM", "MAH", "LBS", "PPM", "CFM",
        "GB", "MB", "KB", "TB", "PB", "AM", "PM", "MM", "CM", "KM", "KG", "ML", "HZ", "MIN", "SEC", "HRS", "KW", "HP", "PS", "NM",
        // 时区
        "GMT", "UTC", "PST", "PDT", "MST", "MDT", "CST", "CDT", "EST", "EDT", "BST", "CET", "CEST", "EET", "JST", "KST", "HKT",
        "SGT", "IST", "AEST", "AEDT", "NZST", "WIB",
        // 月份：英、德、法、西、葡、意、荷
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "SEPT", "OCT", "NOV", "DEC", "MRZ", "MÄR", "MAI", "OKT",
        "DEZ", "JANV", "FEV", "FÉV", "AVR", "JUI", "AOU", "AOÛ", "ENE", "ABR", "AGO", "DIC", "FEV", "OUT", "SET", "GEN", "GIU",
        "LUG", "OTT", "MRT", "MEI", "BMW", "GMC",
    )

    /** 和车牌的字母组撞上的英文词、缩写：「AS OF 2024」「NO 12345」「REF 10086」「PLATE NO. 1 OF 4」。 */
    private val WORDS = setOf(
        "OF", "ON", "IN", "AT", "TO", "BY", "OR", "IS", "IT", "AS", "AN", "UP", "NO", "MY", "WE", "GO", "DO", "SO", "BE", "US",
        "VS", "X", "PO", "ID", "NR", "TEL", "FAX", "REF", "INV", "ORD", "QTY", "NUM", "VOL", "TOP", "NEW", "WIN", "EST", "OFF",
        "FOR", "THE", "AND", "ALL", "BUY", "GET", "PRO", "MAX", "TEE", "EPS", "GDP", "YOY", "QOQ", "ARR", "DEP", "ETA", "ETD",
        "MO", "DI", "MI", "FR", "SA", "LU", "MA", "ME", "JE", "VE", "VR", "OS", "LV", "XP", "MP", "ATK", "DEF", "EXP", "LOT",
        "PIN", "SN", "ZIP", "BOX", "CRN", "PLU", "SKU", "RMA", "TKT", "EMP", "ERR", "BUG", "DEV", "ENG", "APP", "REQ", "WEB",
        "PAY", "CRM", "SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT", "JU", "VI", "WO", "ZA", "ZO", "XT", "XTX", "TI",
    )

    /** 字段名后面的值里不该有的词：「PLATE NO. 1 OF 4」。两个字母的「IS」「TO」不收，它们也是地区码（罗马尼亚的雅西）。 */
    private val GENERIC_STOP = setOf("OF", "THE", "AND", "FOR", "WITH")

    /** 打头的不是德国、波兰的地区码，是标准、指数、品牌、显卡：「ISO 27001」「DAX 19254」「RTX 4090D」「AMG GT 63」。 */
    private val NOT_A_PREFIX = UNITS + setOf(
        "ISO", "IEC", "DIN", "EN", "BS", "ECE", "UN", "ANSI", "IEEE", "ASTM", "SAE", "JIS", "GB", "IP", "DAX", "HSI", "NDX",
        "SMI", "DJI", "NKY", "SPX", "FTSE", "CAC", "IBEX", "RTX", "GTX", "RX", "AMG", "KIA", "LG", "DJI", "MSI", "HTC", "TCL",
        "AKG", "HPE", "EA", "WI", "FI", "SR", "I", "NEU",
    )

    /** 美国的州，华沙的 WA、WI 除外：「CA 94105」是州加邮编。 */
    private val US_STATES = setOf(
        "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA", "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
        "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ", "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
        "SD", "TN", "TX", "UT", "VT", "VA", "WV", "WY", "DC",
    )

    /** 打头的是时装季、财年、集数：「SS25」「FW24」「FY25」「EP10」。 */
    private val SEASONS = setOf("FY", "CY", "HY", "SS", "AW", "FW", "EP", "PS")

    /** 颜色代号：「BLK-10-L」。 */
    private val COLOURS = setOf("BLK", "WHT", "GRN", "GRY", "BRN", "PNK", "PRP", "SLV", "NVY", "BLU", "RD")

    /** 常见机场代码：「BA15 LHR」是航班号接机场。 */
    private val AIRPORTS = setOf(
        "LHR", "LGW", "STN", "LTN", "MAN", "EDI", "GLA", "BHX", "BRS", "DUB", "JFK", "EWR", "LGA", "LAX", "SFO", "SEA", "ORD",
        "ATL", "DFW", "DEN", "IAH", "MIA", "BOS", "IAD", "DCA", "PHL", "PHX", "LAS", "MCO", "SAN", "YYZ", "YVR", "YUL", "CDG",
        "ORY", "AMS", "FRA", "MUC", "ZRH", "GVA", "VIE", "CPH", "ARN", "OSL", "HEL", "MAD", "BCN", "FCO", "MXP", "LIS", "IST",
        "DXB", "AUH", "DOH", "SIN", "HKG", "PEK", "PKX", "PVG", "SHA", "CAN", "SZX", "CTU", "KMG", "XIY", "HGH", "NKG", "ICN",
        "GMP", "NRT", "HND", "KIX", "NGO", "TPE", "TSA", "BKK", "DMK", "KUL", "CGK", "MNL", "SGN", "HAN", "DEL", "BOM", "SYD",
        "MEL", "BNE", "AKL", "JNB", "CPT", "CAI", "GRU", "EZE", "SCL", "BOG", "LIM", "MEX", "CUN",
    )
}

/** 一种写法：正则找形状，[valid] 拿整行文字再验一道（校验字母、排除词、前后文）。 */
internal class PlateShape(pattern: String, private val valid: (String, MatchResult) -> Boolean = { _, _ -> true }) {

    private val regex = Regex(pattern)

    fun findAll(text: String): Sequence<MatchResult> = regex.findAll(text).filter { valid(text, it) }

    fun matches(text: String): Boolean = regex.matchEntire(text)?.let { valid(text, it) } == true

    /** 从 [index] 起的一块牌，后面不能紧跟着字母数字。 */
    fun matchAt(text: String, index: Int): IntRange? =
        regex.matchAt(text, index)?.takeIf { valid(text, it) && text.getOrNull(it.range.last + 1)?.isLetterOrDigit() != true }?.range
}

private fun String.isBlank(range: IntRange): Boolean = range.all { this[it].isWhitespace() }

/**
 * 一行里写法独特的车牌（[Plates.CHINA]、[Plates.DISTINCT]），在哪儿都认。
 * 中国大陆的跑在 HanView 上：ML Kit 逐字切分的「北 京 CA1234」拼回「北京CA1234」，才看得出「京」是城市名的末一个字。
 */
class PlateShapes(private val confidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        val out = ArrayList<IntRange>()
        val view = HanView.of(text)
        Plates.CHINA.forEach { s ->
            s.findAll(view.text).forEach { m ->
                val r = view.toSource(m.range)
                if (out.none { it.overlaps(r) }) out += r
            }
        }
        Plates.DISTINCT.forEach { s ->
            s.findAll(text).forEach { m -> if (out.none { it.overlaps(m.range) }) out += m.range }
        }
        return out.sortedBy { it.first }.map { RuleMatch(it, confidence) }
    }
}

/**
 * 字段名后面的车牌：「车牌号：京A12345」「License plate: 7ABC123」「Kennzeichen M-AB 1234」。
 * 字段名写明了的（冒号、中日文字段名）后面什么写法都认；没写明的英文字段名后面要对得上一种车牌写法，见 [Plates.valueAt]。
 *
 * 跑在 HanView 上：逐字切分的「车 牌 号 京 A 12345」照样认得出字段名。
 */
class PlateLabel(private val confidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        val t = view.text
        return Plates.LABEL.findAll(t).mapNotNull { label ->
            val (start, explicit) = Plates.afterLabel(t, label)
            Plates.valueAt(t, start, explicit)?.let { RuleMatch(view.toSource(it), confidence, outlineOnly = false) }
        }.toList()
    }
}

/**
 * 照片上孤零零一行的车牌（[Plates.ALONE]）：德国、西班牙这些写法不够独特的，一句话里出现的多半是型号、金额，
 * 截图里单独一行的也多半是（价钱、工单号、型号）。车的照片上，牌子自己成一行，周围没有别的字。
 *
 * 所以要两样都对：
 * - 整行就是一块牌。牌上的别的东西常和号码读进同一行：欧盟蓝条上的国别字母（「F AB-123-CD」，一圈星星读成「O」：
 *   「OE 1234 BCD」）、法国右边的省份号（「… 75」）、意大利的省份贴（「… RM」）。行首至多去掉两段、行尾至多去掉一段再对；
 * - 附近没有别的字：上下左右两个字高以内没有别的行，牌上的小字（州名、国别、「RUS」）不算。
 * 认了也不打码。
 *
 * 欧盟蓝条的国别字母粘在号码上、去掉它是写法独特的一种的（「PAB-123-CD」，见 [gluedBand]），也要孤零零一行才认，认了打码：
 * 去掉头一个字母就像法国的牌，截图里、规格表里的型号多的是（「HDX-500-WH」「MSR-150-BK」「DHL-123-AB」）。
 */
class LonePlate(private val confidence: Float) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> = page.texts.indices.mapNotNull { i ->
        val text = page.texts[i]
        val glued = gluedBand(text)
        val r = glued ?: bodyOf(text) ?: return@mapNotNull null
        if (!alone(page, i, listOf(i))) return@mapNotNull null
        PageMatch(i, RuleMatch(r, confidence, outlineOnly = glued == null))
    }

    companion object {
        /** 这一行去掉牌上的杂字以后，整段对得上 [Plates.ALONE] 的那一段。国别字母粘在号码上的（见 [gluedBand]），去掉它再对。 */
        internal fun bodyOf(text: String): IntRange? = body(text, Plates.ALONE, glued = false) ?: body(text, Plates.ALONE, glued = true)

        /**
         * 欧盟蓝条上的国别字母和号码读成了一段（「PAB-123-CD」「FAB-123-CD」，F 常读成 P）：头一段去掉打头那个字母，
         * 整段对得上写法独特的一种（[Plates.DISTINCT]）的那一段。照片上的牌子自成一行，牌上没有的字不该在这一行里。
         */
        internal fun gluedBand(text: String): IntRange? = body(text, Plates.DISTINCT, glued = true)

        /**
         * 这一行去掉牌上的杂字（行首至多两段国别字母、星圈，行尾一段省份号），整段对得上 [shapes] 里一种的那一段。
         * [glued]：剩下的头一段打头的那个字母是读进来的国别字母（[GLUED]），也去掉再对。
         */
        private fun body(text: String, shapes: List<PlateShape>, glued: Boolean): IntRange? {
            val tokens = TOKEN.findAll(text).map { it.range }.toList()
            if (tokens.isEmpty() || tokens.size > MAX_TOKENS) return null
            for (skip in 0..min(MAX_LEADING, tokens.size - 1)) {
                if (skip > 0 && !BAND.matches(text.substring(tokens[skip - 1]))) break
                for (drop in 0..min(1, tokens.size - 1 - skip)) {
                    if (drop == 1 && !SIDE.matches(text.substring(tokens.last()))) continue
                    var first = tokens[skip].first
                    var last = tokens[tokens.size - 1 - drop].last
                    while (first < last && (text[first] in JUNK || text[first].isWhitespace())) first++
                    while (last > first && (text[last] in JUNK || text[last].isWhitespace())) last--
                    if (glued) {
                        if (text[first] !in GLUED || !text.getOrElse(first + 1) { ' ' }.isLetter()) continue
                        val head = text.substring(first).takeWhile { it.isLetter() }
                        if (!Plates.gluedBandOk(text, head)) continue
                        first++
                    }
                    if (shapes.any { it.matches(text.substring(first, last + 1)) }) return first..last
                }
            }
            return null
        }

        private val TOKEN = Regex("[^ ]+")

        /** 读进了号码头一段的国别字母：[BAND] 里单个字母的那几个，星圈读成的 O。 */
        private const val GLUED = "DFIEPBLAHSNMVO"

        /** 行首的国别字母、星圈、年检标：「D」「OF」「NL」「O」「:」。 */
        private val BAND = Regex(
            "O?(?:D|F|I|E|P|B|NL|L|A|CH|IRL|GB|UK|PL|CZ|SK|H|RO|BG|GR|HR|SLO|SRB|S|N|DK|FIN|IS|EST|LV|LT|UA|BY|TR|RUS|KZ|M|MC|" +
                "AND|SM|V|IND|BR|RA|RCH)|[O0]|[·•.:|]+"
        )

        /** 行尾的省份号、省份贴：「75」「2A」「RM」。 */
        private val SIDE = Regex("[0-9]{1,3}|2[AB]|[A-Z]{2}|[·•.:|]+")

        private const val JUNK = "·•.:|"
        private const val MAX_LEADING = 2
        private const val MAX_TOKENS = 7

        /** 牌上的小字：州名、国别、「RUS」「TAIWAN」。字比号码小一截、字数不多。 */
        private const val SMALL_PRINT = 0.85f
        private const val SMALL_PRINT_CHARS = 14

        /** 附近：两个框之间横竖都不超过这么多个字高。截图里上下两行的间隙一般不到一个字高。 */
        private const val NEAR = 1.5f

        private val SHAPES = PlateShapes(confidence = 1f)

        /**
         * [lines] 这几行（一块牌）附近有没有别的字。不算的：牌上的小字（比号码矮一截、字数不多）、自成一行的欧盟蓝条，
         * 别的车的牌子（停车场的照片上几块牌子挨着）。
         * 截图里单独一行的价钱、工单号、型号，上下总挨着标题、说明，过不了这一关。
         *
         * 按第 [ref] 行自己的阅读方向量（Page.boxIn）：截图里一张转过、拍歪了的车的照片，按整页的方向量「附近」「矮一截」全不对。
         */
        internal fun alone(page: Page, main: Int, lines: List<Int>, ref: Int = main): Boolean {
            val boxes = lines.map { page.boxIn(it, ref = ref) }
            val h = boxes.maxOf { it.height }
            if (h <= 0f) return false
            val left = boxes.minOf { it.left }
            val right = boxes.maxOf { it.right }
            val top = boxes.minOf { it.top }
            val bottom = boxes.maxOf { it.bottom }
            val mainHeight = page.boxIn(main, ref = ref).height
            return page.texts.indices.none { j ->
                val text = page.texts[j]
                if (j in lines || text.isBlank()) return@none false
                val b = page.boxIn(j, ref = ref)
                val dx = max(0f, max(b.left - right, left - b.right))
                val dy = max(0f, max(b.top - bottom, top - b.bottom))
                if (dx > NEAR * h || dy > NEAR * h) return@none false
                val small = b.height <= SMALL_PRINT * mainHeight && text.count { !it.isWhitespace() } <= SMALL_PRINT_CHARS
                // 欧盟蓝条自成一行（「O D」「NL」）时和牌子一样高
                val band = TOKEN.findAll(text).all { BAND.matches(it.value) }
                !small && !band && bodyOf(text) == null && gluedBand(text) == null && SHAPES.findIn(text).isEmpty()
            }
        }
    }
}

/**
 * 上下两行的车牌：大车尾牌、挂车、摩托、日本的牌子、各国的方牌。OCR 读成两行，上一行短（「苏B」「品川 300」「MH 12」），
 * 下一行紧贴在它下面（「12345」「あ 12-34」「AB 1234」）。
 *
 * 实测（PP-OCRv6 small，合成的牌子）：两行一定分开读，上下两个框常常咬住一截，从不隔开；上一行落在下一行的横向范围里。
 * 日本牌下一行的假名比数字矮一半以上，常常自成一段，同一排挨着的几段拼在一起。
 *
 * 两行拼成一行（中间一个空格）再对写法：对上 [Plates.TWO_ROW] 或 [Plates.DISTINCT] 的打码；
 * 对上 [Plates.TWO_ROW_LOOSE] 或 [Plates.ALONE] 的要附近没有别的字（同 [LonePlate]），认了也不打码。两行合成一个框（PageMatch.also）。
 */
class TwoRowPlate(private val confidence: Float) : PageFinder {

    override fun findOnPage(page: Page): List<PageMatch> {
        val out = ArrayList<PageMatch>()
        val used = HashSet<Int>()
        // 只看短行；框算一次：一页上百行，两两比的时候再算就是上万次。按转过的那一行的方向量的（见 refsOf），每个方向各算一份
        val ranges = page.texts.map { t -> trimmed(t)?.takeIf { compactLength(t) <= MAX_BOTTOM } }
        val inPage = ranges.mapIndexed { i, r -> r?.let { page.box(i, it) }?.takeIf { it.height > 0f } }
        val turned = HashMap<Int, List<Bounds?>>()
        val boxesIn = { ref: Int? ->
            if (ref == null) inPage
            else turned.getOrPut(ref) { ranges.mapIndexed { i, r -> r?.let { page.boxIn(i, it, ref) }?.takeIf { it.height > 0f } } }
        }
        for (top in page.texts.indices) {
            if (top in used) continue
            val topRange = ranges[top] ?: continue
            if (compactLength(page.texts[top]) !in 2..MAX_TOP) continue
            val (row, ref) = rowBelow(page, boxesIn, top, used) ?: continue
            val joined = (listOf(top) + row).joinToString(" ") { page.texts[it].substring(ranges[it]!!) }
            val strong = Plates.TWO_ROW.any { it.matches(joined) } || Plates.DISTINCT.any { it.matches(joined) }
            val loose = !strong && (Plates.TWO_ROW_LOOSE.any { it.matches(joined) } || Plates.ALONE.any { it.matches(joined) }) &&
                LonePlate.alone(page, row.first(), listOf(top) + row, ref ?: top)
            if (!strong && !loose) continue
            out += PageMatch(
                top,
                RuleMatch(topRange, confidence, outlineOnly = loose),
                also = row.map { Span(it, ranges[it]!!) },
            )
            used += top
            used += row
        }
        return out
    }

    /**
     * 量 [top] 和它下面的 [j] 这一对按哪几个方向（截图里一张转过、拍歪了的车的照片，见 Page.boxIn），先到先试，哪个对得上算哪个：
     * 下一行转过的按下一行自己的——字大、长，框的方向读得准些；上一行转过的按上一行的；最后按这一页的（null）。
     * 短行的框方向时准时不准（照片歪了 10° 还读出过横平的框），差一两度，两行的框咬住多少就差过门槛。都没转过的只按这一页的。
     */
    private fun refsOf(page: Page, top: Int, j: Int): List<Int?> =
        if (!page.isTurned(j) && !page.isTurned(top)) IN_PAGE
        else listOfNotNull(j.takeIf { page.isTurned(it) }, top.takeIf { page.isTurned(it) }) + IN_PAGE

    /**
     * 紧贴在 [top] 下面的那一排：最近的一行，加上同一排左右挨着的几段（日本牌的假名），按从左到右排好；和量它们的方向（[refsOf]）。
     * 没有，或者上一行不在它的横向范围里，给 null。[boxesIn] 给出按哪一行的方向量的各行的框。
     */
    private fun rowBelow(page: Page, boxesIn: (Int?) -> List<Bounds?>, top: Int, used: Set<Int>): Pair<List<Int>, Int?>? {
        var first: Int? = null
        var firstGap = 0f
        var ref: Int? = null
        for (j in page.texts.indices) {
            if (j == top || j in used) continue
            for (r in refsOf(page, top, j)) {
                val boxes = boxesIn(r)
                val topBox = boxes[top] ?: continue
                val b = boxes[j] ?: continue
                if (b.centerY - topBox.centerY < 0.5f * min(b.height, topBox.height)) continue
                val gap = b.top - topBox.bottom
                if (gap > ROW_GAP * max(b.height, topBox.height) || gap < -OVERLAP * min(b.height, topBox.height)) continue
                if (b.right <= topBox.left || b.left >= topBox.right) continue
                if (first == null || gap < firstGap) {
                    first = j
                    firstGap = gap
                    ref = r
                }
                break
            }
        }
        if (first == null) return null
        val boxes = boxesIn(ref)
        val topBox = boxes[top]!!
        val firstBox = boxes[first]!!
        // 同一排挨着的几段；中线比上一行的中线低一截的才算这一排，不把上一行的另一段拉进来
        val row = mutableListOf(first)
        var left = firstBox.left
        var right = firstBox.right
        var grew = true
        while (grew) {
            grew = false
            for (k in boxes.indices) {
                if (k == top || k in row || k in used) continue
                val b = boxes[k] ?: continue
                if (b.centerY < firstBox.top || b.centerY > firstBox.bottom) continue
                if (b.centerY - topBox.centerY < 0.5f * topBox.height) continue
                if (b.left - right > SIDE_GAP * firstBox.height || left - b.right > SIDE_GAP * firstBox.height) continue
                row += k
                left = min(left, b.left)
                right = max(right, b.right)
                grew = true
            }
        }
        val slack = 0.5f * firstBox.height
        if (topBox.centerX < left - slack || topBox.centerX > right + slack) return null
        if (topBox.right - topBox.left > 1.5f * (right - left) + firstBox.height) return null
        return row.sortedBy { boxes[it]!!.left } to ref
    }

    private fun trimmed(text: String): IntRange? {
        val start = text.indexOfFirst { !it.isWhitespace() }
        return if (start < 0) null else start..text.indexOfLast { !it.isWhitespace() }
    }

    private fun compactLength(text: String) = text.count { !it.isWhitespace() }

    private companion object {
        /** 上一行最多几个字：「尾張小牧 500」是 7 个。 */
        const val MAX_TOP = 8

        /** 下一排每一段最多几个字。 */
        const val MAX_BOTTOM = 10

        /** 两行之间的空隙最多是字高的几倍。 */
        const val ROW_GAP = 0.6f

        /** 两行的框最多咬住矮的那个的几成。牌子歪 5° 时外接框咬住七成多。 */
        const val OVERLAP = 0.8f

        /** 同一排两段之间最宽隔几个字高。 */
        const val SIDE_GAP = 1.5f

        /** 只按这一页的方向量（见 refsOf）。 */
        val IN_PAGE: List<Int?> = listOf(null)
    }
}
