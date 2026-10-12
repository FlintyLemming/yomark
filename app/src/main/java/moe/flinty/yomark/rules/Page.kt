package moe.flinty.yomark.rules

/**
 * 规则层眼里的一页：每行的文字，和一段字在图上的框。
 *
 * RuleClassifier 从 OCR 的行拼出它（只收置信度够的行）。规则不直接拿 TextLine，是为了不碰 android.graphics：
 * 评测台（tools/model-eval/ocr-dump）在桌面 JVM 上跑同一套规则。
 *
 * @param locate 第 line 行 range 那几个字的外接框。精度随识别器：PP-OCR 到字，ML Kit 到词。
 */
class Page(val texts: List<String>, private val locate: (line: Int, range: IntRange) -> Bounds) {

    fun box(line: Int, range: IntRange = texts[line].indices): Bounds = locate(line, range)
}

/** 图上一个横平竖直的框，像素坐标。 */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
}

/**
 * 一页上的一处：第 [line] 行（Page.texts 的下标）上的 [match]。
 *
 * @param also 同一处落在别的行上的部分：上下两行的车牌，上一行「苏B」、下一行「12345」。
 *   几段合成一个框、出一个候选：分成两块码，放过一块牌就得点两次，导出前还会说「2 个车牌」。
 */
data class PageMatch(val line: Int, val match: RuleMatch, val also: List<Span> = emptyList()) {

    /** 这一处占的每一段：[line] 上的那段在前。 */
    val spans: List<Span> get() = listOf(Span(line, match.range)) + also

    /** 和 [other] 有没有哪一段落在同一行、区间重叠。 */
    fun overlaps(other: PageMatch): Boolean =
        if (also.isEmpty() && other.also.isEmpty()) line == other.line && match.range.overlaps(other.match.range)
        else spans.any { a -> other.spans.any { b -> a.line == b.line && a.range.overlaps(b.range) } }
}

/** 第 [line] 行上的 [range]。 */
data class Span(val line: Int, val range: IntRange)

/**
 * 看整页的认法。逐行的 Finder 看不到的两种：值不在字段名同一行、而在它下面（护照资料页、表单，见 StackedField）；
 * 要拿机读码去对照页上别处的护照号、生日、姓名（见 MrzEcho）。
 *
 * 它也是一种 Finder，能和逐行的认法一起放进 CompositeRule、按先后排优先级；单看一行时它什么也认不出。
 */
interface PageFinder : Finder {

    fun findOnPage(page: Page): List<PageMatch>

    override fun findIn(text: String): List<RuleMatch> = emptyList()
}
