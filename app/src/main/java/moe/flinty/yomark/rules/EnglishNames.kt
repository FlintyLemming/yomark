package moe.flinty.yomark.rules

/**
 * 英文界面上自己就说明「这是一个人」的写法：字段名、称呼、邮件头、聊天里的系统消息……
 * 与 [LabeledField] 一样不要旁证；名字本身要像名字（常见名单里有、不是品牌、不是「Order Details」这种界面词）。
 *
 * - 字段名后面的值：「Ship to: Jennifer Walsh」「Cardholder JOHN A SMITH」，不分大小写；
 * - 尖括号邮箱前面的：「Sarah Johnson <sarah.j@…>」；
 * - 称谓：「Mr. Patel」「Dr Emily Carter」；
 * - 称呼与落款：「Hi David,」「Thanks for riding, Kevin」，名要在常见名里；
 * - 身份词后面的名：「Your driver Marcus」「rode with Carlos M.」；
 * - 机票上的「姓/名 称谓」：「ZHANG/WEI MR」，不带称谓的要姓、名都在名单里（「SFO/LAX」不算）；
 * - 邮件头「To: Jane Doe」、群聊里没存联系人的「~ Rob Fletcher」、系统消息「Sophie Lambert joined」；
 * - 聊天页顶栏「< Emily Carter」：整段是一个名单人名。
 *
 * 字段名单独占一行、值在下一行的（表单、收货卡片）认不出，交给 [EnglishNameGuess] 加旁证。
 */
