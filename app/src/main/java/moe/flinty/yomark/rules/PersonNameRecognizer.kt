package moe.flinty.yomark.rules

import com.hankcs.hanlp.HanLP
import com.hankcs.hanlp.seg.Segment
import com.hankcs.hanlp.seg.common.Term

/**
 * 不看字段名、不看电话，从字面上认中文人名：HanLP 的人名识别（词典加角色标注）。
 *
 * 起因：滴滴出票页的乘车人一行是「沐晨冉 成人」，下面是打了星的身份证号。没有字段名，后面也不是电话，
 * LabeledField 和 NameBeforePhone 都接不住，只有按需跑的 AI 复查（Gemini Nano）圈了出来——
 * 而 Nano 只在少数旗舰上有。HanLP 是纯 Java 的词典与统计模型，jar 8 MB，一行零点几毫秒，所有设备都能跑。
 *
 * 在 HanLP 的结果上做三件事：
 * - **拼回被切开的名字**：相邻的人名词首尾相接就并成一个，「沐晨」+「冉」→「沐晨冉」；
 * - **单个的姓往后补**：只认出一个姓（「< 陈晓峰」里的「陈」、「赵师傅」里的「赵」）时，
 *   后面紧跟着一两个汉字的词就连上，凑成两到三个字。复姓同理，凑到四个字为止；
 * - **挡商号和地名**：以人名命名、以姓开头的店名品牌（「申通快递」「瑞幸咖啡」「张亮麻辣烫」「高德地图」）
 *   会被认成人名，名字后面紧跟着行业词的整个作废；姓开头的路名（「祁门路」）也会被认成人名，
 *   以门牌用字结尾的整个作废。另有几个实测撞上的词直接排除。
 *
 * 只要中文人名（nr），音译名（nrf）和日本人名（nrj）不要：「优衣库」就是被当成音译名认出来的。
 *
 * 跑在 HanView 上：逐字切分的「沐 晨 冉」先拼回「沐晨冉」再交给分词。
 */
class PersonNameRecognizer(private val confidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        val t = view.text
        val terms = segmenter.seg(t)
        val out = ArrayList<RuleMatch>()
        var i = 0
        while (i < terms.size) {
            if (!terms[i].isChineseName()) { i++; continue }
            val start = terms[i].offset
            var end = start + terms[i].word.length
            var j = i + 1
            while (j < terms.size && terms[j].isChineseName() && terms[j].offset == end) {
                end += terms[j].word.length
                j++
            }
            // 只认出了姓：后面紧跟的一两个汉字的词连上
            if (j < terms.size && isBareSurname(t.substring(start, end))) {
                val next = terms[j]
                val cap = if (end - start == 1) MAX_SINGLE_SURNAME_NAME else MAX_NAME
                if (next.offset == end && next.word.length <= 2 && next.word.all { it.isHan() } &&
                    end - start + next.word.length <= cap
                ) {
                    end += next.word.length
                    j++
                }
            }
            val name = t.substring(start, end)
            if (name.length in MIN_NAME..MAX_NAME && name !in NOT_NAMES && name.last() !in PLACE_ENDINGS &&
                !followedByBusiness(t, end)
            ) {
                out += RuleMatch(view.toSource(start until end), confidence)
            }
            i = j
        }
        return out
    }

    private fun Term.isChineseName(): Boolean {
        val n = nature.toString()
        return n.startsWith("nr") && n != "nrf" && n != "nrj"
    }

    private fun isBareSurname(s: String) = s.length == 1 || s in COMPOUND_SURNAMES

    private fun followedByBusiness(t: String, end: Int): Boolean {
        val rest = t.substring(end).trimStart()
        return BUSINESS_SUFFIXES.any { rest.startsWith(it) }
    }

    companion object {
        private const val MIN_NAME = 2
        private const val MAX_NAME = 4
        private const val MAX_SINGLE_SURNAME_NAME = 3

        private val COMPOUND_SURNAMES = setOf(
            "欧阳", "司马", "上官", "诸葛", "东方", "皇甫", "司徒", "夏侯", "慕容", "令狐", "公孙", "长孙",
            "宇文", "尉迟", "独孤", "南宫", "西门", "端木", "轩辕", "呼延", "百里", "亓官", "澹台", "万俟",
        )

        /** 紧跟在后面就说明前面是商号：快递公司、餐饮、出行、地图、零售。 */
        private val BUSINESS_SUFFIXES = listOf(
            "快递", "速递", "速运", "快运", "物流", "驿站", "咖啡", "奶茶", "茶饮", "麻辣烫", "面条", "面馆",
            "拉面", "米线", "火锅", "烧烤", "小吃", "餐厅", "饭店", "酒店", "宾馆", "民宿", "超市", "便利店",
            "药房", "药店", "医院", "诊所", "银行", "证券", "保险", "集团", "公司", "有限", "股份", "科技",
            "电器", "家居", "服饰", "食品", "商城", "旗舰店", "专卖店", "官方", "门店", "易购", "出行", "地图",
            "打车", "外卖", "优选", "严选", "影城", "影院", "学校", "学院", "大学", "中学", "小学", "店", "馆",
        )

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
