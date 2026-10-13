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
 * 字段名和值之间可以隔着冒号、等号，或者表格的竖线、斜杠（「收货地址 | 朝阳公园南门东侧小区」）。
 *
 * 证件上的字段名常带英文（驾驶证「姓名 Name」、永居证「姓名/Name」、出生医学证明「出生时间 Date of Birth」）：
 * 字段名后面的英文译名跳过去再取值，只跳 [Translations] 里的——值本身也可能是拉丁字母（「ZHANG SAN」）。
 *
 * @param value 值的形状。姓名与地址差别很大（地址必须含数字，姓名遇数字就该停），
 *   所以不在这里写死，由调用方给。
 * @param ignoreCase 字段名不分大小写：英文证件上是「DATE OF BIRTH」，表单上是「Date Of Birth」。值的形状照旧分。
 * @param spaced 也是常用词的字段名（「所有人都到了」「住所地人民法院」「出生地是哪儿」）：后面要隔着冒号、空格
 *   或英文译名才算，紧接着字的是一句话。它们领着的值还要过 [spacedValue]。
 * @param nextField 同一排右边那一段以别的字段名打头，就不是这个字段的值（见 [findOnPage]）。
 * @param valid 值还要过的一道：生日要是日历上有的一天。
 * @param spacedValue [spaced] 里的字段名领着的值还要过的一道，收下视图上的整行和值的区间。
 * @param bareRow 字段名没带英文译名，也接同一排右边那一段（见 [findOnPage]）：值的形状够严的才这样（证件号码要五位以上数字），
 *   表头的下一格、「去设置 >」都对不上。
 */
