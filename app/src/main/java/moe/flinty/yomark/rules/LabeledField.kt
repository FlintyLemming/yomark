package moe.flinty.yomark.rules

/**
 * 标签锚定：先认字段名，再取紧随其后的值。
 *
 * 姓名与地址没有任何形状特征——`张三` 和 `北京` 在正则眼里毫无区别，
 * 校验位、长度窗口、字符集这些手段一个都用不上。`Sensitivity.kt` 因此把
 * PERSON_NAME / POSTAL_ADDRESS 标成「v2：需要 NER」，于是 0.2.0 上这两类一条规则都没有。
 *
 * 这里换一个支点：**不判断值像不像，判断它前面写着什么。**「收货地址」四个字之后那一段，
 * 几乎必然是地址。代价是覆盖面止于有字段名的版面——订单页、快递单、证件、银行页——
 * **聊天记录里随口提到的人名地址抓不到**，那仍然需要 NER。这是近似，不是等价物。
 *
 * 遮的是**值**不是字段名：把「收货人」三个字一起涂黑，用户就看不懂自己在看什么了。
 *
 * 跑在 HanView 上：中文识别器把「收货地址」切成「收 货 地 址」时，标签照样认得出。
 * 汉字字段名的字与字之间还可以夹空格：营业执照上两端对齐的「住　　所」，身份证上拉开的「姓 名」
 * 整行没被认成逐字切分时，HanView 不会去掉这些空格。
 *
 * 证件上的字段名常带英文（驾驶证「姓名 Name」、永居证「姓名/Name」、出生医学证明「出生时间 Date of Birth」）：
 * 字段名后面的英文译名跳过去再取值，只跳 [TRANSLATIONS] 里的——值本身也可能是拉丁字母（「ZHANG SAN」）。
 *
 * @param value 值的形状。姓名与地址差别很大（地址必须含数字，姓名遇数字就该停），
 *   所以不在这里写死，由调用方给。
 * @param ignoreCase 字段名不分大小写：英文证件上是「DATE OF BIRTH」，表单上是「Date Of Birth」。值的形状照旧分。
 * @param spaced 也是常用词的字段名（「所有人都到了」「经营者应当……」「法定代表人签字」）：后面要隔着冒号、空格
 *   或英文译名才算，紧接着字的是一句话。
 * @param nextField 同一排右边那一段以别的字段名打头，就不是这个字段的值（见 [findOnPage]）。
 */
