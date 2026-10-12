package moe.flinty.yomark.rules

/**
 * 毕业证、学位证正文里的名字：没有字段名，夹在一句话里。
 * - 毕业证：「学生 张三，性别 男，一九九八年三月五日生，于……」；
 * - 学位证：「张三，男，一九九八年三月五日生。」——名字后面紧跟「，男，」「，女，」和出生年份
 *   （一行在「男，」后面折断的也算）。只看「，男，」不够：「好的，男，女都可以」。
 */
class DiplomaName(private val confidence: Float) : Finder {

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        return (STUDENT.findAll(view.text) + DEGREE.findAll(view.text))
            .map { it.groups[1]!!.range }
            .filterNot { NOT_A_NAME.containsMatchIn(view.text.substring(it)) }
            .distinct()
            .map { RuleMatch(view.toSource(it), confidence) }
            .toList()
    }

    private companion object {
        const val NAME = """([\u4e00-\u9fa5]{1,8}(?: ?[·•・.．‧] ?[\u4e00-\u9fa5]{1,8}){1,3}|[\u4e00-\u9fa5]{2,4})"""

        val STUDENT = Regex("""(?<![\u4e00-\u9fa5])学生 *$NAME *[，,] *性 *别""")

        val DEGREE = Regex(
            """(?<![^\s，,。：:；;])$NAME *[，,] *[男女] *[，,](?= *(?:$|[〇零○Oo0一二三四五六七八九\d]{4} *年))"""
        )

        /** 句子里的字：名字里不会有。 */
        val NOT_A_NAME = Regex("""[的了是都也和与或在我你他她它们这那]""")
    }
}
