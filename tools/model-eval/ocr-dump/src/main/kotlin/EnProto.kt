package moe.flinty.yomark

import moe.flinty.yomark.rules.Finder
import moe.flinty.yomark.rules.PhoneRule
import moe.flinty.yomark.rules.RuleMatch
import java.io.File

/**
 * 英文人名、地址的规则原型（只在评测台里，不进 app）。与出厂规则同一个思路：形状 + 字段名 + 旁证，不用模型。
 *
 * 逐行的认法（[lineFinders]）和看整页的认法（[pageFinds]：字段名单独一行、值在下面几行）分开写，
 * 后者对应 app 里还没有的一种机制：RuleClassifier 现在只有「要旁证的猜测」会看邻行。
 */
class EnProto(namesDir: File) {

    private val first = File(namesDir, "first.txt").readLines().filter { it.isNotBlank() }.toSet()
    private val last = File(namesDir, "last.txt").readLines().filter { it.isNotBlank() }.toSet()

    // ---------- 地址 ----------

    private val suffix = "Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Drive|Dr|Lane|Ln|Court|Ct|Place|Pl|Way|" +
        "Terrace|Ter|Parkway|Pkwy|Highway|Hwy|Circle|Cir|Square|Sq|Trail|Trl|Close|Crescent|Cres|Grove|Gardens|" +
        "Row|Mews|Walk|Alley|Plaza|Loop|Hill|Park"
    private val unit = """(?:Apt|Apartment|Unit|Suite|Ste|Flat|Floor|Fl|Room|Rm|Building|Bldg|Block|Tower|#)\.?\s*#?[A-Za-z]?\d+[A-Za-z]?"""

    /** 门牌 + 街名 + 街道后缀，前后可带房号、楼栋：「Flat 4, 27 Kingsley Road」「4567 OAK AVE STE 210」「No. 18 Zhongshan Road」。 */
    private val street = Regex(
        """(?i)(?:\b$unit\s*,?\s*)*\b(?:No\.?\s*)?\d{1,6}[A-Za-z]?\s+(?:[NSEW]\.?\s+)?""" +
            """(?:[A-Za-z0][A-Za-z0'.-]*\s+){0,3}(?:$suffix)\b\.?(?:\s*,?\s*$unit\b)*"""
    )
    private val poBox = Regex("""(?i)\bP\.?\s?O\.?\s?Box\s+\d+""")
    private val states = "AL|AK|AZ|AR|CA|CO|CT|DE|DC|FL|GA|HI|ID|IL|IN|IA|KS|KY|LA|ME|MD|MA|MI|MN|MS|MO|MT|NE|NV|NH|NJ|NM|" +
        "NY|NC|ND|OH|OK|OR|PA|RI|SC|SD|TN|TX|UT|VT|VA|WA|WV|WI|WY|PR"
    private val cityWord = """[A-Z][A-Za-z.'-]*"""

    /** 城市、州、邮编：「Columbus, OH 43215」「LOS ANGELES CA 90012-3456」。 */
    private val usCity = Regex("""\b$cityWord(?:\s+$cityWord){0,3},?\s+(?:$states)\s+\d{5}(?:-\d{4})?\b""")

    /** 英国：城市 + 邮编「Manchester M14 6PL」。 */
    private val ukCity = Regex("""\b$cityWord(?:\s+$cityWord){0,2},?\s+[A-Z]{1,2}\d[A-Z\d]?\s\d[A-Z]{2}\b""")

    /** 拼音地址的区县以上：「Xuanwu District, Nanjing, Jiangsu 210018」。 */
    private val cnRegion = Regex(
        """\b[A-Z][a-z]+\s(?:District|County|New Area)\b(?:,\s*[A-Z][a-z]+(?:\s(?:City|Province))?){0,3}(?:,?\s*\d{6})?"""
    )

    private val addressFinders = listOf(street, poBox, usCity, ukCity, cnRegion)

    /** 地址：同一行上相邻的几段（中间只隔逗号、空格）连成一段。 */
    val address = Finder { text ->
        val spans = addressFinders.flatMap { r -> r.findAll(text).map { it.range } }.sortedBy { it.first }
        val merged = ArrayList<IntRange>()
        spans.forEach { s ->
            val prev = merged.lastOrNull()
            if (prev != null && s.first <= prev.last + 3 && text.substring(prev.last + 1, maxOf(prev.last + 1, s.first)).all { it in ", " }) {
                merged[merged.lastIndex] = prev.first..maxOf(prev.last, s.last)
            } else merged += s
        }
        merged.map { RuleMatch(it, 0.7f) }
    }

