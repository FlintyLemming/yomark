package moe.flinty.yomark.engine.ppocr

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一页上文字行的走向，从检测框估出来：竖着拍的证件，一行行字在图上是竖着的。
 *
 * DB 的框是最小外接矩形，倾斜的行也框得正，长边就是行的走向。只看长边超过短边两倍的框（一行字），
 * 单个的字、图标是方的，看不出方向。走向是轴向的（往右读和往左读是同一条轴），在两倍角上求平均。
 *
 * 先找众数再求平均：护照样张上斜压的水印「SPECIMEN」框很长，直接平均会把整页拉歪一两度。
 * 取长度最多的那个方向上下 [WINDOW] 以内的框求平均，窗口外的不算；窗口里不到总长的 [MIN_SHARE]，
 * 说明横的竖的都有、没有一个占多数，看不出来。
 */
internal object TextAxis {

    /** 一个文字行框：长边的走向（度，(−90, 90]）和长边的长度。 */
    private class Line(val box: Box, val angle: Double, val length: Double)

    /**
     * 文字行的走向：度，(−90, 90]，图像坐标 y 朝下，正值是顺时针。离 0 或 ±90 不到 [SNAP] 的取整（竖着的记作 90）。
     * 看不出来时为 null：没有一行像样的字，或者各个方向的行都有。
     */
    fun of(boxes: List<Box>): Float? {
        val lines = lines(boxes)
        val mode = mode(lines) ?: return null
        val near = lines.filter { diff(it.angle, mode) <= WINDOW }
        if (near.sumOf { it.length } < MIN_SHARE * lines.sumOf { it.length }) return null
        val x = near.sumOf { it.length * cos(Math.toRadians(2 * it.angle)) }
        val y = near.sumOf { it.length * sin(Math.toRadians(2 * it.angle)) }
        val axis = Math.toDegrees(atan2(y, x)) / 2
        return when {
            abs(axis) < SNAP -> 0f
            abs(axis) > 90 - SNAP -> 90f
            else -> axis.toFloat()
        }
    }

    /** 拿来试方向的几行：走向在 [axis] 附近、最长的 [SAMPLE] 个框。 */
    fun sample(boxes: List<Box>, axis: Float): List<Box> =
        lines(boxes).filter { diff(it.angle, axis.toDouble()) <= WINDOW }
            .sortedByDescending { it.length }.take(SAMPLE).map { it.box }

    private fun lines(boxes: List<Box>): List<Line> = boxes.mapNotNull { b ->
        val u = b.tr - b.tl
        val v = b.bl - b.tl
        val (long, short) = if (u.length() >= v.length()) u to v.length() else v to u.length()
        val length = long.length().toDouble()
        if (length < 2 * short || length <= 0.0) return@mapNotNull null
        var angle = Math.toDegrees(atan2(long.y.toDouble(), long.x.toDouble()))
        if (angle <= -90) angle += 180
        if (angle > 90) angle -= 180
        Line(b, angle, length)
    }

    /** 众数：哪个框的走向上下 [WINDOW] 以内的框加起来最长。 */
    private fun mode(lines: List<Line>): Double? =
        lines.maxByOrNull { l -> lines.sumOf { if (diff(it.angle, l.angle) <= WINDOW) it.length else 0.0 } }?.angle

    /** 两条轴之间的夹角，0..90。 */
    private fun diff(a: Double, b: Double): Double {
        val d = abs(a - b) % 180
        return if (d > 90) 180 - d else d
    }

    /** 窗口：众数上下这么多度以内的行算同一个走向。拍照时的透视会让同一页的行差出几度。 */
    private const val WINDOW = 15.0

    /** 窗口里的行至少占总长的这么多，才算看得出走向。 */
    private const val MIN_SHARE = 0.6

    /** 离横平竖直不到这么多度的当作横平竖直：正着的截图一个像素都不转。 */
    private const val SNAP = 2.0

    const val SAMPLE = 5
}
