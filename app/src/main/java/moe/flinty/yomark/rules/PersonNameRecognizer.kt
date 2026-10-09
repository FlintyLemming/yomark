package moe.flinty.yomark.rules

import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.corpus.tag.NR
import com.hankcs.hanlp.dictionary.CoreDictionary
import com.hankcs.hanlp.dictionary.nr.PersonDictionary
import com.hankcs.hanlp.seg.Segment
import com.hankcs.hanlp.seg.common.Term

/**
 * 不看字段名、不看电话，从字面上认中文人名：HanLP 的人名识别（词典加角色标注）。
 *
 * 起因：滴滴出票页的乘车人一行是「沐晨冉 成人」，下面是打了星的身份证号。没有字段名，后面也不是电话，
 * LabeledField 和 NameBeforePhone 都接不住，只有按需跑的 AI 复查（Gemini Nano）圈了出来——
 * 而 Nano 只在少数旗舰上有。HanLP 是纯 Java 的词典与统计模型，jar 8 MB，一行零点几毫秒，所有设备都能跑。
 *
 * HanLP 的角色标注不看上下文，凡是「姓氏用字 + 一两个字」都可能认成人名。真机反馈：淘宝商品规格页上
 * 「福来恩」「海乐妙」（药名）、「成猫」「单月」、「8周以上」里的「周以上」全被圈成人名。
 * 「福来恩」按 HanLP 自己的统计比「沐晨冉」还像人名（福、沐作姓都只有二十来次），单看字面分不开，
 * 只能看它周围。所以在 HanLP 的结果上做这几件事：
 * - **拼回被切开的名字**：相邻的人名词首尾相接就并成一个，「沐晨」+「冉」→「沐晨冉」；
 * - **单个的姓往后补**：只认出一个姓（「< 陈晓峰」里的「陈」、「赵师傅」里的「赵」）时，后面紧跟着的
 *   一两个字像名（HanLP 人名词典里常作名用）或是称呼，才连上。「周」+「以上」不连；
 * - **姓 + 称呼补认**：HanLP 常常不把它们标成人名——「张先生」「刘老师」整个成了专名（nz），
 *   「张」「老师」被拆开、「张」还被标成量词。姓是常见的姓、后面紧跟称呼的，照样当人名。
 *   「常见」按人名词典：作姓的次数够多，还要多过作上下文的次数，「向老师请教」里的「向」是介词；
 * - **看左右邻居**：人名紧挨着的是标点、空格、动词、介词（「转告周振宇」「黄磊拍了拍你」「来自赵磊的转账」）。
 *   左边紧贴名词、形容词、数量词（「好丽友派」「8周」），右边紧贴名词、数量、规格词
 *   （「福来恩3支」「冠能猫粮」「单月装」）的，是商品名、品牌名里被切出来的一截，作废。
 *   称呼、票种、自己的东西例外：「张三老师」「沐晨冉成人」「李娜电话多少」；
 * - **名要像名**：单名的那个字在人名词典里几乎不作名用的（「成猫」的猫），作废；
 *   四个字的只认复姓开头的（「欧阳娜娜」），「成猫」+「单月」拼出来的「成猫单月」不要；
 * - **挡商号和地名**：以人名命名、以姓开头的店名品牌（「申通快递」「瑞幸咖啡」「张亮麻辣烫」「高德地图」）
 *   名字后面紧跟着行业词的整个作废；姓开头的路名（「祁门路」）以门牌用字结尾的整个作废。另有几个实测撞上的词直接排除。
 *
 * 只要中文人名（nr），音译名（nrf）和日本人名（nrj）不要：「优衣库」就是被当成音译名认出来的。
 *
 * 这里只管一行之内；认出来的仍然只是猜测。附近有没有电话、证件号、地址、「收货人」这类字段，
 * 由 RuleClassifier 看整页再定（见 [NeedsAnchor]）。
 *
 * 跑在 HanView 上：逐字切分的「沐 晨 冉」先拼回「沐晨冉」再交给分词。
 */
