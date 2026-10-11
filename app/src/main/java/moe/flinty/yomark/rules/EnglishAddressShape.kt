package moe.flinty.yomark.rules

/**
 * 没有字段名的英文地址：靠地址自己的形状认，与 [AddressShape] 认中文门牌一个路子。
 *
 * 认这几种（同一行上相邻的几段，中间只隔逗号、空格的，连成一段）：
 * - 门牌 + 街名 + 街道后缀，前后可带房号：「2847 Maple Grove Dr, Apt 12C」「Flat 4, 27 Kingsley Road」
 *   「4567 OAK AVE STE 210」「No. 18 Zhongshan Road」。后缀和房号用词取 USPS Pub 28 的全部写法；
 *   街名的词必须大写开头：后缀里有 Walk、Park、Way 这类常用词，「5 min walk」不能算地址；
 * - 美国的城市、州、邮编：「Columbus, OH 43215」「LOS ANGELES CA 90012-3456」，州可以写全名；
 * - 英国、加拿大、澳大利亚、爱尔兰、新加坡的邮编，英国、加拿大的可以单独成行（「LS6 3DQ」「M5V 3L9」）；
 * - 拼音地址的区县以上：「Xuanwu District, Nanjing, Jiangsu 210018」；PO Box。
 *
 * 认不出没有门牌号的街名、单独一行的城市名（「Manchester」），这些交给字段名（「Address」「Ship to」）。
 */
class EnglishAddressShape(private val confidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        val spans = patterns.flatMap { r -> r.findAll(text).map { it.range } }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        spans.forEach { s ->
            val prev = merged.lastOrNull()
            if (prev != null && s.first <= prev.last + 3 &&
                text.substring(prev.last + 1, maxOf(prev.last + 1, s.first)).all { it == ',' || it == ' ' }
            ) {
                merged[merged.lastIndex] = prev.first..maxOf(prev.last, s.last)
            } else merged += s
        }
        return merged.map { RuleMatch(it, confidence) }
    }

    private companion object {
        fun cap(w: String) = w.lowercase().replaceFirstChar { it.uppercase() }

        val suffix by lazy {
            EnglishLists.streetSuffixes.flatMap { listOf(it, cap(it)) }.distinct()
                .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        }
        val unit by lazy {
            val words = EnglishLists.unitWords.flatMap { listOf(it, cap(it)) } + listOf("Apt", "Unit", "Suite", "Flat", "Room", "#")
            """(?:${words.distinct().sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }})\.?\s*#?[A-Za-z]?\d+[A-Za-z]?"""
        }

        const val STATES = "AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|" +
            "NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|PR|AS|GU|MP|VI|AA|AE|AP"
        const val STATE_NAMES = "Alabama|Alaska|Arizona|Arkansas|California|Colorado|Connecticut|Delaware|Florida|Georgia|Hawaii|" +
            "Idaho|Illinois|Indiana|Iowa|Kansas|Kentucky|Louisiana|Maine|Maryland|Massachusetts|Michigan|Minnesota|Mississippi|" +
            "Missouri|Montana|Nebraska|Nevada|New Hampshire|New Jersey|New Mexico|New York|North Carolina|North Dakota|Ohio|" +
            "Oklahoma|Oregon|Pennsylvania|Rhode Island|South Carolina|South Dakota|Tennessee|Texas|Utah|Vermont|Virginia|" +
            "Washington|West Virginia|Wisconsin|Wyoming|District of Columbia|Puerto Rico"
        const val CITY_WORD = """[A-Z][A-Za-z.'-]*"""

        val patterns by lazy {
            listOf(
                // 门牌（可带「4/18」这种单元/门牌）+ 方向词 + 至多四个大写开头的词 + 后缀，前后可带房号
                Regex(
                    """(?:\b$unit\s*,?\s*)*\b(?:No\.?\s*)?\d{1,6}[A-Za-z]?(?:[/-]\d{1,6}[A-Za-z]?)?\s+""" +
                        """(?:(?:N|S|E|W|NE|NW|SE|SW|North|South|East|West)\.?\s+)?(?:[A-Z0-9][A-Za-z0-9'.-]*\s+){0,3}""" +
                        """(?:$suffix)\b\.?(?:\s*,?\s*$unit\b)*"""
                ),
                Regex("""(?i)\bP\.?\s?O\.?\s?Box\s+\d+"""),
                Regex("""\b$CITY_WORD(?:\s+$CITY_WORD){0,3},?\s+(?:$STATES|$STATE_NAMES)\s+\d{5}(?:-\d{4})?\b"""),
                // 英国：城市 + 邮编，或者邮编单独出现（Google 地址元数据里那条更严的式子）
                Regex("""\b$CITY_WORD(?:\s+$CITY_WORD){0,2},?\s+[A-Z]{1,2}\d[A-Z\d]?\s\d[A-Z]{2}\b"""),
                Regex("""\b(?:GIR 0AA|[A-PR-UWYZ](?:\d{1,2}|[A-HK-Y]\d{1,2}|\d[A-HJKPS-UW]|[A-HK-Y]\d[ABEHMNPRV-Y])\s\d[ABD-HJLNP-UW-Z]{2})\b"""),
                // 加拿大：可带省份缩写
                Regex("""\b(?:(?:AB|BC|MB|NB|NL|NS|NT|NU|ON|PE|QC|SK|YT)\s+)?[ABCEGHJ-NPRSTVXY]\d[ABCEGHJ-NPRSTV-Z]\s?\d[ABCEGHJ-NPRSTV-Z]\d\b"""),
                // 澳大利亚：郊区 + 州 + 四位邮编
                Regex("""\b[A-Z][A-Za-z'-]*(?:\s+[A-Z][A-Za-z'-]*){0,3}\s+(?:NSW|VIC|QLD|SA|WA|TAS|NT|ACT)\s+\d{4}\b"""),
                // 爱尔兰 Eircode、新加坡
                Regex("""\b[AC-FHKNPRTV-Y]\d{2}\s?[AC-FHKNPRTV-Y0-9]{4}\b"""),
                Regex("""\bSingapore\s+\d{6}\b"""),
                // 拼音地址的区县以上
                Regex(
                    """\b[A-Z][a-z]+\s(?:District|County|New Area)\b(?:,\s*[A-Z][a-z]+(?:\s(?:City|Province))?){0,3}(?:,?\s*\d{6})?"""
                ),
            )
        }
    }
}