    // ---------- 人名 ----------

    private val pinyinSurnames = setOf(
        "Wang", "Li", "Zhang", "Liu", "Chen", "Yang", "Huang", "Zhao", "Wu", "Zhou", "Xu", "Sun", "Ma", "Zhu", "Hu",
        "Guo", "He", "Gao", "Lin", "Luo", "Zheng", "Liang", "Xie", "Song", "Tang", "Han", "Feng", "Deng", "Cao", "Peng",
        "Zeng", "Xiao", "Tian", "Dong", "Yuan", "Pan", "Yu", "Jiang", "Cai", "Du", "Ye", "Cheng", "Su", "Wei", "Lu",
        "Ding", "Ren", "Shen", "Yao", "Cui", "Zhong", "Tan", "Fan", "Jin", "Shi", "Liao", "Jia", "Xia", "Fu", "Fang",
        "Bai", "Zou", "Meng", "Xiong", "Qin", "Qiu", "Yin", "Xue", "Yan", "Duan", "Lei", "Hou", "Long", "Tao", "Gu",
        "Mao", "Hao", "Gong", "Shao", "Wan", "Qian", "Dai", "Mo", "Kong", "Xiang", "Chang", "Kang", "Niu", "Ou", "Mu",
    )

    /** 汉语拼音音节（不带声调），给名用：「Wei」「Xiaoming」「Zhiyuan」。 */
    private val pinyinSyllable =
        "(?:zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])?(?:iang|iong|uang|ang|eng|ing|ong|uai|uan|ian|iao|ai|ei|ao|ou|an|en|in|un|" +
            "ua|uo|ia|ie|iu|ui|ue|er|a|o|e|i|u|v)"
    private val pinyinGiven = Regex("(?i)$pinyinSyllable{1,2}")

    private fun isPinyinName(a: String, b: String) =
        (a in pinyinSurnames && pinyinGiven.matches(b)) || (b in pinyinSurnames && pinyinGiven.matches(a))

    private fun cap(w: String) = w.lowercase().replaceFirstChar { it.uppercase() }

    /** 首字母大写（或全大写）的词，可带连字符：「Walsh」「GARCIA」「Smith-Jones」。 */
    private val capWord = Regex("""\b(?:[A-Z][a-z]+(?:-[A-Z][a-z]+)?|[A-Z]{2,})\b""")
    private val initial = Regex("""^\s+[A-Z]\.\s+$""")

    /**
     * 相邻两个大写词（中间可夹一个缩写「A.」），前一个是常见的名、后一个是常见的姓；或者是拼音名。
     * 逐个词对试，不用一个正则扫：「Call Michael Chen」里 Call+Michael 不成，还要试 Michael+Chen。
     */
    private fun dictionaryNames(text: String): List<RuleMatch> {
        val words = capWord.findAll(text).toList()
        val out = ArrayList<RuleMatch>()
        for (i in 0 until words.size - 1) {
            val a = words[i]
            val b = words[i + 1]
            val between = text.substring(a.range.last + 1, b.range.first)
            if (between.isNotBlank() && !initial.matches(between)) continue
            if (between.isEmpty()) continue
            val fa = cap(a.value)
            val lb = cap(b.value.substringBefore('-'))
            val ok = (fa in first && lb in last && fa !in NOT_FIRST && lb !in NOT_LAST) || isPinyinName(fa, lb)
            if (ok && out.none { it.range.first <= b.range.last && a.range.first <= it.range.last }) {
                out += RuleMatch(a.range.first..b.range.last, 0.6f, needsAnchor = true)
            }
        }
        return out
    }

    /** 聊天页、联系人页的顶栏：「< Emily Carter」。名要过名单。 */
    private val topBar = Regex("""^\s*[<‹〈]\s*(.+?)\s*$""")

    fun titleName(text: String): List<RuleMatch> {
        val m = topBar.find(text) ?: return emptyList()
        val inner = m.groups[1]!!
        return dictionaryNames(inner.value)
            .filter { it.range.first == 0 && it.range.last == inner.value.length - 1 }
            .map { RuleMatch(inner.range, 0.7f) }
    }

