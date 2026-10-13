package moe.flinty.yomark.rules

/**
 * 毕业证、学位证正文里的名字：没有字段名，夹在一句话里。
 * - 毕业证：「学生 张三，性别 男，一九九八年三月五日生，于……」；
 * - 学位证：「张三，男，一九九八年三月五日生。」——名字后面紧跟「，男，」「，女，」，再后面是年月日俱全的「……日生」，
 *   同一行接着写，或者这一行在「男，」后面折断、下一行以它打头。
 *   只看「，男，」不够：「好的，男，女都可以」；只看后面有个年份也不够：「招聘：保安，男，1970年后出生」「收到，女，1990年生的那个」。
 */
class DiplomaName(private val confidence: Float) : PageFinder {

    override fun findIn(text: String): List<RuleMatch> = find(text) { false }

    override fun findOnPage(page: Page): List<PageMatch> = page.texts.indices.flatMap { i ->
        find(page.texts[i]) { page.wrapOf(i)?.let { j -> BORN.containsMatchIn(HanView.of(page.texts[j]).text) } == true }
            .map { PageMatch(i, it) }
    }

    /** [wrapped]：这一行在「男，」后面就完了，折下去的那一行以「……日生」打头。 */
    private fun find(text: String, wrapped: () -> Boolean): List<RuleMatch> {
        val view = HanView.of(text)
        val t = view.text
        val degree = DEGREE.findAll(t).filter { m ->
            val rest = t.substring(m.range.last + 1)
            if (rest.isBlank()) wrapped() else BORN.containsMatchIn(rest)
        }
        return (STUDENT.findAll(t) + degree)
            .map { it.groups[1]!!.range }
            .filterNot { NOT_A_NAME.containsMatchIn(t.substring(it)) }
            .distinct()
            .map { RuleMatch(view.toSource(it), confidence) }
            .toList()
    }

    private companion object {
        const val NAME = """([\u4e00-\u9fa5]{1,8}(?: ?[·•・.．‧] ?[\u4e00-\u9fa5]{1,8}){1,3}|[\u4e00-\u9fa5]{2,4})"""

        val STUDENT = Regex("""(?<![\u4e00-\u9fa5])学生 *$NAME *[，,] *性 *别""")

        val DEGREE = Regex("""(?<![^\s，,。：:；;])$NAME *[，,] *[男女] *[，,]""")

        /** 一行开头的「一九九八年三月五日生」。 */
        val BORN = Regex("""^\s*${Dates.CHINESE_DAY}${BornOn.SHENG}""")

        /** 句子里的字：名字里不会有。 */
        val NOT_A_NAME = Regex("""[的了是都也和与或在我你他她它们这那]""")
    }
}
