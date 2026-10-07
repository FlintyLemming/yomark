package com.yomark.app.engine.ppocr

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
 */
object WordGaps {

    class Gap(val center: Float, val width: Float)

    class Found(val gaps: List<Gap>, val textHeight: Float)

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

        val column = BooleanArray(width) { x -> (0 until height).any { y -> ink[y * width + x] } }
        val gaps = ArrayList<Gap>()
        var x = 0
        while (x < width) {
            if (column[x]) { x++; continue }
            val start = x
            while (x < width && !column[x]) x++
            // 行首行尾的留白不是词界
            if (start > 0 && x < width) gaps += Gap((start + x) / 2f, (x - start).toFloat())
        }
        return Found(gaps, textHeight)
    }

    /**
     * 相邻两个字之间要不要补空格。
     *
     * @param leftX / rightX 两个字中心的横坐标（与 gaps 同一坐标系）。
     */
    fun isBreak(left: String, right: String, leftX: Float, rightX: Float, found: Found): Boolean {
        if (left == " " || right == " ") return false
        val widest = found.gaps.filter { it.center > leftX && it.center < rightX }.maxOfOrNull { it.width } ?: return false
        val digits = left.singleOrNull()?.isAsciiDigit() == true && right.singleOrNull()?.isAsciiDigit() == true
        return widest >= found.textHeight * if (digits) DIGIT_BREAK else BREAK
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

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
}
