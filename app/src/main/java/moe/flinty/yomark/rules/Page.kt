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
 * 第 [line] 行折下去的那一行：紧挨在它下面（空隙不到一个字高）、字高差不多，左沿在它左沿往左三个字高（首行缩进）
 * 到往右一个字高之间。一句话写不下时，下半句就在这一行（「王小明，男，」下面的「2000年1月1日生。」）。没有就是 null。
 */
internal fun Page.wrapOf(line: Int): Int? {
    val t = texts[line]
    val s = t.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return null
    val above = box(line, s..t.indexOfLast { !it.isWhitespace() })
    val h = above.height
    if (h <= 0f) return null
    return texts.indices.filter { it != line && texts[it].isNotBlank() }
        .map { it to box(it) }
        .filter { (_, b) ->
            b.top >= above.centerY && b.top - above.bottom <= h && kotlin.math.abs(b.height - h) <= 0.4f * h &&
                b.left >= above.left - 3 * h && b.left <= above.left + h
        }
        .minByOrNull { (_, b) -> b.top }?.first
}

/** 一页上的一处：第 [line] 行（Page.texts 的下标）上的 [match]。 */
data class PageMatch(val line: Int, val match: RuleMatch)

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
