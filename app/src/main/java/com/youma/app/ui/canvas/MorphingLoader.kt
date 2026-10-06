package com.youma.app.ui.canvas

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.circle
import androidx.graphics.shapes.star
import androidx.graphics.shapes.toPath
import androidx.graphics.shapes.transformed
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 磨砂动效中间那个边转边变形的等待图标：Material 3 Expressive 的 LoadingIndicator（不定进度那种）。
 *
 * Material 3 的这个组件目前只在 compose-material3 1.5 的 alpha 里，不为它升级到 alpha 版。这里用它底下
 * 那套稳定的形状库（androidx.graphics:graphics-shapes）照着原样画：同样的七个形状（取自 MaterialShapes 的
 * 定义）首尾相接地变形，每 650 ms 变一次，每次用阻尼 0.6、刚度 200 的弹簧走完，同时顺时针转四分之一圈；
 * 整体再匀速转，4.666 秒一圈。尺寸也一样：48 dp 的位置里画 38 dp 的形状。
 *
 * 不用 Compose 的动画：它画在画布上，跟扫光、磨砂同一根时间轴，结果到达时才能和那个洞严丝合缝地接上。
 * 某一时刻长什么样只取决于识别开始以来的毫秒数，给定时间就能画出任意一帧（测试也这么画）。
 */
internal object MorphingLoader {

    /** 图标占的位置与形状本身的大小（dp），与 Material 3 一致。 */
    const val CONTAINER_DP = 48f
    const val INDICATOR_DP = 38f

    /** 每隔多久变一次形。 */
    const val MORPH_INTERVAL_MS = 650L

    /** 整体匀速转一圈的时长。 */
    const val GLOBAL_ROTATION_MS = 4666L

    /** 变形用的弹簧：欠阻尼，会略微冲过头再回来，形状因此有一点弹性。 */
    private const val DAMPING_RATIO = 0.6f
    private const val STIFFNESS = 200f

    /**
     * 七个形状，按 Material 3 的顺序：柔和的十角星、九瓣曲奇、五边形、药丸、八角太阳、四瓣曲奇、椭圆。
     * 每个都已归一化到单位正方形里。
     */
    val shapes: List<RoundedPolygon> by lazy {
        listOf(softBurst(), cookie9(), pentagon(), pill(), sunny(), cookie4(), oval()).map { it.normalized() }
    }

