package moe.flinty.yomark.rules

/**
 * 规则层眼里的一页：每行的文字，和一段字在图上的框。
 *
 * RuleClassifier 从 OCR 的行拼出它（只收置信度够的行）。规则不直接拿 TextLine，是为了不碰 android.graphics：
 * 评测台（tools/model-eval/ocr-dump）在桌面 JVM 上跑同一套规则。
 *
 * @param turned 自己的阅读方向和这一页的差得多的那几行（截图里一张转过、拍歪了的小照片上的字，字少，RuleClassifier 不单分一组）：
 *   第几行 → 按那一行自己的方向怎么量，同 [locate]。见 [boxIn]。
 * @param locate 第 line 行 range 那几个字的外接框。精度随识别器：PP-OCR 到字，ML Kit 到词。
 */
class Page(
    val texts: List<String>,
    private val turned: Map<Int, (line: Int, range: IntRange) -> Bounds> = emptyMap(),
    private val locate: (line: Int, range: IntRange) -> Bounds,
) {

    fun box(line: Int, range: IntRange = texts[line].indices): Bounds = locate(line, range)

    /** 第 [ref] 行自己的阅读方向和这一页的不一样（见 [boxIn]）。 */
    fun isTurned(ref: Int): Boolean = ref in turned

    /**
     * 按第 [ref] 行自己的阅读方向量的框：照片上的「下面一行」「旁边」按照片上的字的方向看，按整页的方向看全对不上。
     * 只看一小块地方的认法（上下两行的车牌、孤零零一行的车牌）拿它量。[ref] 的方向同这一页的，就是 [box]。
     */
    fun boxIn(line: Int, range: IntRange = texts[line].indices, ref: Int): Bounds =
        turned[ref]?.invoke(line, range) ?: locate(line, range)
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

/**
 * 第 [line] 行里的一段段字：字与字之间的空白宽过一个字高（按两边高的那个）就断开，同 StackedField 分栏。
 * RowGrouper 拼成一行的几段之间只补了一个空格，按框断开。
 */
internal fun Page.cells(line: Int): List<IntRange> {
    val text = texts[line]
    val out = ArrayList<IntRange>()
    var start = -1
    var end = -1
    var right = 0f
    var height = 0f
    for (k in text.indices) {
        if (text[k].isWhitespace()) continue
        val b = box(line, k..k)
        if (start >= 0 && b.left - right > CELL_GAP * kotlin.math.max(height, b.height)) {
            out += start..end
            start = -1
        }
        if (start < 0) {
            start = k
            right = b.right
            height = b.height
        } else {
            right = kotlin.math.max(right, b.right)
            height = kotlin.math.max(height, b.height)
        }
        end = k
    }
    if (start >= 0) out += start..end
    return out
}

/** 一段里两个字之间最宽的空白，按字高算。 */
private const val CELL_GAP = 1f

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
