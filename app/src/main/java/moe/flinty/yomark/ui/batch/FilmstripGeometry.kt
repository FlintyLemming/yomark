package moe.flinty.yomark.ui.batch

import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * 胶片条的排法，仿 iOS「照片」看大图时底下那一条：其余各张是一样宽的窄条，停在中间的那张按自己的比例展开、
 * 两边各多让出一点空；滑动时这一张收拢、下一张展开，一路接力，没有跳变。
 *
 * 位置都以像素计，[position] 是停在中线上的是第几张，可以是小数：2.3 = 正从第 2 张滑向第 3 张，滑了三成。
 * 中线上对着的点从第 k 张的中心匀速挪到第 k+1 张的中心，两张展开的程度此消彼长。
 *
 * @param collapsedWidth 窄条的宽
 * @param gap 相邻两条之间的缝
 * @param margin 展开的那一张两边各多让出的空
 */
class FilmstripGeometry(
    val collapsedWidth: Float,
    val gap: Float,
    val margin: Float,
) {
    /** 没有展开的时候，每滑过一张走多少。 */
    val pitch: Float get() = collapsedWidth + gap

    /** 第 [i] 张展开到几成：正停在中线上是 1，离开一整张及以上是 0。 */
    fun expansion(i: Int, position: Float): Float = (1f - abs(i - position)).coerceIn(0f, 1f)

    /** 第 [i] 张画多宽：窄条和它展开后的宽 [expanded] 之间，按展开的程度取。 */
    fun width(i: Int, position: Float, expanded: Float): Float =
        collapsedWidth + (expanded - collapsedWidth) * expansion(i, position)

    /**
     * 第 [i] 张的中心离中线多远，往右为正。
     *
     * 只有夹着 [position] 的两张（k、k+1）在展开，各比窄条多占「展开多出的宽 + 两边的空」乘上展开的程度，
     * 排在它们后面的跟着往后挪；中线对着这两张中心之间按 position 的小数部分取的那一点。
     *
     * @param expandedWidth 第几张展开后多宽
     */
    fun centerOffset(i: Int, position: Float, count: Int, expandedWidth: (Int) -> Float): Float {
        val k = floor(position).toInt()
        val f = position - k
        fun extra(j: Int): Float =
            if (j !in 0 until count) 0f
            else (expandedWidth(j) - collapsedWidth + 2 * margin) * expansion(j, position)
        val ek = extra(k)
        val ek1 = extra(k + 1)
        fun center(j: Int): Float {
            var c = j * pitch + collapsedWidth / 2
            if (j > k) c += ek
            if (j > k + 1) c += ek1
            if (j == k) c += ek / 2
            if (j == k + 1) c += ek1 / 2
            return c
        }
        val anchor = center(k) + f * (center(k + 1) - center(k))
        return center(i) - anchor
    }

    companion object {
        /**
         * 一张图（[imageWidth]×[imageHeight] 像素）在 [cellWidth]×[cellHeight] 的格子里露出哪一块，以图的像素计。
         *
         * 视差：图按比格子宽出 [parallax] 的尺寸铺满，格子像一扇窗，横向多出来的部分随 [shift]（-1 到 1，
         * 格子在条带的最左到最右）从图的左边移到右边。滑动时窗跟着手走、图慢半拍，看起来图在窗后面。
         * [parallax] 为 0 时就是普通的居中裁切；停在中间的那张传 0，看到的是它自己完整的样子。
         */
        fun window(
            imageWidth: Int,
            imageHeight: Int,
            cellWidth: Float,
            cellHeight: Float,
            parallax: Float,
            shift: Float,
        ): Rect {
            val scale = max((cellWidth + parallax) / imageWidth, cellHeight / imageHeight)
            val w = (cellWidth / scale).coerceAtMost(imageWidth.toFloat())
            val h = (cellHeight / scale).coerceAtMost(imageHeight.toFloat())
            val left = (imageWidth - w) * (1f + shift.coerceIn(-1f, 1f)) / 2f
            val top = (imageHeight - h) / 2f
            return Rect(left, top, left + w, top + h)
        }
    }
}
