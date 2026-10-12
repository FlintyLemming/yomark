package moe.flinty.yomark.rules

import moe.flinty.yomark.rules.validator.Checksums
import java.text.Normalizer

/**
 * 证件底部的机读码（MRZ，ICAO 9303）：护照两行各 44 个字符（TD3），身份证、居留证三行各 30 个（TD1），
 * 部分证件、签证两行各 36 个（TD2）。姓名、证件号、生日、有效期全在里面，是一页上最该遮的一块。
 * 大陆的几种通行证排法各不相同：往来港澳、台湾通行证卡正面一行 30 个（CS、CD 打头），
 * 回乡证、台胞证背面三行的第一行（CR、CT 打头），这两种行里都没有成串的填充符，靠校验位认。
 *
 * 原先只认一字不差的 44 个字符，拍的护照上一行也没认出来：
 * - OCR 在等宽字里补空格：窄的「1」两边留白宽，第二行读成「…F301 01 49500101920<091 824」；
 * - 填充符「<<<<<<」数不准，多一个少一个就不是 44 了。
 * 所以去掉空格再看，长度放宽，靠两样东西认它，有一样就行：
 * - 填充符：成串的「<」，正常文字里没有；
 * - 校验位：证件号、生日、有效期三个对上两个。美国护照第二行只有一个「<」，就靠这个。
 * 读字段时按位置纠正认岔的字（读卡器都这么做）：国籍那三位只会是字母，「UT0」是「UTO」；
 * 生日、有效期、校验位只会是数字，「74O812」是「740812」。
 */
internal object Mrz {

    /** 一行里的一段机读码。[range] 在原串上；[chars] 去掉了空格，「«」这类认岔的填充符统一成「<」。 */
    class Line(val range: IntRange, val chars: String)

    fun findIn(text: String): List<Line> {
        val sb = StringBuilder(text.length)
        val origin = IntArray(text.length)
        text.forEachIndexed { i, c ->
            if (c == ' ') return@forEachIndexed
            origin[sb.length] = i
            sb.append(if (c in FILLER_LOOKALIKES) '<' else c)
        }
        return RUN.findAll(sb).filter { isMrz(it.value) }
            .map { Line(origin[it.range.first]..origin[it.range.last], it.value) }.toList()
    }

    private fun isMrz(s: String): Boolean =
        (s.length >= MIN_FILLED && "<<" in s && s.count { it == '<' } >= 3) ||
            (s.length >= MIN_CHECKED && (documentLine(s)?.trusted == true || idCardLine2(s)?.trusted == true)) ||
            permitLine(s)?.trusted == true

    /**
     * 机读码里读出来、校验位对得上的几样。生日是（两位年, 月, 日）。
     * [numberSuffix] 是回乡证、台胞证的换证（签发）次数：印在证件号旁边，OCR 常把两样读成一串「H1234567800」。
     */
    class Holder(
        val documentNumber: String?,
        val birth: Triple<Int, Int, Int>?,
        val names: Set<String>,
        val numberSuffix: String? = null,
    )

    /** 一页的机读码读成一个人。一行机读码都没有时给 null。 */
    fun read(lines: List<List<Line>>): Holder? {
        val all = lines.flatten().map { it.chars }
        if (all.isEmpty()) return null
        var number: String? = null
        var suffix: String? = null
        var birth: Triple<Int, Int, Int>? = null
        val names = LinkedHashSet<String>()
        all.forEach { s ->
            // 通行证那一行要先认：当护照第二行读，会把它的有效期读成生日
            permitLine(s)?.let { f ->
                if (f.trusted) {
                    if (number == null) number = f.number?.also { suffix = f.suffix }
                    if (birth == null) birth = f.birth
                }
                return@forEach
            }
            documentLine(s)?.takeIf { it.trusted }?.let { f ->
                if (number == null) number = f.number
                if (birth == null) birth = f.birth
            }
            idCardLine1(s)?.let { if (number == null) number = it }
            idCardLine2(s)?.takeIf { it.trusted }?.let { if (birth == null) birth = it.birth }
            names += namesIn(s)
        }
        return Holder(number, birth, names, suffix)
    }

    /** 读出来的字段：校验位对得上的才给值；[trusted] 是这一行的形状和校验位够不够认定它是机读码。 */
    private class Fields(
        val number: String?,
        val birth: Triple<Int, Int, Int>?,
        val trusted: Boolean,
        val suffix: String? = null,
    )

