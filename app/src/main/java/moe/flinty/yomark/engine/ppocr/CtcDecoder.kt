package moe.flinty.yomark.engine.ppocr

import java.nio.FloatBuffer

/**
 * 识别头的 CTC 贪心解码。
 *
 * PP-OCRv5 的类别表：0 是 blank，1..N 是字典里的字，N+1 是空格（use_space_char）。
 * 每个时间步取 argmax，连续相同的合并，blank 丢掉。
 *
 * 除了字本身，还记下每个字占了哪几个时间步——字在行里的横向位置由它推出来，
 * 字符级的框、词界的判断都靠这个。
 */
object CtcDecoder {

    class Decoded(
        val chars: List<String>,
        /** chars[i] 占的时间步区间。 */
        val steps: List<IntRange>,
        /** 保留下来的字的平均概率，即 PaddleOCR 的行置信度。 */
        val confidence: Float,
    )

    /** @param out 形状 [steps, classes] 的行主序输出。 */
    fun decode(out: FloatBuffer, steps: Int, classes: Int, dict: List<String>): Decoded {
        require(classes == dict.size + 2) { "模型输出 $classes 类，字典 ${dict.size} 字，对不上" }
        val chars = ArrayList<String>()
        val ranges = ArrayList<IntRange>()
        var probSum = 0.0
        var prev = 0
        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestP = out.get(base)
            for (k in 1 until classes) {
                val p = out.get(base + k)
                if (p > bestP) { bestP = p; best = k }
            }
            if (best != 0 && best == prev) {
                ranges[ranges.lastIndex] = ranges.last().first..t
            } else if (best != 0) {
                chars += if (best == classes - 1) " " else dict[best - 1]
                ranges += t..t
                probSum += bestP
            }
            prev = best
        }
        val conf = if (chars.isEmpty()) 0f else (probSum / chars.size).toFloat()
        return Decoded(chars, ranges, conf)
    }
}