    private val honorific = Regex("""\b(?:Mr|Mrs|Ms|Miss|Mx|Dr|Prof)\.?\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)?""")

    /** 「Sarah Johnson <sarah.johnson@…>」：尖括号前面的就是发件人。 */
    private val beforeEmail = Regex("""\b[A-Z][a-z]+(?:\s+[A-Z]\.?)?(?:\s+[A-Z][a-z'-]+){1,2}(?=\s*<[^>@\s]+@)""")

    /** 称呼与落款：「Hi David,」「Thanks for riding, Kevin」「Dear Ms. Smith」。名要在常见名里。 */
    private val greeting = Regex("""(?i:^\s*(?:hi|hello|hey|dear|thanks|thank you|thanks for \w+)),?\s+([A-Z][a-z]+)\b""")

    private val nameLabel = Regex(
        """(?i)\b(?:full name|first name|last name|guest name|account name|account holder|card ?holder|name on card|""" +
            """name|recipient|guest|passenger|traveler|traveller|customer|sender|attn|attention|payee|beneficiary|""" +
            """patient|ship to|bill to|deliver to|delivering to)\s*[:：]?\s+"""
    )
    private val nameValue = Regex("""[A-Z][A-Za-z'.-]*(?:\s+[A-Z][A-Za-z'.-]*){0,3}""")

    val nameLine = Finder { text ->
        val out = ArrayList<RuleMatch>()
        fun add(r: IntRange, c: Float, anchor: Boolean = false) {
            if (out.none { it.range.first <= r.last && r.first <= it.range.last }) out += RuleMatch(r, c, anchor)
        }
        nameLabel.findAll(text).forEach { l ->
            nameValue.matchAt(text, l.range.last + 1)?.let { v -> if (looksLikeNameValue(v.value)) add(v.range, 0.8f) }
        }
        beforeEmail.findAll(text).forEach { add(it.range, 0.8f) }
        honorific.findAll(text).forEach { add(it.range, 0.8f) }
        greeting.find(text)?.let { m -> if (m.groupValues[1] in first) add(m.groups[1]!!.range, 0.7f) }
        roleName.findAll(text).forEach { m -> if (m.groupValues[1] in first) add(m.groups[1]!!.range.first..m.range.last, 0.7f) }
        senderLabel.find(text)?.let { m -> if (isNameLike(m.groupValues[1])) add(m.groups[1]!!.range, 0.7f) }
        systemMessage.find(text)?.let { m -> if (isNameLike(m.groupValues[1])) add(m.groups[1]!!.range, 0.7f) }
        dictionaryNames(text).forEach { add(it.range, it.confidence, anchor = true) }
        out
    }

    /**
     * 原型 v2（在第二轮基准出来之前定稿）：几种界面上常见、不靠名单旁证的人名写法。
     * 「Your driver Marcus」「Your Dasher, Marcus,」「rode with Carlos M.」：身份词后面紧跟一个常见名（可带缩写）。
     */
    private val roleName = Regex(
        """(?i:\b(?:driver|dasher|courier|shopper|rider|host|guide|agent|representative|rep|technician|tech|instructor|stylist|""" +
            """nurse|teacher|coach|landlord|tenant|seller|buyer|carrier|partner|rode with|delivered by|handled by|assigned to))""" +
            """\s*,?\s+([A-Z][a-z]+)(?:\s+[A-Z]\.)?"""
    )

    /** WhatsApp 群里没存联系人的发送者「~ Rob Fletcher」。 */
    private val senderLabel = Regex("""^\s*~\s*([A-Z][A-Za-z'.-]+(?:\s+[A-Z][A-Za-z'.-]+){0,2})\s*$""")

    /** 聊天、转账的系统消息：「Sophie Lambert joined」「Jordan paid Alex」「Priya added you」。 */
    private val systemMessage = Regex(
        """^\s*([A-Z][a-z]+(?:\s+[A-Z][a-z'-]+){0,2})\s+(?:joined|left|added|removed|changed the|created|paid|charged|sent you|sent|requested|""" +
            """shared|reacted|liked|mentioned you|invited you|is typing|accepted|declined)\b"""
    )