    private fun permitLine(s: String): Fields? = exitPermitLine(s) ?: travelPermitLine(s)

    /**
     * 往来港澳通行证（CS）、往来台湾通行证（CD）卡正面底下那一行，30 个字符：
     * 证件号 2..10 校验位 11，「<」，有效期 13..18 校验位 19，「<」，生日 21..26 校验位 27，「<」，前面三段的总校验位 29。
     * 官方样张「CSC000000004<2404200<8108038<6」四位校验位都对得上。有效期在生日前面，和护照反着。
     * 一行里没有成串的填充符，原先认不出来。
     */
    private fun exitPermitLine(raw: String): Fields? {
        if (raw.length < 30 || raw[0] != 'C' || raw[1] !in "SD") return null
        val s = corrected(raw.take(30), letters = 2..2, digits = listOf(4..11, 13..19, 21..27, 29..29), fillers = listOf(12, 20, 28))
        if (s[12] != '<' || s[20] != '<' || s[28] != '<') return null
        if (!s.substring(4, 12).isDigits() || !s.substring(13, 20).isDigits() || !s.substring(21, 28).isDigits() || !s[29].isDigit()) {
            return null
        }
        val numberOk = Checksums.mrzCheck(s.substring(2, 11)) == s[11]
        val expiryOk = Checksums.mrzCheck(s.substring(13, 19)) == s[19]
        val birthOk = Checksums.mrzCheck(s.substring(21, 27)) == s[27]
        val compositeOk = Checksums.mrzCheck(s.substring(2, 12) + s.substring(13, 20) + s.substring(21, 28)) == s[29]
        return Fields(
            number = s.substring(2, 11).takeIf { numberOk },
            birth = date(s.substring(21, 27)).takeIf { birthOk },
            trusted = listOf(numberOk, expiryOk, birthOk, compositeOk).count { it } >= 2,
        )
    }

    /**
     * 回乡证（CR）、台胞证（CT）背面三行机读码的第一行，30 个字符，整行没有成串的填充符：
     * 证件号 2..10 校验位 11（台胞证号八位数字，第 10 位补「<」），换证或签发次数 12..13 校验位 14，
     * 有效期 15..20 校验位 21，性别 22，生日 23..28 校验位 29。
     */
    private fun travelPermitLine(raw: String): Fields? {
        if (raw.length < 30 || raw[0] != 'C' || raw[1] !in "RT") return null
        val hk = raw[1] == 'R'
        val s = corrected(
            raw.take(30),
            letters = if (hk) 2..2 else IntRange.EMPTY,
            digits = listOf(if (hk) 3..11 else 2..9, 11..21, 23..29),
            fillers = if (hk) emptyList() else listOf(10),
        )
        if (hk && s[2] !in "HM") return null
        if (!hk && s[10] != '<') return null
        if (!s.substring(if (hk) 3 else 2, if (hk) 11 else 10).isDigits() || !s.substring(11, 22).isDigits() || !s.substring(23, 30).isDigits()) {
            return null
        }
        if (s[22] !in "MF<") return null
        val numberOk = Checksums.mrzCheck(s.substring(2, 11)) == s[11]
        val countOk = Checksums.mrzCheck(s.substring(12, 14)) == s[14]
        val expiryOk = Checksums.mrzCheck(s.substring(15, 21)) == s[21]
        val birthOk = Checksums.mrzCheck(s.substring(23, 29)) == s[29]
        return Fields(
            number = s.substring(2, 11).trimEnd('<').takeIf { numberOk },
            birth = date(s.substring(23, 29)).takeIf { birthOk },
            trusted = listOf(numberOk, countOk, expiryOk, birthOk).count { it } >= 2,
            suffix = s.substring(12, 14),
        )
    }

    /**
     * 护照（TD3）、TD2 的第二行：证件号 0..8 校验位 9，国籍 10..12，生日 13..18 校验位 19，性别 20，有效期 21..26 校验位 27。
     * 两种证件这前 28 位排法一样。
     */
    private fun documentLine(raw: String): Fields? {
        if (raw.length < 28) return null
        val s = corrected(raw, letters = 10..12, digits = listOf(9..9, 13..19, 21..27))
        if (!s.substring(13, 19).isDigits() || !s.substring(21, 27).isDigits()) return null
        if (s[20] !in "MFX<" || !s.substring(10, 13).all { it in 'A'..'Z' || it == '<' }) return null
        val numberOk = Checksums.mrzCheck(s.substring(0, 9)) == s[9]
        val birthOk = Checksums.mrzCheck(s.substring(13, 19)) == s[19]
        val expiryOk = Checksums.mrzCheck(s.substring(21, 27)) == s[27]
        return Fields(
            number = s.substring(0, 9).trimEnd('<').takeIf { numberOk && it.length >= 5 },
            birth = date(s.substring(13, 19)).takeIf { birthOk },
            trusted = listOf(numberOk, birthOk, expiryOk).count { it } >= 2,
        )
    }