class EnglishNameCues(private val confidence: Float, private val weakConfidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        if (text.none { it in 'A'..'Z' }) return emptyList()
        val out = ArrayList<RuleMatch>()
        fun add(r: IntRange, c: Float) {
            if (out.none { it.range.first <= r.last && r.first <= it.range.last }) out += RuleMatch(r, c)
        }
        LABEL.findAll(text).forEach { l ->
            val v = VALUE.matchAt(text, l.range.last + 1) ?: return@forEach
            // 没有冒号的「Customer Reviews」「Guest Wi-Fi」是界面文案：没冒号时值的第一个词要在名单里
            val colon = l.value.any { it == ':' || it == '：' }
            val listed = v.value.substringBefore(' ').lowercase().let { it in EnglishLists.firstNames || it in EnglishLists.lastNames }
            if (looksLikeNameValue(v.value) && (colon || listed)) add(v.range, confidence)
        }
        BEFORE_EMAIL.findAll(text).forEach { if (!EnglishLists.isBrand(it.value)) add(it.range, confidence) }
        AIRLINE.findAll(text).forEach { m ->
            val titled = m.groups[3] != null
            val listed = m.groupValues[1].split(' ', '-').first().lowercase() in EnglishLists.lastNames &&
                m.groupValues[2].split(' ').first().lowercase() in EnglishLists.firstNames
            if (titled || listed) add(m.range, confidence)
        }
        MAIL_HEADER.find(text)?.let { m -> if (isNameLike(m.groupValues[1])) add(m.groups[1]!!.range, weakConfidence) }
        HONORIFIC.findAll(text).forEach { if (!EnglishLists.isBrand(it.value)) add(it.range, confidence) }
        GREETING.find(text)?.let { m ->
            if (m.groupValues[1].lowercase() in EnglishLists.firstNames) add(m.groups[1]!!.range, weakConfidence)
        }
        ROLE_NAME.findAll(text).forEach { m ->
            if (m.groupValues[1].lowercase() in EnglishLists.firstNames) add(m.groups[1]!!.range.first..m.range.last, weakConfidence)
        }
        SENDER.find(text)?.let { m -> if (isNameLike(m.groupValues[1])) add(m.groups[1]!!.range, weakConfidence) }
        SYSTEM_MESSAGE.find(text)?.let { m -> if (isNameLike(m.groupValues[1])) add(m.groups[1]!!.range, weakConfidence) }
        TOP_BAR.find(text)?.let { m ->
            val inner = m.groups[1]!!
            val names = EnglishNameGuess.namesIn(inner.value)
            if (names.size == 1 && names[0] == inner.value.indices) add(inner.range, weakConfidence)
        }
        return out
    }

    private companion object {
        /** 英文的人名字段名。不分大小写：表单常写「FULL NAME」「Ship To」。 */
        val LABEL = Regex(
            """(?i)\b(?:full name|first name|last name|legal name|preferred name|guest name|lead guest|primary guest|""" +
                """account name|account holder|card ?holder|name on card|contact name|emergency contact|member name|""" +
                """driver name|policy ?holder|insured|recipient|passenger|traveler|traveller|customer|sender|attn|""" +
                """attention|payee|beneficiary|patient|tenant|applicant|employee|student|guest|name|""" +
                """ship to|bill to|billed to|sold to|invoice to|deliver to|delivering to)\s*[:：]?\s+"""
        )
        val VALUE = Regex("""[A-Z][A-Za-z'.-]*(?:\s+[A-Z][A-Za-z'.-]*){0,3}""")

        val BEFORE_EMAIL = Regex("""\b[A-Z][a-z]+(?:\s+[A-Z]\.?)?(?:\s+[A-Z][a-z'-]+){1,2}(?=\s*<[^>@\s]+@)""")
        val HONORIFIC = Regex("""\b(?:Mr|Mrs|Ms|Miss|Mx|Dr|Prof)\.?\s+[A-Z][a-z]+(?:\s+[A-Z][a-z]+)?""")
        val GREETING = Regex("""(?i:^\s*(?:hi|hello|hey|dear|thanks|thank you|thanks for \w+)),?\s+([A-Z][a-z]+)\b""")
        val ROLE_NAME = Regex(
            """(?i:\b(?:driver|dasher|courier|shopper|rider|host|guide|agent|representative|rep|technician|tech|instructor|""" +
                """stylist|nurse|teacher|coach|landlord|tenant|seller|buyer|carrier|partner|rode with|delivered by|""" +
                """handled by|assigned to))\s*,?\s+([A-Z][a-z]+)(?:\s+[A-Z]\.)?"""
        )
        val AIRLINE = Regex("""\b([A-Z]{2,}(?:[ '-][A-Z]{2,})*)/([A-Z]{2,}(?: [A-Z]{2,})*?)(?:\s?(MR|MRS|MS|MISS|MSTR|DR))?\b""")
        val MAIL_HEADER = Regex("""^\s*(?:To|From|Cc|Bcc)\s*:\s*([A-Z][a-z]+(?:\s+[A-Z][a-z'-]+){0,2})""")
        val SENDER = Regex("""^\s*~\s*([A-Z][A-Za-z'.-]+(?:\s+[A-Z][A-Za-z'.-]+){0,2})\s*$""")
        val SYSTEM_MESSAGE = Regex(
            """^\s*([A-Z][a-z]+(?:\s+[A-Z][a-z'-]+){0,2})\s+(?:joined|left|added|removed|changed the|created|paid|charged|""" +
                """sent you|sent|requested|shared|reacted|liked|mentioned you|invited you|is typing|accepted|declined)\b"""
        )
        val TOP_BAR = Regex("""^\s*[<‹〈]\s*(.+?)\s*$""")

        /** 字段名后面紧跟着的不是名字、而是界面词：「Name Order Details」「Customer Service」。 */
        val NOT_NAME_WORDS = setOf(
            "order", "details", "address", "phone", "email", "number", "date", "total", "price", "room",
            "service", "support", "information", "info", "id", "type", "required", "optional",
        )

        fun looksLikeNameValue(v: String): Boolean {
            val words = v.split(Regex("\\s+"))
            return words.size in 1..4 && words.none { it.trim('.', ',').lowercase() in NOT_NAME_WORDS } &&
                !EnglishLists.isBrand(v)
        }

        /** 一两个大写词，第一个是常见的名；既是名又是普通词的（Will、Grant），后面要跟一个姓。品牌不算。 */
        fun isNameLike(s: String): Boolean {
            val w = s.split(Regex("\\s+")).map { it.lowercase() }
            if (w.isEmpty() || w[0] !in EnglishLists.firstNames || w[0] in EnglishNameGuess.NOT_FIRST) return false
            if (w[0] in EnglishLists.ambiguousFirst && (w.size < 2 || w[1] !in EnglishLists.lastNames)) return false
            return !EnglishLists.isBrand(s)
        }
    }
}

/**
 * 从字面猜英文人名：相邻两个大写词，前一个是常见的名、后一个是常见的姓（中间可夹一个缩写「A.」），
 * 或者是拼音名「Zhang Wei」「Xiaoming Li」。中文那边的 [PersonNameRecognizer] 对应的英文版。
 *
 * 误报比字段名高得多（「Grace Church」「Austin Texas」「Trader Joe」），所以只算猜测，放进 [NeedsAnchor]：
 * 附近有电话、邮箱、地址、字段名才算数。两头都是普通英文词的（「Will Power」「Rose Hill」）、
 * 整段是品牌的（「Ralph Lauren」）不算。
 */
