package moe.flinty.yomark.core.geometry

import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 一页字的阅读方向，和转到这个方向上看版面的坐标系。
 *
 * 规则层按「右边 = +x、下面 = +y」认版面：值在字段名下面、旁证在名字下一行、同一排是竖直方向重叠。
 * 竖着拍的身份证，阅读方向在图上朝下，「下一行」在图上是左边。把每个框先转到阅读方向的坐标系里再取外接框，
 * 规则就一行不用改。
 *
 * 阅读方向取每行 quad 的 p0→p1（识别器按阅读方向给角：PP-OCR 转正读过的行映射回来仍是这个顺序，ML Kit 的 cornerPoints 也是），
 * 按行长加权求平均。先找众数再平均：横排的页上夹着几行竖排的小字，不该把整页拉歪；窗口里不到总长的 [MIN_SHARE]，
 * 横的竖的都有，看不出来，当作正着的。
 *
 * @param degrees 阅读方向：0 是从左往右，90 是从上往下（图像坐标，y 朝下，正值顺时针）。
 */
class ReadingFrame private constructor(val degrees: Float) {

    private val c = cos(Math.toRadians(degrees.toDouble())).toFloat()
    private val s = sin(Math.toRadians(degrees.toDouble())).toFloat()

    val isUpright: Boolean get() = degrees == 0f

    /**
     * 页面在图上顺时针转了几个直角（0..3）：阅读方向离某个直角不到 [QUARTER_SLACK] 度就算它。
     * 歪得更多的（斜四十五度拍的）算 0——转直角也转不正。人脸检测按它把图转正了再看一遍（RedactionEngine）。
     */
    val quarterTurns: Int = (degrees / 90).roundToInt().let { k ->
        if (abs(degrees - k * 90) <= QUARTER_SLACK) Math.floorMod(k, 4) else 0
    }

    /**
     * [quad] 转到阅读方向的坐标系里（绕原点转 −[degrees]）之后的外接框。
     * 只用于比较框和框的相对位置，原点在哪不要紧。正着的页上就是 quad.bounds()，一位都不差。
     */
    fun bounds(quad: Quad): RectF {
        if (isUpright) return quad.bounds()
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        quad.points().forEach { p ->
            val x = when (degrees) {
                90f -> p.y
                180f -> -p.x
                -90f -> -p.y
                else -> p.x * c + p.y * s
            }
            val y = when (degrees) {
                90f -> -p.x
                180f -> -p.y
                -90f -> p.x
                else -> -p.x * s + p.y * c
            }
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        return RectF(left, top, right, bottom)
    }

    /** [bounds] 反过来：阅读方向上的一个框转回图上，角按阅读方向排（左上、右上、右下、左下）。 */
    fun toImage(r: RectF): Quad = Quad(back(r.left, r.top), back(r.right, r.top), back(r.right, r.bottom), back(r.left, r.bottom))

    private fun back(x: Float, y: Float): PointF = when (degrees) {
        0f -> PointF(x, y)
        90f -> PointF(-y, x)
        180f -> PointF(-x, -y)
        -90f -> PointF(y, -x)
        else -> PointF(x * c - y * s, x * s + y * c)
    }

    companion object {

        val UPRIGHT = ReadingFrame(0f)

        /** 一组行（各行的 quad）的阅读方向。离横平竖直不到 [SNAP] 度的取整：正着的截图一个像素都不转。 */
        fun of(quads: List<Quad>): ReadingFrame {
            val d = direction(quads) ?: return UPRIGHT
            val k = (d / 90).roundToInt()
            if (abs(d - k * 90) < SNAP) return when (Math.floorMod(k, 4)) {
                0 -> UPRIGHT
                1 -> ReadingFrame(90f)
                2 -> ReadingFrame(180f)
                else -> ReadingFrame(-90f)
            }
            return ReadingFrame(d)
        }

        /** 一组行的 [ReadingFrame.quarterTurns]。 */
        fun quarterTurns(quads: List<Quad>): Int = of(quads).quarterTurns

        /** 加权平均的阅读方向（度，(−180, 180]）；没有行、或者看不出来时为 null。 */
        private fun direction(quads: List<Quad>): Float? {
            val lines = quads.mapNotNull { q ->
                val dx = (q.p1.x - q.p0.x).toDouble()
                val dy = (q.p1.y - q.p0.y).toDouble()
                val length = hypot(dx, dy)
                if (length <= 0.0) null else Math.toDegrees(atan2(dy, dx)) to length
            }
            if (lines.isEmpty()) return null
            // 众数：哪一行的方向上下 WINDOW 以内的行加起来最长
            val mode = lines.maxBy { (a, _) -> lines.sumOf { (b, len) -> if (diff(a, b) <= WINDOW) len else 0.0 } }.first
            val near = lines.filter { diff(it.first, mode) <= WINDOW }
            if (near.sumOf { it.second } < MIN_SHARE * lines.sumOf { it.second }) return null
            val x = near.sumOf { (a, len) -> len * cos(Math.toRadians(a)) }
            val y = near.sumOf { (a, len) -> len * sin(Math.toRadians(a)) }
            return Math.toDegrees(atan2(y, x)).toFloat()
        }

        /** 两个方向的夹角，0..180。 */
        private fun diff(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        /** 众数上下这么多度以内的行算同一个方向。 */
        private const val WINDOW = 20.0

        /** 窗口里的行至少占总长的这么多，才算看得出方向。 */
        private const val MIN_SHARE = 0.6

        /** 离横平竖直不到这么多度的，当作横平竖直。 */
        private const val SNAP = 1f

        /** 离直角这么多度以内，人脸检测转直角就够转正。 */
        private const val QUARTER_SLACK = 30f
    }
}