    /** 身份证（TD1）第一行：证件种类 0..1，签发国 2..4，证件号 5..13 校验位 14。 */
    private fun idCardLine1(raw: String): String? {
        if (raw.length < 15) return null
        val s = corrected(raw, letters = 2..4, digits = listOf(14..14))
        if (s[0] !in "ACI" || s.substring(2, 5).any { it !in 'A'..'Z' && it != '<' }) return null
        if (Checksums.mrzCheck(s.substring(5, 14)) != s[14]) return null
        return s.substring(5, 14).trimEnd('<').takeIf { it.length >= 5 && it.any(Char::isDigit) }
    }

    /** 身份证（TD1）第二行：生日 0..5 校验位 6，性别 7，有效期 8..13 校验位 14，国籍 15..17。 */
    private fun idCardLine2(raw: String): Fields? {
        if (raw.length < 18) return null
        val s = corrected(raw, letters = 15..17, digits = listOf(0..6, 8..14))
        if (!s.substring(0, 6).isDigits() || !s.substring(8, 14).isDigits() || s[7] !in "MFX<") return null
        val birthOk = Checksums.mrzCheck(s.substring(0, 6)) == s[6]
        val expiryOk = Checksums.mrzCheck(s.substring(8, 14)) == s[14]
        return Fields(null, date(s.substring(0, 6)).takeIf { birthOk }, trusted = birthOk && expiryOk)
    }

    /**
     * 姓名行：有「<<」，不是上面那几种带数字的行。护照第一行前五位是证件种类和签发国（「P<USA」），身份证第三行整行是姓名。
     * 两种分不清时看长度：身份证一行 30 个字符，护照 44、TD2 36；填充符数不准，留几个的余量。
     * 名字里只有字母，认岔成数字的（「MARL0WE」）换回来；换不回来的数字说明这不是姓名行。
     */
    private fun namesIn(raw: String): List<String> {
        if ("<<" !in raw || permitLine(raw) != null || documentLine(raw)?.trusted == true || idCardLine1(raw) != null ||
            idCardLine2(raw)?.trusted == true
        ) {
            return emptyList()
        }
        val s = raw.map { asLetter(it) }.joinToString("")
        if (s.any(Char::isDigit)) return emptyList()
        val names = if (s.length >= 34 || s.getOrNull(1) == '<') s.drop(5) else s
        return names.split('<').filter { it.length >= 2 }
    }

    /** 按位置纠正：[letters] 里的位置只会是字母，[digits] 里的只会是数字，[fillers] 里的只会是「<」（OCR 常读成 K）。 */
    private fun corrected(s: String, letters: IntRange, digits: List<IntRange>, fillers: List<Int> = emptyList()): String =
        String(CharArray(s.length) { i ->
            when {
                i in fillers -> if (s[i] == 'K') '<' else s[i]
                i in letters -> asLetter(s[i])
                digits.any { i in it } -> asDigit(s[i])
                else -> s[i]
            }
        })

    private fun asLetter(c: Char) = when (c) {
        '0' -> 'O'; '1' -> 'I'; '2' -> 'Z'; '5' -> 'S'; '6' -> 'G'; '8' -> 'B'; else -> c
    }

    private fun asDigit(c: Char) = when (c) {
        'O', 'Q', 'D' -> '0'; 'I', 'L' -> '1'; 'Z' -> '2'; 'S' -> '5'; 'G' -> '6'; 'B' -> '8'; else -> c
    }

    private fun date(yymmdd: String) = Triple(yymmdd.take(2).toInt(), yymmdd.substring(2, 4).toInt(), yymmdd.substring(4, 6).toInt())
        .takeIf { (_, m, d) -> m in 1..12 && d in 1..31 }

    private fun String.isDigits() = all { it in '0'..'9' }

    /** 机读码的字符集里一段够长的。 */
    private val RUN = Regex("""[A-Z0-9<]{$MIN_FILLED,}""")

