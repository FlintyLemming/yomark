package moe.flinty.yomark.engine.ppocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 把版面上的间隙补回成空格。
 *
 * PP-OCR 的识别模型几乎不输出空格：「待取件 09-29 12:30」读出来是「待取件09-2912:30」，
 * 「沐晨冉 86-186****3392」读出来是「沐晨冉86-186****3392」。规则层靠空格判断字段边界
 * （时间前面不能紧贴数字、名字要是一个独立的词），不补回来就会漏。
 *
 * CTC 给出的字符位置太粗（一个时间步 8 像素，峰值还会漂），靠它量间距误判很多。
 * 这里直接看像素：识别用的那条 48 像素高的灰度图里，找整列都没有墨迹的连续列，
 * 宽过字高的 0.3 倍就是词界。
 *
 * 例外是两个数字之间：「1」这种窄字形两边留白很宽，0.3 倍会把「101」拆成「1 01」，
 * 数字串被拆开比少一个空格危险得多（卡号、电话规则都要求数字连续），所以要宽过 0.45 倍。
 *
 * 拉丁字母另有一套（「混合空格」，docs/english-recognition-research.md 第二轮）：0.3 倍在英文上补多了，
 * 字距宽的全大写字被拆开——网页上的「P L A C E OR D E R」，护照上的「TRAVE L E R」，
 * 机读码整行成了「P< U S A T R A V E L E R < <」。英文的词界识别模型自己会出空格，按像素补只是兜底：
 * 两个字母、字母和数字之间要宽过 0.6 倍，挨着 ASCII 标点的不补（「187****9157」「3-2-1234」不再被拆开）。
 * 两侧有汉字、全角标点的照旧。72 张压力页上英文的关键片段 94.2% → 97.6%，中文不变。
 */
object WordGaps {

    class Gap(val center: Float, val width: Float)

    /** @param columns 每一列上有几个墨迹像素（整列从上到下数），[boundary] 用它找字和字之间的空当 */
    class Found(val gaps: List<Gap>, val textHeight: Float, val columns: IntArray = IntArray(0))

    /** @param gray 行主序的灰度图，取值 0..1。 */
    fun find(gray: FloatArray, width: Int, height: Int): Found {
        val bg = median(gray)
        val ink = BooleanArray(gray.size) { kotlin.math.abs(gray[it] - bg) > INK_CONTRAST }

        var top = -1
        var bottom = -1
        for (y in 0 until height) {
            var any = false
            for (x in 0 until width) if (ink[y * width + x]) { any = true; break }
            if (any) { if (top < 0) top = y; bottom = y }
        }
        val textHeight = if (top < 0) height.toFloat() else (bottom - top + 1).toFloat()

        val columns = IntArray(width) { x -> (0 until height).count { y -> ink[y * width + x] } }
        val gaps = ArrayList<Gap>()
        var x = 0
        while (x < width) {
            if (columns[x] > 0) { x++; continue }
            val start = x
            while (x < width && columns[x] == 0) x++
            // 行首行尾的留白不是词界
            if (start > 0 && x < width) gaps += Gap((start + x) / 2f, (x - start).toFloat())
        }
        return Found(gaps, textHeight, columns)
    }

    /**
     * 两个相邻的字之间的分界（与 gaps 同一坐标系），字框就切在这儿：大爆炸按字打码、规则遮一段字，码的边都落在它上面。
     *
     * CTC 给的字的位置太粗（见类注释），取两个字中心的中点，一半以上的分界落在笔画上：「号码保护中」整行往右偏七八个像素，
     * 只遮「保」会露出它的单人旁、盖住「护」的提手旁。所以落到像素上找：两个中心之间整列都没有墨迹的空当，
     * 取正中离中点最近的那一段的正中；字挨着字、一列空当也没有（小字号的截图、字距紧的拉丁字母），
     * 取中间那一半里墨迹最少的那一列，一样少的取离中点近的。
     *
     * 合成的字行上（有真值）落在笔画上的分界 32% → 3%；真实截图上 11 张图的规则结果一处不多一处不少，只是码的边往空当里挪。
     * 中心偏了三分之一个字以上时会挑错空当（一串窄字「****5」、拉丁字母连写的 ff），约 1%。
     *
     * @param leftX / rightX 两个字中心的横坐标，[leftX] < [rightX]
     */
    fun boundary(leftX: Float, rightX: Float, found: Found): Float {
        val col = found.columns
        val mid = (leftX + rightX) / 2
        if (col.isEmpty()) return mid
        val lo = ceil(leftX).toInt().coerceIn(0, col.size)
        val hi = floor(rightX).toInt().coerceIn(0, col.size)
        var best = Float.NaN
        var bestDistance = Float.MAX_VALUE
        var x = lo
        while (x < hi) {
            if (col[x] > 0) { x++; continue }
            val start = x
            while (x < hi && col[x] == 0) x++
            val center = (start + x) / 2f
            val distance = abs(center - mid)
            if (distance < bestDistance) { bestDistance = distance; best = center }
        }
        if (!best.isNaN()) return best

        val quarter = (rightX - leftX) / 4
        val from = ceil(leftX + quarter).toInt().coerceAtLeast(0)
        val to = floor(rightX - quarter).toInt().coerceAtMost(col.size - 1)
        if (from > to) return mid
        var thinnest = from
        for (c in from..to) {
            val better = col[c] < col[thinnest] ||
                (col[c] == col[thinnest] && abs(c + 0.5f - mid) < abs(thinnest + 0.5f - mid))
            if (better) thinnest = c
        }
        return thinnest + 0.5f
    }

    /**
     * 相邻两个字之间要不要补空格。
     *
     * @param leftX / rightX 两个字中心的横坐标（与 gaps 同一坐标系）。
     */
    fun isBreak(left: String, right: String, leftX: Float, rightX: Float, found: Found): Boolean {
        if (left == " " || right == " ") return false
        val widest = found.gaps.filter { it.center > leftX && it.center < rightX }.maxOfOrNull { it.width } ?: return false
        val l = left.singleOrNull()
        val r = right.singleOrNull()
        val threshold = when {
            l == null || r == null || l.code > 0x7F || r.code > 0x7F -> BREAK
            l.isAsciiDigit() && r.isAsciiDigit() -> DIGIT_BREAK
            !l.isAsciiLetterOrDigit() || !r.isAsciiLetterOrDigit() -> return false
            else -> LATIN_BREAK
        }
        return widest >= found.textHeight * threshold
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
    private fun Char.isAsciiLetterOrDigit() = isAsciiDigit() || this in 'a'..'z' || this in 'A'..'Z'

    /** 背景色：截图是大块纯色底，灰度中位数就是底色。直方图求近似，免得整行排序。 */
    private fun median(gray: FloatArray): Float {
        val bins = IntArray(BINS)
        gray.forEach { bins[(it.coerceIn(0f, 1f) * (BINS - 1)).toInt()]++ }
        var acc = 0
        for (i in bins.indices) {
            acc += bins[i]
            if (acc * 2 >= gray.size) return i / (BINS - 1f)
        }
        return 1f
    }

    private const val BINS = 256
    private const val INK_CONTRAST = 0.25f
    private const val BREAK = 0.3f
    private const val DIGIT_BREAK = 0.45f
    private const val LATIN_BREAK = 0.6f
}