class LabeledField(
    labels: List<String>,
    private val value: Regex,
    private val confidence: Float,
    ignoreCase: Boolean = false,
    spaced: List<String> = emptyList(),
    private val nextField: (String) -> Boolean = { false },
) : PageFinder {

    /**
     * 长标签排在前面，`收货地址` 才不会被 `地址` 抢先匹配掉半截。
     *
     * 前面的否定后顾是为了挡住词中词：汉字侧是「用户名」里的「户名」，
     * 拉丁侧是「IP地址」「MAC地址」里的「地址」和「Username」里的「Name」——
     * 这些都是界面文案，不是邮寄地址或姓名字段。紧邻字母、数字、汉字时
     * 多半不是一个独立字段名。「@所有人」是群聊里的提醒，也挡掉。
     *
     * 代价是「寄件人姓名 张三」这种标签叠标签的写法会被一起挡掉，
     * **少认一处好过错涂一处**。
     */
    private val label = Regex(
        "(?<![\\u4e00-\\u9fa5A-Za-z0-9@])(?" + (if (ignoreCase) "i" else "") + ":" +
            "(?:${alternatives(labels)})$TRANSLATED[：:＝=]?[ 　]*" +
            (if (spaced.isEmpty()) "" else "|(?:${alternatives(spaced)})(?:$TRANSLATION_STEP+[：:＝=]?[ 　]*|[：:＝=][ 　]*|[ 　]+)") +
            ")"
    )

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        return label.findAll(view.text)
            .mapNotNull { l -> valueAt(view.text, l.range.last + 1) }
            .map { RuleMatch(view.toSource(it), confidence) }
            .toList()
    }

    /** 从 [start] 起的值。表单上没填的格子里是提示语（「请输入」「请填写详细地址」），不是值。 */
    private fun valueAt(text: String, start: Int): IntRange? =
        value.matchAt(text, start)?.range?.takeIf { PROMPT.matchAt(text, it.first) == null }

    /**
     * 逐行认的那些，再加上字段名独占一段、值在同一排右边另一段的：驾驶证、行驶证上值在右边一栏，
     * 「姓名 Name」和「周以宁」之间隔着大半栏，OCR 拆成了两行。
     *
     * 只在中文字段名带着英文译名时这样接，那是证件的排版：单单一个「姓名」，右边隔得老远的可能是表头的下一格
     * （「姓名　学号　成绩」）；「收货地址：」右边可能是「去设置 >」。
     * 右边那一段从它的头一个字起要像值，也不能以别的字段名打头（[nextField]）。
     */
    override fun findOnPage(page: Page): List<PageMatch> {
        val out = page.texts.indices.flatMap { i -> findIn(page.texts[i]).map { PageMatch(i, it) } }.toMutableList()
        page.texts.forEachIndexed { i, text ->
            val view = HanView.of(text)
            val l = label.findAll(view.text).lastOrNull() ?: return@forEachIndexed
            if (view.text.substring(l.range.last + 1).isNotBlank()) return@forEachIndexed
            // 「姓名 Name」「Name/姓名」：中文字段名，这一段里还有英文
            if (l.value.none { it.isHan() } || view.text.none { it in 'A'..'Z' || it in 'a'..'z' }) return@forEachIndexed
            val lb = page.box(i, view.toSource(l.range))
            val h = lb.height
            if (h <= 0f) return@forEachIndexed
            val j = page.texts.indices.filter { it != i && page.texts[it].isNotBlank() }
                .map { it to page.box(it) }
                .filter { (_, b) ->
                    minOf(b.bottom, lb.bottom) - maxOf(b.top, lb.top) >= 0.5f * minOf(b.height, h) &&
                        b.left >= lb.right - 0.2f * h && b.left - lb.right <= ROW_REACH * h
                }
                .minByOrNull { (_, b) -> b.left }?.first ?: return@forEachIndexed
            val t = page.texts[j]
            if (nextField(t)) return@forEachIndexed
            val v = HanView.of(t)
            val start = v.text.indexOfFirst { !it.isWhitespace() }
            valueAt(v.text, start)?.let { out += PageMatch(j, RuleMatch(v.toSource(it), confidence)) }
        }
        return out
    }

    companion object {
        /** 同一排右边那一段最远隔几个字高还算这个字段的值。 */
        private const val ROW_REACH = 10f

        /** 表单上空着的格子、可点的一行里的字：「请输入」「去设置」「修改 >」。 */
        private val PROMPT = Regex("请|点击|选择|输入|填写|未填|未设置|暂无|去设置|去填写|去添加|修改|编辑|添加|查看")

        /**
         * 证件上跟在中文字段名后面的英文译名：驾驶证、行驶证（含 2008 版的「Birthday」「M&F」「Valid From」）、
         * 永居证、出生医学证明、护照资料页。只有这些会被跳过。
         */
        val TRANSLATIONS = listOf(
            "Name", "Full Name", "Surname", "Given Names", "Given Name", "Sex", "M&F", "Gender", "Nationality",
            "Address", "Date of Birth", "Birth Date", "Birthday", "DOB", "Place of Birth", "Date of First Issue",
            "Issue Date", "Date of Issue", "Date of Expiry", "Class", "Valid Period", "Valid From", "Valid For",
            "Period of Validity", "Plate No.", "Vehicle Type", "Owner", "Use Character", "Model", "VIN", "Engine No.",
            "Register Date", "ID No.", "Card No.", "Passport No.", "Holder", "Legal Representative", "Domicile",
        )

        /** 一段译名：前面可隔着空格、斜杠、竖线；词与词之间的空格可有可无（「Date ofBirth」），句点可丢。 */
        private val TRANSLATION_STEP = "(?:[ 　]*[/／|｜]?[ 　]*(?i:" +
            TRANSLATIONS.sortedByDescending { it.length }.joinToString("|") { t ->
                t.split(' ').joinToString("[ ]*") { w -> Regex.escape(w.trimEnd('.')) + if (w.endsWith('.')) "\\.?" else "" }
            } + ")(?![A-Za-z]))"

        /** 字段名后面最多跳两段译名：「Date of Birth/DOB」。 */
        private val TRANSLATED = "$TRANSLATION_STEP{0,2}"

        /** 汉字字段名的字与字之间允许空格（半角、全角都行）。 */
        private fun alternatives(labels: List<String>): String =
            labels.sortedByDescending { it.length }.joinToString("|") { l ->
                if (l.none { it.isHan() }) Regex.escape(l)
                else l.map { Regex.escape(it.toString()) }.joinToString("[ 　]*")
            }
    }
}