class LabeledField(
    labels: List<String>,
    private val value: Regex,
    private val confidence: Float,
    private val ignoreCase: Boolean = false,
    spaced: List<String> = emptyList(),
    private val nextField: (String) -> Boolean = { false },
    private val valid: (String) -> Boolean = { true },
    private val spacedValue: (line: String, value: IntRange) -> Boolean = { _, _ -> true },
    private val bareRow: Boolean = false,
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
        "(?<![\\u4e00-\\u9fa5A-Za-z0-9@])(?" + (if (ignoreCase) "i" else "") + ":" + alternatives(labels + spaced) + ")"
    )

    private val spacedKeys = spaced.map { key(it, ignoreCase) }.toSet()

    private fun isSpaced(l: MatchResult) = spacedKeys.isNotEmpty() && key(l.value, ignoreCase) in spacedKeys

    override fun findIn(text: String): List<RuleMatch> {
        val view = HanView.of(text)
        return labelsIn(view.text)
            .mapNotNull { (l, a) -> valueAfter(view.text, l, a) }
            .map { RuleMatch(view.toSource(it), confidence) }
    }

    /**
     * 一行里的字段名，各带着它后面跳过的译名、分隔符。下一个从跳过的后面找起：「姓名 Name」里的「Name」
     * 是译名，不再当一个字段名。
     */
    private fun labelsIn(text: String): List<Pair<MatchResult, After>> {
        val out = ArrayList<Pair<MatchResult, After>>()
        var from = 0
        while (from < text.length) {
            val l = label.find(text, from) ?: break
            val a = after(text, l.range.last + 1)
            out += l to a
            from = a.end
        }
        return out
    }

    /** 字段名 [l] 后面的值。 */
    private fun valueAfter(text: String, l: MatchResult, a: After): IntRange? {
        if (isSpaced(l) && !a.gap) return null
        // 「Name/姓名 张三」：斜杠后面又是一个字段名，值归它（接着就认到它）
        if (label.matchAt(text, a.end) != null) return null
        return valueAt(text, a.end, isSpaced(l))
    }

    /**
     * 从 [start] 起的值。表单上没填的格子里是提示语（「请输入」「请填写详细地址」），不是值。
     * [spacedLabel]：领着它的是 [spaced] 里的字段名。
     */
    private fun valueAt(text: String, start: Int, spacedLabel: Boolean): IntRange? {
        val r = value.matchAt(text, start)?.range ?: return null
        if (PROMPT.matchAt(text, r.first) != null || !valid(text.substring(r))) return null
        if (spacedLabel && !spacedValue(text, r)) return null
        return r
    }

    /** 字段名之后：跳过的译名、分隔符停在 [end]；[translated] 跳过了译名；[gap] 字段名和值之间隔着东西。 */
    private class After(val end: Int, val translated: Boolean, val gap: Boolean)

    private fun after(text: String, from: Int): After {
        val (p, n) = Translations.skip(text, from)
        val end = SEPARATOR.matchAt(text, p)?.range?.let { it.last + 1 } ?: p
        return After(end, n > 0, end > from)
    }

    /**
     * 逐行认的那些，再加上字段名独占一段、值在同一排右边另一段的：驾驶证、行驶证上值在右边一栏，
     * 「姓名 Name」和「周以宁」之间隔着大半栏，OCR 拆成了两行。
     *
     * 只在中文字段名自己带着英文译名时这样接（「姓名 Name」「Name/姓名」，或者译名单独成一段：「姓名」|「Name」|「周以宁」），
     * 那是证件的排版。单单一个「姓名」，右边隔得老远的可能是表头的下一格（「姓名　学号　成绩」）；
     * 「收货地址：」右边可能是「去设置 >」；设置页的「IP 地址」「Email 地址」右边是 IP 和邮箱——那一段里的英文不是译名。
     * 值的形状够严的（[bareRow]）不管这些：驾驶证上的「证号」没有译名，号码在右边一栏。
     *
     * 右边那一段从它的头一个字起要像值；以别的字段名打头的（[nextField]），或者本身就是一个中英文字段名的
     * （空着没填的双语表单：「姓名 Name」右边是「房号 Room No.」），不是值。
     */
    override fun findOnPage(page: Page): List<PageMatch> {
        val out = page.texts.indices.flatMap { i -> findIn(page.texts[i]).map { PageMatch(i, it) } }.toMutableList()
        page.texts.forEachIndexed { i, text ->
            val view = HanView.of(text)
            val (l, a) = labelsIn(view.text).lastOrNull() ?: return@forEachIndexed
            if (l.value.none { it.isHan() }) return@forEachIndexed
            if (view.text.substring(a.end).isNotBlank()) return@forEachIndexed
            val translated = a.translated || translatedBefore(view.text, l.range.first)
            val h = page.box(i, view.toSource(l.range)).height
            if (h <= 0f) return@forEachIndexed
            // 从这一段（字段名连同译名）的右沿量起
            var near = page.box(i)
            var j = rightOf(page, i, near, h) ?: return@forEachIndexed
            var v = HanView.of(page.texts[j])
            var start = v.text.indexOfFirst { !it.isWhitespace() }
            val (end, n) = if (translated) start to 0 else Translations.skip(v.text, start)
            if (!translated && n == 0 && !bareRow) return@forEachIndexed
            if (n > 0) {
                // 译名单独成一段：跳过它，值在它后面，或者在它右边的下一段
                if (v.text.substring(end).isBlank()) {
                    near = page.box(j)
                    j = rightOf(page, j, near, h) ?: return@forEachIndexed
                    v = HanView.of(page.texts[j])
                    start = v.text.indexOfFirst { !it.isWhitespace() }
                } else start = SEPARATOR.matchAt(v.text, end)?.range?.let { it.last + 1 } ?: end
            }
            val rest = v.text.substring(start)
            if (nextField(rest) || isBilingualLabel(rest.trim()) || label.matchAt(v.text, start) != null) return@forEachIndexed
            valueAt(v.text, start, isSpaced(l))?.let { out += PageMatch(j, RuleMatch(v.toSource(it), confidence)) }
        }
        return out
    }

    /** 同一排、在 [near] 右边最近的一段（第 [i] 段自己除外）。OCR 的框常常咬住一截，看中线在不在它右边。 */
    private fun rightOf(page: Page, i: Int, near: Bounds, h: Float): Int? =
        page.texts.indices.filter { it != i && page.texts[it].isNotBlank() }
            .map { it to page.box(it) }
            .filter { (_, b) ->
                minOf(b.bottom, near.bottom) - maxOf(b.top, near.top) >= 0.5f * minOf(b.height, near.height) &&
                    b.centerX > near.right && b.left - near.right <= ROW_REACH * h
            }
            .minByOrNull { (_, b) -> b.left }?.first

    /** 「Name/姓名」：字段名前面这一段整个是译名。 */
    private fun translatedBefore(text: String, labelStart: Int): Boolean {
        val (end, n) = Translations.skip(text, 0)
        if (n == 0) return false
        val sep = SEPARATOR.matchAt(text, end)?.range?.let { it.last + 1 } ?: end
        return sep == labelStart
    }

    /**
     * 证件上跟在中文字段名后面的英文译名：驾驶证、行驶证（含 2008 版的「Birthday」「M&F」「Valid From」）、
     * 永居证、出生医学证明、护照资料页。只跳这些，值本身也可能是拉丁字母。
     *
     * OCR 常读错一个字母（「Adress」「Date of Brith」「Valid Periad」），「m」常读成「rn」（「Narne」）：
     * 七个字母以上的译名差一个字母（多、少、换一个、相邻两个对调）也算；短的只认「rn」，「Nana」「Bob」是名字。
     * 全大写的缩写（VIN、DOB、M&F）只认大写：「姓名 Vin Diesel」里的「Vin」是名字。
     * 两个字母的「No.」要带着句点：「NO」单独是个姓（韩国的노）。
     * 译名可以括在括号里：「车辆识别代号(VIN)」。
     */
    internal object Translations {

        val ALL = listOf(
            "Name", "Full Name", "Surname", "Given Names", "Given Name", "Sex", "M&F", "Gender", "Nationality",
            "Address", "Date of Birth", "Birth Date", "Birthday", "DOB", "Place of Birth", "Date of First Issue",
            "Issue Date", "Date of Issue", "Date of Expiry", "Class", "Valid Period", "Valid From", "Valid For",
            "Period of Validity", "Plate No.", "Vehicle Type", "Owner", "Use Character", "Model", "VIN", "Engine No.",
            "Register Date", "ID No.", "Card No.", "Passport No.", "Holder", "Legal Representative", "Domicile",
            // 驾驶证「证号」的译名（翻译件、部分卡面）
            "No.",
        )

        /**
         * 去掉空格、句点，小写；打头是全大写的缩写的（「VIN」「ID No.」），记下它有几个字母，这几个只认大写；
         * [dotted]：句点不能少。长的在前。
         */
        private class Key(val compact: String, val upperHead: Int, val dotted: Boolean)

        private val KEYS = ALL.map { t ->
            val head = t.substringBefore(' ').trimEnd('.')
            val acronym = head.length <= 3 && head.none { it.isLowerCase() }
            val compact = t.filter { it.isLetter() || it == '&' }.lowercase()
            Key(compact, if (acronym) head.length else 0, dotted = compact.length <= 2 && t.endsWith('.'))
        }.sortedByDescending { it.compact.length }

        /** 七个字母以上的译名才容一个字母的错。 */
        private const val FUZZY_FROM = 7

        /**
         * 从 [from] 起至多跳过两段译名（「Date of Birth/DOB」），返回跳过之后的下标和跳过了几段。
         * 每段前面可隔着空格、斜杠、竖线；斜杠常被读成 1、l、I（「姓名1Name」）。
         */
        fun skip(text: String, from: Int): Pair<Int, Int> {
            var p = from
            var n = 0
            while (n < 2) {
                p = step(text, p) ?: break
                n++
            }
            return p to n
        }

        private fun step(text: String, from: Int): Int? {
            val p = spaces(text, from)
            val c = text.getOrNull(p)
            if (c != null && c in "/／|｜1lI") at(text, spaces(text, p + 1))?.let { return it }
            if (c != null && c in "(（") at(text, spaces(text, p + 1))?.let { e ->
                val q = spaces(text, e)
                if (text.getOrNull(q)?.let { it in ")）" } == true) return q + 1
            }
            return at(text, p)
        }

        private fun spaces(text: String, from: Int): Int {
            var p = from
            while (p < text.length && text[p] in " 　") p++
            return p
        }

        /** 从 [p] 起是一段译名就返回它后面的下标。 */
        private fun at(text: String, p: Int): Int? {
            if (p >= text.length || !text[p].isLatin()) return null
            // 一串拉丁字母，词与词之间的空格、句点不算（「Date ofBirth」「Plate No」）；ends[k] 是第 k 个字母后面的下标
            val letters = StringBuilder()
            val ends = ArrayList<Int>()
            var k = p
            while (k < text.length && letters.length < 32) {
                val c = text[k]
                when {
                    c.isLatin() || c == '&' -> { letters.append(c); ends += k + 1 }
                    c == ' ' || c == '.' -> Unit
                    else -> break
                }
                k++
            }
            val lower = letters.toString().lowercase()
            val upper = letters.toString()
            KEYS.forEach { key -> length(lower, upper, key, ends, text, fuzzy = false)?.let { return it } }
            KEYS.filter { it.compact.length >= FUZZY_FROM }
                .forEach { key -> length(lower, upper, key, ends, text, fuzzy = true)?.let { return it } }
            return null
        }

        /** [key] 对上了开头那几个字母就返回它后面的下标（句点算进来）：后面不能紧跟着字母。 */
        private fun length(lower: String, upper: String, key: Key, ends: List<Int>, text: String, fuzzy: Boolean): Int? {
            val n = key.compact.length
            for (len in (n + 1) downTo maxOf(1, n - 1)) {
                if (len > lower.length) continue
                val end = ends[len - 1]
                if (end < text.length && text[end].isLatin()) continue
                val got = lower.substring(0, len)
                val ok = listOf(got, got.replace("rn", "m")).any { if (fuzzy) withinOne(it, key.compact) else it == key.compact }
                if (!ok) continue
                if (upper.substring(0, minOf(len, key.upperHead)).any { it.isLowerCase() }) continue
                val dot = end < text.length && text[end] == '.'
                if (key.dotted && !dot) continue
                return if (dot) end + 1 else end
            }
            return null
        }

        /** 差至多一个字母：多一个、少一个、换一个，或者相邻两个对调。 */
        private fun withinOne(a: String, b: String): Boolean {
            if (a == b) return true
            if (a.length == b.length) {
                val diff = a.indices.filter { a[it] != b[it] }
                return diff.size == 1 ||
                    (diff.size == 2 && diff[1] == diff[0] + 1 && a[diff[0]] == b[diff[1]] && a[diff[1]] == b[diff[0]])
            }
            val (s, l) = if (a.length < b.length) a to b else b to a
            if (l.length - s.length != 1) return false
            val i = s.indices.firstOrNull { s[it] != l[it] } ?: return true
            return s.substring(i) == l.substring(i + 1)
        }

        private fun Char.isLatin() = this in 'A'..'Z' || this in 'a'..'z'
    }

    companion object {
        /** 同一排右边那一段最远隔几个字高还算这个字段的值。 */
        private const val ROW_REACH = 10f

        /** 表单上空着的格子、可点的一行里的字：「请输入」「去设置」「修改 >」。 */
        private val PROMPT = Regex("请|点击|选择|输入|填写|未填|未设置|暂无|去设置|去填写|去添加|修改|编辑|添加|查看")

        /** 字段名和值之间：冒号、等号，表格的竖线、斜杠，前后的空格。 */
        private val SEPARATOR = Regex("[ 　]*[：:＝=/／|｜]?[ 　]*")

        /** 几个汉字，可隔斜杠，后面一串英文。 */
        private val HAN_THEN_LATIN = Regex("""[\u4e00-\u9fa5][\u4e00-\u9fa5 　]{0,9}[/／|｜]?[ 　]*([A-Za-z][A-Za-z&.' ]*)""")

        /**
         * 一段本身就是中英文字段名：汉字后面跟着英文，英文是译名，或者带小写字母（「房号 Room No.」「年龄 Age」）。
         * 值里的拼音是全大写的，「张三 ZHANG SAN」不算。
         */
        private fun isBilingualLabel(s: String): Boolean {
            val m = HAN_THEN_LATIN.matchEntire(s) ?: return false
            val latin = m.groups[1]!!
            return latin.value.any { it.isLowerCase() } || Translations.skip(s, latin.range.first).first == s.length
        }

        /** 汉字字段名的字与字之间允许空格（半角、全角都行）。 */
        private fun alternatives(labels: List<String>): String =
            labels.sortedByDescending { it.length }.joinToString("|") { l ->
                if (l.none { it.isHan() }) Regex.escape(l)
                else l.map { Regex.escape(it.toString()) }.joinToString("[ 　]*")
            }

        /** 认出来的字段名（可能夹着空格）对回字段名表。 */
        private fun key(label: String, ignoreCase: Boolean): String =
            label.filterNot { it == ' ' || it == '　' }.let { if (ignoreCase) it.lowercase() else it }
    }
}
