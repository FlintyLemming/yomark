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
 * 先找众数（哪个方向上下 [TIGHT] 以内的行最长），只在众数附近求平均：差二三十度的两组行（字和水印）
 * 不会被平均成一个谁都不是的角度。众数是竖着的（离竖直不到 [WINDOW]），就是它：不转根本读不出来，
 * 别的行在原图上照常读（PpOcrEngine）。众数是横的、斜的，还有几条防着页上的「别的字」把整页带歪：
 * - 横平或竖直的行占总长的 [LEVEL_SHARE] 以上，就是横平或竖直的。截图、扫描件上的字横平竖直，
 *   斜着平铺的水印（「仅供办理银行开户使用」）往往比证件上的字还长；
 * - 众数上下 [WINDOW] 以内不到总长的 [MIN_SHARE]，说明横的竖的斜的都有、没有一个占多数，看不出来；
 * - 歪着的页（众数离横平不到 45°）上，众数以外的行还占 [OTHER_SHARE] 以上，当它横平：歪了的证件上压着水印，
 *   转到水印的方向，证件上的字反倒更歪。
 */
internal object TextAxis {

    /** 一个文字行框：长边的走向（度，(−90, 90]）和长边的长度。 */
    private class Line(val index: Int, val angle: Double, val length: Double)

    /**
     * 文字行的走向：度，(−90, 90]，图像坐标 y 朝下，正值是顺时针。离 0 或 ±90 不到 [SNAP] 的取整（竖着的记作 90）。
     * 看不出来时为 null：没有一行像样的字，或者各个方向的行都有。
     */
    fun of(boxes: List<Box>): Float? {
        val lines = lines(boxes)
        val total = lines.sumOf { it.length }
        if (total <= 0.0) return null
        val mode = mode(lines) ?: return null
        // 竖着的一组最长（截图里一张竖着放的证件照）：不转读不出来，横着的那些在原图上照常读（PpOcrEngine），下面几条都不用看
        if (diff(mode, 90.0) > WINDOW) {
            val level = lengthNear(lines, 0.0, SNAP)
            val upright = lengthNear(lines, 90.0, SNAP)
            if (maxOf(level, upright) >= LEVEL_SHARE * total) return if (level >= upright) 0f else 90f
            val group = lengthNear(lines, mode, WINDOW)
            if (group < MIN_SHARE * total) return null
            // 歪着拍的页上另有一组斜着的行（压在证件上的水印）也不少：转到哪一组，另一组都歪着，不如照常读——逐框切图本来就会摆正
            if (abs(mode) <= 45 && total - group >= OTHER_SHARE * total) return 0f
        }
        val near = lines.filter { diff(it.angle, mode) <= TIGHT }
        val x = near.sumOf { it.length * cos(Math.toRadians(2 * it.angle)) }
        val y = near.sumOf { it.length * sin(Math.toRadians(2 * it.angle)) }
        val axis = Math.toDegrees(atan2(y, x)) / 2
        return when {
            abs(axis) < SNAP -> 0f
            abs(axis) > 90 - SNAP -> 90f
            else -> axis.toFloat()
        }
    }

    /** 拿来试方向的几行：走向在 [axis] 上下 [TIGHT] 以内、最长的 [SAMPLE] 个框，给的是下标。 */
    fun sample(boxes: List<Box>, axis: Float): List<Int> =
        lines(boxes).filter { diff(it.angle, axis.toDouble()) <= TIGHT }
            .sortedByDescending { it.length }.take(SAMPLE).map { it.index }

    /** 一个框和走向的关系：转正了读的时候，哪些框在画布上读、哪些留在原图上读（PpOcrEngine.readTurned）。 */
    enum class Fit {
        /** 顺着走向的一行。 */
        ALONG,

        /** 和走向垂直的一行：竖着放的证件旁边正着的聊天记录、表单。 */
        CROSS,

        /** 斜着的行或方框：水印、拍歪了的另一张纸。 */
        OTHER,

        /** 方的（一两个字、图标），边和走向对齐：看不出是顺着的还是垂直的。 */
        SQUARE,
    }

    /**
     * [box] 和走向 [axis] 的关系，差 [WINDOW] 以内算对齐。长短边差到 [ELONGATED] 倍才看得出走向，
     * 比投票的门槛（两倍）低：两个字的词框出来只有一倍半长，也看得出是横是竖。
     */
    fun fit(box: Box, axis: Float): Fit {
        val u = box.tr - box.tl
        val v = box.bl - box.tl
        val (long, short) = if (u.length() >= v.length()) u to v.length() else v to u.length()
        if (long.length() <= 0f) return Fit.OTHER
        val a = angle(long)
        val along = diff(a, axis.toDouble()) <= WINDOW
        val cross = diff(a, axis + 90.0) <= WINDOW
        return when {
            long.length() < ELONGATED * short -> if (along || cross) Fit.SQUARE else Fit.OTHER
            along -> Fit.ALONG
            cross -> Fit.CROSS
            else -> Fit.OTHER
        }
    }

    private fun lines(boxes: List<Box>): List<Line> = boxes.mapIndexedNotNull { i, b ->
        val u = b.tr - b.tl
        val v = b.bl - b.tl
        val (long, short) = if (u.length() >= v.length()) u to v.length() else v to u.length()
        val length = long.length().toDouble()
        if (length < 2 * short || length <= 0.0) return@mapIndexedNotNull null
        Line(i, angle(long), length)
    }

    /** 一条边的走向，(−90, 90]。 */
    private fun angle(p: Pt): Double {
        var angle = Math.toDegrees(atan2(p.y.toDouble(), p.x.toDouble()))
        if (angle <= -90) angle += 180
        if (angle > 90) angle -= 180
        return angle
    }

    private fun lengthNear(lines: List<Line>, angle: Double, window: Double) =
        lines.sumOf { if (diff(it.angle, angle) <= window) it.length else 0.0 }

    /** 众数：哪个框的走向上下 [TIGHT] 以内的框加起来最长。 */
    private fun mode(lines: List<Line>): Double? = lines.maxByOrNull { l -> lengthNear(lines, l.angle, TIGHT) }?.angle

    /** 两条轴之间的夹角，0..90。 */
    private fun diff(a: Double, b: Double): Double {
        val d = abs(a - b) % 180
        return if (d > 90) 180 - d else d
    }

    /** 求平均的窗口：众数上下这么多度以内的行算同一组。拍照时的透视会让同一页的行差出几度，水印和字一般差二三十度。 */
    private const val TIGHT = 5.0

    /** 众数上下这么多度以内都算顺着走向（分组读、判断看不看得出来）。 */
    private const val WINDOW = 15.0

    /** 窗口里的行至少占总长的这么多，才算看得出走向。 */
    private const val MIN_SHARE = 0.6

    /** 横平或竖直的行占总长这么多，整页就当横平或竖直的。 */
    private const val LEVEL_SHARE = 0.25

    /** 歪着的页上，众数那组以外的行占到这么多，就不转了。 */
    private const val OTHER_SHARE = 0.25

    /** 离横平竖直不到这么多度的当作横平竖直：正着的截图一个像素都不转。 */
    private const val SNAP = 2.0

    /** 长边至少是短边的这么多倍，才看得出一个框的走向。 */
    private const val ELONGATED = 1.3f

    const val SAMPLE = 5
}