class PersonNameRecognizer(
    private val confidence: Float,
    /**
     * 跟在名字后面的称呼（DefaultRuleSet.PERSON_TITLES）。拿它补全「赵」+「师傅」、补认 HanLP 漏标的「张先生」，
     * 也放过紧贴在名字后面的它（「张三老师」）。同一张表也是人名的旁证，见 DefaultRuleSet.nameAnchors。
     */
    private val titles: List<String>,
) : Finder {

    /** 名字后面紧贴着也不奇怪的词：称呼，加上 [RIGHT_WORDS]。 */
    private val rightWords = titles + RIGHT_WORDS

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        val t = view.text
        val terms = segmenter.seg(t)
        val out = ArrayList<RuleMatch>()
        var i = 0
        while (i < terms.size) {
            val start = terms[i].offset
            // 名字之后的那个词的下标
            val j = if (terms[i].isChineseName()) nameEnd(t, terms, i) else titledSurnameEnd(terms, i)
            if (j < 0) { i++; continue }
            val end = terms[j - 1].offset + terms[j - 1].word.length
            val name = t.substring(start, end)
            val titled = titles.any { name.endsWith(it) }
            if (looksLikeName(name, titled) && !followedByBusiness(t, end) &&
                fitsLeft(t, terms.getOrNull(i - 1), start) && fitsRight(t, terms.getOrNull(j), end, titled)
            ) {
                out += RuleMatch(view.toSource(start until end), confidence)
            }
            i = j
        }
        return out
    }

    /** 从人名词 [i] 起：并上首尾相接的人名词；只认出了姓的，后面紧跟的一两个字像名、或者是称呼，才连上。 */
    private fun nameEnd(t: String, terms: List<Term>, i: Int): Int {
        val start = terms[i].offset
        var end = start + terms[i].word.length
        var j = i + 1
        while (j < terms.size && terms[j].isChineseName() && terms[j].offset == end) {
            end += terms[j].word.length
            j++
        }
        if (j < terms.size && isBareSurname(t.substring(start, end))) {
            val next = terms[j]
            val cap = if (end - start == 1) MAX_SINGLE_SURNAME_NAME else MAX_NAME
            if (next.offset == end && next.word.length <= 2 && next.word.all { it.isHan() } &&
                end - start + next.word.length <= cap &&
                (next.word in titles || looksLikeGivenName(next.word))
            ) {
                j++
            }
        }
        return j
    }

    /**
     * HanLP 没标成人名的姓 + 称呼：「张先生」整个成了专名，或者「张」「老师」被拆成两个词。
     * 是的话返回名字之后那个词的下标，不是返回 -1。
     *
     * 整个成词的，核心词典里收了的不算：那是品牌、常用词（「康师傅」）。「张先生」「刘老师」只在
     * 自定义词典里，是语料里常见的称呼，不是一个词。
     */
    private fun titledSurnameEnd(terms: List<Term>, i: Int): Int {
        val word = terms[i].word
        val title = titles.firstOrNull { word.length > it.length && word.endsWith(it) }
        if (title != null) {
            val isName = isCommonSurname(word.dropLast(title.length)) && CoreDictionary.get(word) == null
            return if (isName) i + 1 else -1
        }
        val next = terms.getOrNull(i + 1) ?: return -1
        val adjacent = next.offset == terms[i].offset + word.length
        return if (adjacent && next.word in titles && isCommonSurname(word)) i + 2 else -1
    }

    /**
     * 常见的姓：人名词典里作姓够 [MIN_SURNAME_FREQUENCY] 次，而且多过它作人名上下文的次数。
     * 「班」作姓 34 次，「班主任」不是人；「向」作姓 309 次、作上下文一千五百多次，「向老师请教」里它是介词。
     */
    private fun isCommonSurname(s: String): Boolean {
        if (s in COMPOUND_SURNAMES) return true
        if (s.length != 1) return false
        val item = PersonDictionary.dictionary.get(s) ?: return false
        val asSurname = item.getFrequency(NR.B)
        val asContext = item.getFrequency(NR.K) + item.getFrequency(NR.L) + item.getFrequency(NR.M)
        return asSurname >= MIN_SURNAME_FREQUENCY && asSurname >= asContext
    }

    private fun Term.isChineseName(): Boolean {
        val n = nature.toString()
        return n.startsWith("nr") && n != "nrf" && n != "nrj"
    }

    private fun isBareSurname(s: String) = s.length == 1 || s in COMPOUND_SURNAMES

    /** 长度、排除词、地名用字，以及姓后面那一两个字像不像名。带称呼的（「赵师傅」「王女士」）不看名。 */
    private fun looksLikeName(name: String, titled: Boolean): Boolean {
        if (name.length !in MIN_NAME..MAX_NAME || name in NOT_NAMES || name.last() in PLACE_ENDINGS) return false
        if (!name.all { it.isHan() }) return false
        val surname = if (name.take(2) in COMPOUND_SURNAMES) 2 else 1
        if (name.length == MAX_NAME && surname == 1) return false
        return titled || looksLikeGivenName(name.substring(surname))
    }

    /**
     * 按 HanLP 人名词典里的角色频次：单名看这个字作单名（E）的次数，双名看首字作名首（C）、
     * 末字作名末（D）的次数，或者整个词本身就常作双名（Z，「晓峰」）。门槛很低，只挡几乎不作名用的字——
     * 「月」作单名 30 次、「三」22 次、「嵩」41 次，都得放过；「猫」4 次、「茗」5 次，挡掉。
     */
    private fun looksLikeGivenName(given: String): Boolean = when (given.length) {
        1 -> roleFrequency(given, NR.E) >= MIN_ROLE_FREQUENCY
        2 -> roleFrequency(given, NR.Z) > 0 ||
            (roleFrequency(given.substring(0, 1), NR.C) >= MIN_ROLE_FREQUENCY &&
                roleFrequency(given.substring(1), NR.D) >= MIN_ROLE_FREQUENCY)
        else -> false
    }

    private fun roleFrequency(word: String, role: NR): Int =
        PersonDictionary.dictionary.get(word)?.getFrequency(role) ?: 0

    /** 左边紧贴着的词。中间隔着空格、标点的不算紧贴。 */
    private fun fitsLeft(t: String, prev: Term?, start: Int): Boolean {
        if (start == 0) return true
        val c = t[start - 1]
        if (c.isAsciiLetterOrDigit()) return false                    // 「8周以上」
        if (!c.isHan() || prev == null || prev.offset + prev.word.length != start) return true
        if (LEFT_ROLES.any { prev.word.endsWith(it) }) return true    // 「司机李建国」「群主王建国」
        return !prev.isNounLike() && prev.natureInitial() !in "amqbr"
    }

    /** 右边紧贴着的词。[titled]：名字以称呼结尾。 */
    private fun fitsRight(t: String, next: Term?, end: Int, titled: Boolean): Boolean {
        if (end == t.length) return true
        val c = t[end]
        if (c.isDigit()) {
            // 紧跟一个数再跟一个量词或单位：「福来恩3支」「卫龙106g」
            var k = end
            while (k < t.length && (t[k].isDigit() || t[k] == '.')) k++
            return k == t.length || !t[k].isLetter()
        }
        if (!c.isHan() || next == null || next.offset != end) return true
        if (c in SPEC_SUFFIXES) return false                          // 「单月装」「季度装」
        if (rightWords.any { next.word.startsWith(it) }) return true  // 「张三老师」「李娜电话多少」
        // 称呼后面紧跟形容词是客套：「张老师好」。名词、数量、区别词照样不行：「李先生牛肉面」「康师傅红烧」
        if (titled) return !next.isNounLike() && next.natureInitial() !in "mqb"
        return !next.isNounLike() && next.natureInitial() !in "amqb"
    }

    /** 名词一类（含专名、机构名、地名、音译名），以及名动词「驱虫」「快递」、名形词。人名本身已在前面并掉了。 */
    private fun Term.isNounLike(): Boolean {
        val n = nature.toString()
        return (n.startsWith("n") && n != "nr" && n != "nx") || n == "vn" || n == "an"
    }

    /** 词性的大类：a 形容词、m 数词、q 量词、b 区别词、r 代词…… */
    private fun Term.natureInitial(): Char = nature.toString().firstOrNull() ?: ' '

    private fun Char.isAsciiLetterOrDigit() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    /** 后面紧跟着行业词（「张亮麻辣烫」），或者分店的括号（「李先生（望京店）」）：这是店。 */
    private fun followedByBusiness(t: String, end: Int): Boolean {
        val rest = t.substring(end).trimStart()
        return BUSINESS_SUFFIXES.any { rest.startsWith(it) } || BRANCH.containsMatchIn(rest)
    }

    companion object {
        private const val MIN_NAME = 2
        private const val MAX_NAME = 4
        private const val MAX_SINGLE_SURNAME_NAME = 3
        private const val MIN_ROLE_FREQUENCY = 10
        private const val MIN_SURNAME_FREQUENCY = 100

        private val COMPOUND_SURNAMES = setOf(
            "欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "司徒", "夏侯", "慕容", "令狐", "公孙", "长孙",
            "宇文", "尉迟", "独孤", "南宫", "西门", "端木", "轩辕", "呼延", "百里", "亓官", "澹台", "万俟",
        )

        /**
         * 名字后面紧贴着也不奇怪的词（称呼之外）：亲属、票种、这个人自己的东西，
         * 以及 HanLP 迷你词典标成名词的几个动词（「李师傅揽收」）。
         */
        private val RIGHT_WORDS = listOf(
            "学长", "学姐", "老婆", "老公", "妈妈", "爸爸", "父亲", "母亲", "儿子", "女儿", "家",
            "成人", "儿童", "学生", "婴儿", "本人", "电话", "手机", "微信", "邮箱", "账号", "号码", "身份证",
            "揽收", "签收", "代收", "派送", "派件",
        )

        /** 紧贴在名字前面也不奇怪的身份名词：「司机李建国」「群主王建国」。 */
        private val LEFT_ROLES = listOf(
            "司机", "骑手", "快递员", "派送员", "配送员", "群主", "管理员", "老师", "同学", "医生", "护士",
            "客服", "店主", "房东", "经理", "主管", "师傅",
        )

        /** 紧跟在名字后面就说明这是规格：「单月装」「季度装」「经典款」。 */
        private const val SPEC_SUFFIXES = "装套款版型色码味"

        /** 紧跟在后面就说明前面是商号：快递公司、餐饮、出行、地图、零售。 */
        private val BUSINESS_SUFFIXES = listOf(
            "快递", "速递", "速运", "快运", "物流", "驿站", "咖啡", "奶茶", "茶饮", "麻辣烫", "面条", "面馆",
            "拉面", "米线", "火锅", "烧烤", "小吃", "餐厅", "饭店", "酒店", "宾馆", "民宿", "超市", "便利店",
            "药房", "药店", "医院", "诊所", "银行", "证券", "保险", "集团", "公司", "有限", "股份", "科技",
            "电器", "家居", "服饰", "食品", "商城", "旗舰店", "专卖店", "官方", "门店", "易购", "出行", "地图",
            "打车", "外卖", "优选", "严选", "影城", "影院", "学校", "学院", "大学", "中学", "小学", "店", "馆",
        )

        /** 紧跟在名字后面的分店括号：「（望京店）」「(中关村店)」。 */
        private val BRANCH = Regex("""^[（(][^）)]{0,10}店[）)]""")

        /** 人名几乎不以这些字结尾，地址里却到处都是：「祁门路」「梅园村」。 */
        private const val PLACE_ENDINGS = "路街道巷弄村镇乡区县市省栋幢室号楼"

        /** HanLP 实测会认成人名、但在截图里几乎不会是人名的词。 */
        private val NOT_NAMES = setOf("刘海屏", "双早", "双床", "单早")

        /**
         * 共用一个分词器：HanLP 文档写明 seg 线程安全，预热线程和识别可以同时用它。
         * 开启偏移量，结果才落得回行内的区间。没有 hanlp.properties 时 HanLP 进入 portable 模式，
         * 词典直接从 jar 里读（预编译好的 .bin），不用拷文件、不写缓存。
         */
        private val segmenter: Segment by lazy { HanLP.newSegment().enableOffset(true) }

        /**
         * 提前把词典读进内存。第一次分词要加载近 30 MB 的词典（桌面 JVM 上约 0.7 秒），
         * 放在识别时现读会把第一张图拖慢；应用启动时在后台线程先读掉。
         */
        fun warmUp() {
            segmenter.seg("张三李四")
        }
    }
}