    /** 一两个大写词，第一个是常见的名（第二个若有，要像姓：在姓表里或者首字母大写的一个词）。 */
    private fun isNameLike(s: String): Boolean {
        val w = s.split(Regex("\\s+"))
        return w.isNotEmpty() && cap(w[0]) in first && cap(w[0]) !in NOT_FIRST
    }

    /**
     * 弱一档（只圈不打码）：一整行只有一个名单人名，比如联系人页、个人主页顶上的大字号名字、收件箱里的发件人。
     * 品牌也常是人名（Ralph Lauren、Martha Stewart），所以不打码。
     */
    fun standaloneName(text: String): List<RuleMatch> {
        val t = text.trim()
        val lead = text.indexOf(t)
        return dictionaryNames(t).filter { it.range.first == 0 && it.range.last == t.length - 1 }
            .map { RuleMatch((it.range.first + lead)..(it.range.last + lead), 0.5f) }
    }

    private fun looksLikeNameValue(v: String): Boolean {
        val words = v.split(Regex("\\s+"))
        return words.size in 1..4 && words.none { cap(it.trim('.', ',')) in NOT_NAME_WORDS }
    }

    // ---------- 电话：中文系统上按 US / GB 再试一遍 ----------

    private val usPhone = PhoneRule("US")
    private val gbPhone = PhoneRule("GB")

    /** 拉丁字母为主的行（没有汉字），按 US、GB 各认一遍。 */
    val foreignPhone = Finder { text ->
        if (text.any { it in '一'..'鿿' }) emptyList()
        else (usPhone.findIn(text) + gbPhone.findIn(text)).distinctBy { it.range }
    }

    // ---------- 看整页：字段名单独一行，值在下面 ----------

    private val addressHeader = Regex(
        """(?i)^\W*(?:shipping|billing|delivery|mailing|home|work|pickup|return|business)?\s*(?:address|ship to|deliver to|delivering to|pickup|dropoff|drop-off)\b[^A-Za-z0-9]*(?:\d{1,2}:\d{2}\s*[AP]M)?\s*$"""
    )
    private val nameHeader = Regex(
        """(?i)^\W*(?:full name|name|first name|last name|guest name|recipient|guest|passenger|cardholder|account holder|contact|customer)\s*:?\s*$"""
    )

    class PageLine(val text: String, val l: Float, val t: Float, val r: Float, val b: Float) {
        val h get() = b - t
    }

    /** 字段名单独占一行时，紧挨着的下面几行（左对齐、行距不超过 1.5 个字高）。地址最多 4 行，人名 1 行。 */
    fun pageFinds(lines: List<PageLine>): List<Pair<Int, IntRange>> {
        val out = ArrayList<Pair<Int, IntRange>>()
        lines.forEachIndexed { i, line ->
            val take = when {
                addressHeader.matches(line.text) -> 4
                nameHeader.matches(line.text) -> 1
                else -> 0
            }
            var prev = line
            var j = i + 1
            var n = 0
            var firstGap = -1f
            while (n < take && j < lines.size) {
                val next = lines[j]
                val h = maxOf(prev.h, next.h)
                val gap = next.t - prev.b
                if (gap > 1.5f * h || gap < -0.5f * h || kotlin.math.abs(next.l - line.l) > 2f * h) break
                // 一块地址的行距是均匀的；比前面的行距大出一截，就是下一个字段了
                if (firstGap >= 0f && gap > maxOf(firstGap * 1.5f, 0.3f * h)) break
                if (firstGap < 0f) firstGap = maxOf(gap, 0f)
                if (addressHeader.matches(next.text) || nameHeader.matches(next.text)) break
                // 电话、邮箱行交给各自的规则；国家名一行也算地址的一部分
                out += j to next.text.indices
                prev = next; j++; n++
            }
        }
        return out
    }

    private companion object {
        /** 名单里有、但在截图上多半是普通词的名与姓。 */
        val NOT_FIRST = setOf("Will", "May", "Mark", "Grant", "Bill", "Art", "Sale", "Order", "Power", "Long", "Young", "King", "Page")
        val NOT_LAST = setOf("Street", "Road", "Lane", "Court", "Way", "Park", "Hill", "Grove", "Bank", "Edition", "Details", "Order", "Store")
        val NOT_NAME_WORDS = setOf("Order", "Details", "Address", "Phone", "Email", "Number", "Date", "Total", "Price", "Room")
    }
}
