package moe.flinty.yomark.rules

/**
 * 一种旁证：从字面猜出的人名（[NeedsAnchor]）要有一处旁证才算数，算数了按「人名」的设置打码，
 * 没有的出厂不圈（见 RuleClassifier）。
 *
 * 旁证是一张表：DefaultRuleSet.nameAnchors。**加一种旁证就是往那里加一行**，
 * 再在 NameAnchorPageTest 的例子表里给它配一个例子（不配，那里的测试会提醒）。
 * 设置页人名那一页上「旁证是……」那句话由这张表拼出来，不用另改。
 *
 * @param label 写给用户看的名字，拼进设置页上「旁证是……」那句话里。
 * @param reach 离名字多近才算。
 * @param finder 在一行里找出它。
 */
class NameAnchor(val label: String, val reach: Reach, val finder: Finder) {

    enum class Reach {
        /**
         * 紧挨着名字（中间至多隔着空格），或者就在名字里头：称呼。「王小明先生」「李女士」「张老师好」。
         * 只认紧挨着的：上一行写着「先生您好」，说明不了这一行的名字是谁。
         */
        ATTACHED,

        /**
         * 同一行、同一排或上下相邻一行：电话、证件号、地址、字段名。
         * 滴滴出票页上名字在上、打了星的证件号在下，就是这一种。
         */
        NEARBY,
    }
}

/**
 * 一组固定的词：字段名、称呼、票种。长词排在前面，「成人票」不会只认出「成人」。
 *
 * 跑在 HanView 上：逐字切分的「收 货 人」照样认得出，区间映射回原串。
 *
 * @param boundedBefore 词前面不能紧挨着汉字或字母：「用户名」里的「户名」不是字段名。
 *   称呼不设这一条，它本来就紧跟在名字后面。
 * @param boundedAfter 词后面不能紧挨着汉字：「成人用品」里的「成人」不是票种。
 *   字段名不设这一条，值常常直接跟在后面（「收货人张三」）。
 */
class Words(
    words: List<String>,
    boundedBefore: Boolean = false,
    boundedAfter: Boolean = false,
) : Finder {

    private val pattern = Regex(
        (if (boundedBefore) "(?<![\\u4e00-\\u9fa5A-Za-z])" else "") +
            "(?:" + words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) } + ")" +
            (if (boundedAfter) "(?![\\u4e00-\\u9fa5])" else "")
    )

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        return pattern.findAll(view.text).map { RuleMatch(view.toSource(it.range), 1f) }.toList()
    }
}