    /** 有填充符时最短多长：TD1 一行 30，OCR 可能丢几个填充符。 */
    private const val MIN_FILLED = 25

    /** 只靠校验位认时最短多长：要放得下证件号到有效期的 28 位。 */
    private const val MIN_CHECKED = 28

    /** OCR 读机读码时常把「<」读成的字。 */
    private const val FILLER_LOOKALIKES = "«‹〈＜"
}

/**
 * 机读码那两三行本身（PASSPORT 规则的第一种认法）。
 */
class MrzLines(private val confidence: Float) : Finder {
    override fun findIn(text: String): List<RuleMatch> = Mrz.findIn(text).map { RuleMatch(it.range, confidence) }
}

/**
 * 拿机读码去对照页上别处：资料页上印着的证件号、生日、姓名，幽灵像旁边那行小字「05 02 65」，激光穿孔的号码。
 *
 * 字段名是哪国文字、OCR 有没有把字段名读对都不要紧：机读码的校验位对得上，页上一样的那一串就是同一样东西。
 * 机读码那几行自己不在这里认（那是 [MrzLines] 的事）。
 */
class MrzEcho(private val what: What, private val confidence: Float) : PageFinder {

    enum class What { DOCUMENT_NUMBER, BIRTH_DATE, NAME }

    override fun findOnPage(page: Page): List<PageMatch> {
        val mrz = page.texts.map { Mrz.findIn(it) }
        val holder = Mrz.read(mrz) ?: return emptyList()
        val out = ArrayList<PageMatch>()
        page.texts.forEachIndexed { i, text ->
            if (mrz[i].isNotEmpty()) return@forEachIndexed
            val found = when (what) {
                What.DOCUMENT_NUMBER -> holder.documentNumber?.let { numberIn(text, it, holder.numberSuffix) } ?: emptyList()
                What.BIRTH_DATE -> holder.birth?.let { birthIn(text, it) } ?: emptyList()
                What.NAME -> listOfNotNull(namesIn(text, holder.names))
            }
            found.forEach { out += PageMatch(i, RuleMatch(it, confidence)) }
        }
        return out
    }

    /**
     * 证件号：去掉空格比（OCR 可能在号码中间补空格），前后不能紧挨字母数字。
     * 回乡证、台胞证的号码后面可能粘着换证次数（[suffix]），一起算。
     */
    private fun numberIn(text: String, number: String, suffix: String?): List<IntRange> {
        val origin = text.indices.filter { text[it] != ' ' }
        val compact = origin.map { text[it] }.joinToString("")
        val tail = suffix?.let { "(?:${Regex.escape(it)})?" } ?: ""
        return Regex("""(?<![A-Z0-9])${Regex.escape(number)}$tail(?![A-Z0-9])""").findAll(compact)
            .map { origin[it.range.first]..origin[it.range.last] }.toList()
    }

    private fun birthIn(text: String, birth: Triple<Int, Int, Int>): List<IntRange> =
        Dates.findAllLenient(text).filter { r ->
            Dates.readings(text.substring(r)).any { (y, m, d) -> y % 100 == birth.first && m == birth.second && d == birth.third }
        }

    /**
     * 姓名：整行只有机读码里的名字（「TRAVELER」「HAPPY」「ZHANG SAN」，可夹着汉字「张三」），整行算。
     * 只对得上一部分的不算：姓「STATES」不该让「UNITED STATES OF AMERICA」变成人名。
     */
    private fun namesIn(text: String, names: Set<String>): IntRange? {
        if (names.isEmpty()) return null
        val words = LATIN_WORD.findAll(text).toList()
        if (words.isEmpty() || words.any { fold(it.value) !in names }) return null
        if (text.any { !it.isLetter() && it !in NAME_PUNCTUATION }) return null
        val first = text.indexOfFirst { !it.isWhitespace() }
        val last = text.indexOfLast { !it.isWhitespace() }
        return first..last
    }

    /** 机读码只有 A–Z：「JOSÉ」在机读码里是「JOSE」。 */
    private fun fold(word: String): String =
        Normalizer.normalize(word, Normalizer.Form.NFD).filter { it in 'A'..'Z' || it in 'a'..'z' }.uppercase()

    private companion object {
        val LATIN_WORD = Regex("""[A-Za-zÀ-ÿ]+""")
        const val NAME_PUNCTUATION = " ,/-'’"
    }
}
