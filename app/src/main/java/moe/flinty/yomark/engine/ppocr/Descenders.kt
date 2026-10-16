package moe.flinty.yomark.engine.ppocr

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * 字母文字（拉丁、西里尔、希腊……）的行，框的下沿挪到下伸的笔画（p g y j q、逗号、括号）下面。
 *
 * DB 检测出的文字核盖着一行字的主体：x 高那一截和上伸的笔画（大写字母、b d h l，满行都是）；
 * 下伸的笔画只零星几个，核往下够不着，外扩之后框的下沿正好落在它们的尖上。实测 Noto Sans、Roboto、Inter 30–48px 的聊天气泡，
 * 框的上沿离字顶 4–9 像素，下沿离下伸的尖 −1.3～+0.2 像素（负的是尖露在框外），遮罩的边一抗锯齿、预览一缩放，
 * 尖就露在码下面；没有下伸笔画的行，下沿离字底 3–5 像素。
 * 方块字（汉字、假名、谚文）的框四边都离字 3–8 像素，全角逗号也有离下沿一两个像素的，只有字母文字的行才挪（[alphabetic]）。
 *
 * 做法（[cover]）：从框里离下沿 [MARGIN] 个字高的那一排往下走，跟着连着的墨走到它的最低点；
 * 下沿离最低点不到 [MARGIN] 个字高的，挪到最低点下面 [MARGIN] 个字高，同没有下伸笔画的行一样留着边。
 * 出了框只跟连着的墨走，下一行的字、气泡的边连不上，不算；走出 [REACH] 个字高还没走完的不是下伸的笔画
 * （字压在一块深色上，或者是下一行的字伸进了这一行的框），原样不动。
 *
 * 只改出来的框（行、字、段），识别切图仍按检测的框：切得高了字会变小，读法跟着变。
 */
internal object Descenders {

    /**
     * [box] 是在 [image] 上读出 [text] 的框（角按阅读方向排，下沿是 bl→br），[gray] 是从它切出来识别的灰度图（0..1），
     * 拿来定纸和墨的亮度。返回下沿挪好了的框；不用挪的原样给回。
     */
    fun cover(image: Pixels, box: Box, text: String, gray: FloatArray): Box {
        if (!alphabetic(text)) return box
        val h = box.height
        if (h < 1f) return box
        val ink = Levels.of(gray) ?: return box
        val n = ceil(box.width).toInt().coerceAtLeast(1)
        val margin = MARGIN * h
        val first = floor(h - margin).toInt().coerceAtLeast(0)
        val last = ceil(h + REACH * h).toInt()
        var live = BooleanArray(n)
        var lowest = -1
        var ended = false
        for (r in first..last) {
            val t = r + 0.5f
            val inside = t < h
            val row = BooleanArray(n) { i ->
                // 框的坐标按像素边算，采样按像素中心
                val p = box.at((i + 0.5f) / n, t / h)
                ink.inked(image.sample(p.x - 0.5f, p.y - 0.5f))
            }
            // 框里的墨都算；出了框，只算挨着上一排的
            val next = BooleanArray(n) { i -> row[i] && (inside || live[i] || (i > 0 && live[i - 1]) || (i < n - 1 && live[i + 1])) }
            // 一排里连着的墨一起算：g j y 的尾巴往旁边弯
            for (i in 1 until n) if (row[i] && next[i - 1]) next[i] = true
            for (i in n - 2 downTo 0) if (row[i] && next[i + 1]) next[i] = true
            if (next.any { it }) lowest = r
            else if (!inside) { ended = true; break }
            live = next
        }
        if (!ended || lowest < 0) return box
        val bottom = lowest + 1 + margin
        if (bottom <= h) return box
        val k = bottom / h
        return Box(box.tl, box.tr, box.tr + (box.br - box.tr) * k, box.tl + (box.bl - box.tl) * k)
    }

    /** 有字母文字的字母：汉字、假名、谚文、注音以外的字母。数字、标点不算，「13800138000」「5-1-0307」不挪。 */
    fun alphabetic(text: String): Boolean = text.any { it.isLetter() && Character.UnicodeScript.of(it.code) !in SQUARE }

    private val SQUARE = setOf(
        Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO,
    )

    /**
     * 一行的纸和墨（0..255）：灰度中位数是纸，离它远的那一头（最暗或最亮的 2%）是墨，白底黑字、蓝底白字都认得。
     * 和纸差过两者之差 [INK] 的算墨，抗锯齿的淡边也算上。墨和纸差不到 [MIN_CONTRAST] 的看不出，为 null。
     */
    private class Levels(val paper: Float, val threshold: Float) {

        fun inked(c: Color): Boolean = abs(c.luma - paper) >= threshold

        companion object {
            fun of(gray: FloatArray): Levels? {
                if (gray.isEmpty()) return null
                val bins = IntArray(BINS)
                gray.forEach { bins[(it.coerceIn(0f, 1f) * (BINS - 1)).toInt()]++ }
                fun at(rank: Int): Float {
                    var acc = 0
                    for (i in bins.indices) {
                        acc += bins[i]
                        if (acc > rank) return i / (BINS - 1f)
                    }
                    return 1f
                }
                val paper = at(gray.size / 2)
                val dark = at(gray.size / 50)
                val light = at(gray.size - 1 - gray.size / 50)
                val text = if (paper - dark >= light - paper) dark else light
                val contrast = abs(text - paper)
                if (contrast < MIN_CONTRAST) return null
                return Levels(paper * 255f, INK * contrast * 255f)
            }
        }
    }

    /** 下沿留在最低的墨下面这么多个字高（框高）：没有下伸笔画的行，下沿离字底 0.08–0.11 个框高。 */
    const val MARGIN = 0.08f

    /** 出了框最多跟着墨走这么多个字高。下伸的笔画在基线下 0.2 个框高上下，框的下沿不会比基线还高。 */
    private const val REACH = 0.5f

    private const val INK = 0.3f
    private const val MIN_CONTRAST = 0.2f
    private const val BINS = 256
}