class EnglishNameGuess(private val confidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> = namesIn(text).map { RuleMatch(it, confidence) }

    internal companion object {
        /** 首字母大写（或全大写）的词，可带连字符：「Walsh」「GARCIA」「Smith-Jones」。 */
        private val CAP_WORD = Regex("""\b(?:[A-Z][a-z]+(?:-[A-Z][a-z]+)?|[A-Z]{2,})\b""")
        private val INITIAL = Regex("""^\s+[A-Z]\.\s+$""")

        /** 名单里有、但在截图上多半是普通词或地名开头（「San Francisco」「Santa Monica」）的名与姓。小写。 */
        val NOT_FIRST = setOf(
            "will", "may", "mark", "grant", "bill", "art", "sale", "order", "power", "long", "young", "king", "page",
            "san", "santa", "saint", "del", "lake",
        )
        private val NOT_LAST = setOf(
            "street", "road", "lane", "court", "way", "park", "hill", "grove", "bank", "edition", "details", "order", "store",
            "books", "shop", "music", "games",
        )

        private val PINYIN_SURNAMES = setOf(
            "wang", "li", "zhang", "liu", "chen", "yang", "huang", "zhao", "wu", "zhou", "xu", "sun", "ma", "zhu", "hu",
            "guo", "he", "gao", "lin", "luo", "zheng", "liang", "xie", "song", "tang", "han", "feng", "deng", "cao", "peng",
            "zeng", "xiao", "tian", "dong", "yuan", "pan", "yu", "jiang", "cai", "du", "ye", "cheng", "su", "wei", "lu",
            "ding", "ren", "shen", "yao", "cui", "zhong", "tan", "fan", "jin", "shi", "liao", "jia", "xia", "fu", "fang",
            "bai", "zou", "meng", "xiong", "qin", "qiu", "yin", "xue", "yan", "duan", "lei", "hou", "long", "tao", "gu",
            "mao", "hao", "gong", "shao", "wan", "qian", "dai", "mo", "kong", "xiang", "chang", "kang", "niu", "ou", "mu",
        )

        /** 汉语拼音音节（不带声调），给名用：「Wei」「Xiaoming」「Zhiyuan」。 */
        private const val PINYIN_SYLLABLE =
            "(?:zh|ch|sh|[bpmfdtnlgkhjqxrzcsyw])?(?:iang|iong|uang|ang|eng|ing|ong|uai|uan|ian|iao|ai|ei|ao|ou|an|en|in|un|" +
                "ua|uo|ia|ie|iu|ui|ue|er|a|o|e|i|u|v)"
        private val PINYIN_GIVEN = Regex("(?i)$PINYIN_SYLLABLE{1,2}")

        private fun isPinyinName(a: String, b: String) =
            (a in PINYIN_SURNAMES && PINYIN_GIVEN.matches(b)) || (b in PINYIN_SURNAMES && PINYIN_GIVEN.matches(a))

        /** 逐个词对试，不用一个正则扫：「Call Michael Chen」里 Call+Michael 不成，还要试 Michael+Chen。 */
        fun namesIn(text: String): List<IntRange> {
            val words = CAP_WORD.findAll(text).toList()
            val out = ArrayList<IntRange>()
            for (i in 0 until words.size - 1) {
                val a = words[i]
                val b = words[i + 1]
                val between = text.substring(a.range.last + 1, b.range.first)
                if (between.isEmpty() || (between.isNotBlank() && !INITIAL.matches(between))) continue
                val fa = a.value.lowercase()
                val lb = b.value.substringBefore('-').lowercase()
                val listed = fa in EnglishLists.firstNames && lb in EnglishLists.lastNames && fa !in NOT_FIRST && lb !in NOT_LAST &&
                    !(fa in EnglishLists.ambiguousFirst && lb in EnglishLists.ambiguousLast)
                if (!listed && !isPinyinName(fa, lb)) continue
                val r = a.range.first..b.range.last
                if (EnglishLists.isBrand(text.substring(r))) continue
                if (out.none { it.first <= r.last && r.first <= it.last }) out += r
            }
            return out
        }
    }
}