    /** 首尾相接的变形序列：第 i 段从第 i 个形状变到第 i + 1 个，最后一段回到第一个。 */
    private val morphs: List<Morph> by lazy {
        shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) }
    }

    /**
     * 形状画多大：照 Material 3 的算法，按每个形状转起来要占的最大范围收一收，转到哪个角度都不出界；
     * 再乘上 38 / 48，留出它在 48 dp 位置里的边距。
     */
    private val shapeScale: Float by lazy {
        val bounds = FloatArray(4)
        val maxBounds = FloatArray(4)
        var factor = 1f
        shapes.forEach { polygon ->
            polygon.calculateBounds(bounds)
            polygon.calculateMaxBounds(maxBounds)
            val sx = (bounds[2] - bounds[0]) / (maxBounds[2] - maxBounds[0])
            val sy = (bounds[3] - bounds[1]) / (maxBounds[3] - maxBounds[1])
            factor = min(factor, maxOf(sx, sy))
        }
        factor * INDICATOR_DP / CONTAINER_DP
    }

    /** 此刻在走第几段变形。 */
    fun morphIndex(ms: Long): Int = ((ms.coerceAtLeast(0L) / MORPH_INTERVAL_MS) % shapes.size).toInt()

    /** 这一段变形走到哪儿了：每段开始时为 0，弹簧把它带到 1（中途会冲过一点）。 */
    fun morphProgress(ms: Long): Float = spring((ms.coerceAtLeast(0L) % MORPH_INTERVAL_MS) / 1000f)

    /**
     * 此刻转了多少度（顺时针）：每变一次形转四分之一圈，跟着弹簧走；整体再匀速转。
     * 与 Material 3 一样从 90° 起步。不对 360 取模：角度一路往上走，任何一帧到下一帧都接得上。
     */
    fun rotation(ms: Long): Float {
        val t = ms.coerceAtLeast(0L)
        val morphs = (t / MORPH_INTERVAL_MS).toFloat() + morphProgress(t)
        val global = t.toFloat() / GLOBAL_ROTATION_MS * 360f
        return 90f + 90f * morphs + global
    }

    /**
     * 阻尼比 0.6、刚度 200（质量 1）的弹簧从 0 弹向 1 的位置，[t] 以秒计。
     * 约 0.28 秒时冲过头最多（约 9%），0.65 秒时与 1 只差千分之几。
     */
    fun spring(t: Float): Float {
        val w0 = sqrt(STIFFNESS)
        val decay = DAMPING_RATIO * w0
        val wd = w0 * sqrt(1f - DAMPING_RATIO * DAMPING_RATIO)
        return 1f - exp(-decay * t) * (cos(wd * t) + decay / wd * sin(wd * t))
    }

    /**
     * 在 ([cx], [cy]) 画此刻（识别开始后 [ms] 毫秒）的图标。
     *
     * @param container 图标位置的边长（像素），即 48 dp
     * @param scale 额外的缩放（入场、散开时用）
     * @param alpha 不透明度
     */
    fun draw(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        container: Float,
        ms: Long,
        scale: Float,
        alpha: Float,
        paint: Paint,
        path: Path,
        matrix: Matrix,
        bounds: RectF,
    ) {
        if (alpha <= 0f || scale <= 0f) return
        morphs[morphIndex(ms)].toPath(morphProgress(ms), path)
        // 与 Material 3 一样：放大到位后按这一帧形状自己的外框居中，再绕中心转
        val size = container * shapeScale * scale
        matrix.setScale(size, size)
        path.transform(matrix)
        path.computeBounds(bounds, true)
        matrix.setTranslate(cx - bounds.centerX(), cy - bounds.centerY())
        matrix.postRotate(rotation(ms), cx, cy)
        path.transform(matrix)
        val restore = paint.alpha
        paint.alpha = (restore * alpha.coerceIn(0f, 1f)).toInt()
        canvas.drawPath(path, paint)
        paint.alpha = restore
    }

    // ---------- 形状（取自 Material 3 的 MaterialShapes，数值原样照搬）----------

    private fun softBurst() = customPolygon(
        listOf(PointNRound(0.193f, 0.277f, CornerRounding(0.053f)), PointNRound(0.176f, 0.055f, CornerRounding(0.053f))),
        reps = 10,
    )

    private fun cookie9() = RoundedPolygon.star(numVerticesPerRadius = 9, innerRadius = 0.8f, rounding = CornerRounding(0.5f))
        .transformed(Matrix().apply { setRotate(-90f) })

    private fun pentagon() = customPolygon(
        listOf(
            PointNRound(0.500f, -0.009f, CornerRounding(0.172f)),
            PointNRound(1.030f, 0.365f, CornerRounding(0.164f)),
            PointNRound(0.828f, 0.970f, CornerRounding(0.169f)),
        ),
        reps = 1,
        mirroring = true,
    )

    private fun pill() = customPolygon(
        listOf(
            PointNRound(0.961f, 0.039f, CornerRounding(0.426f)),
            PointNRound(1.001f, 0.428f),
            PointNRound(1.000f, 0.609f, CornerRounding(1.000f)),
        ),
        reps = 2,
        mirroring = true,
    )

    private fun sunny() = RoundedPolygon.star(numVerticesPerRadius = 8, innerRadius = 0.8f, rounding = CornerRounding(0.15f))

    private fun cookie4() = customPolygon(
        listOf(PointNRound(1.237f, 1.236f, CornerRounding(0.258f)), PointNRound(0.500f, 0.918f, CornerRounding(0.233f))),
        reps = 4,
    )

    private fun oval() = RoundedPolygon.circle()
        .transformed(Matrix().apply { setScale(1f, 0.64f) })
        .transformed(Matrix().apply { setRotate(-45f) })

    private class PointNRound(val x: Float, val y: Float, val r: CornerRounding = CornerRounding.Unrounded)

    /**
     * 由一段顶点绕中心重复 [reps] 次拼出整个形状；[mirroring] 时每隔一段镜像一次。
     * 与 MaterialShapes 里的同名私有函数一致。
     */
    private fun customPolygon(
        points: List<PointNRound>,
        reps: Int,
        center: PointF = PointF(0.5f, 0.5f),
        mirroring: Boolean = false,
    ): RoundedPolygon {
        val all = repeatAround(points, reps, center, mirroring)
        return RoundedPolygon(
            vertices = FloatArray(all.size * 2) { i -> if (i % 2 == 0) all[i / 2].x else all[i / 2].y },
            perVertexRounding = all.map { it.r },
            centerX = center.x,
            centerY = center.y,
        )
    }

    private fun repeatAround(points: List<PointNRound>, reps: Int, center: PointF, mirroring: Boolean): List<PointNRound> {
        if (!mirroring) {
            return (0 until points.size * reps).map { i ->
                val p = points[i % points.size]
                val (x, y) = rotateAround(p.x, p.y, (i / points.size) * 360f / reps, center)
                PointNRound(x, y, p.r)
            }
        }
        val angles = points.map { Math.toDegrees(atan2((it.y - center.y).toDouble(), (it.x - center.x).toDouble())).toFloat() }
        val distances = points.map { hypot(it.x - center.x, it.y - center.y) }
        val sections = reps * 2
        val sectionAngle = 360f / sections
        return buildList {
            repeat(sections) { s ->
                points.indices.forEach { index ->
                    val i = if (s % 2 == 0) index else points.lastIndex - index
                    if (i > 0 || s % 2 == 0) {
                        val degrees = sectionAngle * s +
                            if (s % 2 == 0) angles[i] else sectionAngle - angles[i] + 2 * angles[0]
                        val a = Math.toRadians(degrees.toDouble())
                        add(
                            PointNRound(
                                (cos(a) * distances[i] + center.x).toFloat(),
                                (sin(a) * distances[i] + center.y).toFloat(),
                                points[i].r,
                            )
                        )
                    }
                }
            }
        }
    }

    private fun rotateAround(x: Float, y: Float, degrees: Float, center: PointF): Pair<Float, Float> {
        val a = Math.toRadians(degrees.toDouble())
        val dx = x - center.x
        val dy = y - center.y
        return (dx * cos(a) - dy * sin(a) + center.x).toFloat() to (dx * sin(a) + dy * cos(a) + center.y).toFloat()
    }
}
