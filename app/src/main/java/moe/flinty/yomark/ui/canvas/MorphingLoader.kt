package moe.flinty.yomark.ui.canvas

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 磨砂动效中间那个边转边变形的等待图标：Material 3 Expressive 的 LoadingIndicator（不定进度那种）。
 *
 * Material 3 的这个组件目前只在 compose-material3 1.5 的 alpha 里，不为它升级到 alpha 版。这里用它底下
 * 那套稳定的形状库（androidx.graphics:graphics-shapes）照着原样画：同样的七个形状（取自 MaterialShapes 的
 * 定义，见 [ExpressiveShapes]）首尾相接地变形，每 650 ms 变一次，每次用阻尼 0.6、刚度 200 的弹簧走完，
 * 同时顺时针转四分之一圈；整体再匀速转，4.666 秒一圈。尺寸也一样：48 dp 的位置里画 38 dp 的形状。
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
        with(ExpressiveShapes) { listOf(softBurst(), cookie9(), pentagon(), pill(), sunny(), cookie4(), oval()) }
            .map { it.normalized() }
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
}
