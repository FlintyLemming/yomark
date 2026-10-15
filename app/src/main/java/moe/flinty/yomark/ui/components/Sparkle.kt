package moe.flinty.yomark.ui.components

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 一大、一中、一小三颗四角星，「AI 增强识别」按钮上的图标。大的在左，中的在右上，小的在右下。
 *
 * Android 和 Material 只给了静态的 AutoAwesome，没有会动的版本（Gemini、Galaxy AI 那种星星
 * 是各家 App 自己的资源），所以这里自己画：[animating] 时三颗星绕着同一个圈顺时针转，转到前面的变大、
 * 转到后面的变小。星星本身不自转。每拍转三分之一圈，慢起、加速、再慢慢停回静态图标，
 * 一拍一拍的，不是一直匀速地转。停下时就是一枚静态图标。
 *
 * 转的时候星星会出方框一点（最多 [Sparkle.MARGIN]），摆它的地方要留出这点空；圆钮、宽屏按钮的内边距都够。
 */
@Composable
fun SparkleIcon(
    animating: Boolean,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val progress = if (animating) {
        val transition = rememberInfiniteTransition(label = "sparkle")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(Sparkle.BEAT_MS, easing = Sparkle.EASING)),
            label = "sparkle-progress",
        ).value
    } else 0f
    SparkleFrame(progress, modifier, tint)
}

/** 转到 [progress]（见 [Sparkle.stars]）时的一帧。 */
@Composable
internal fun SparkleFrame(progress: Float, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val path = remember { Path() }
    // 画布不裁边：转的时候出方框的那一点照样画出来
    Canvas(modifier) {
        val box = size.minDimension
        val origin = Offset(size.width - box, size.height - box) / 2f
        Sparkle.stars(progress).forEach { star -> drawPath(path.setStar(star, origin, box), tint) }
    }
}

/**
 * 三颗星绕的那一圈。坐标都按图标边长归一，(0, 0) 是方框左上角。
 *
 * 先定静止时三颗星在哪、多大（[REST]），再反推出那个圈：三颗星在圈上隔 120°，越往前越大。
 * 圈斜着放，三颗离镜头远近各不相同，静止时才分得出大、中、小。
 */
internal object Sparkle {
    /**
     * 一拍的时长，一拍转三分之一圈。三颗星长得一样，转三分之一圈正好挨个换到下一颗的位置上，
     * 画面和开始时一样，所以接得上。
     */
    const val BEAT_MS = 1500

    /**
     * 每一拍的快慢：慢起、中间快、慢慢停下，两拍之间在静态图标上停一下。
     * 不用 FastOutSlowIn：它起步太急，转动看着像一抖。
     */
    val EASING: Easing = EaseInOut

    /** 静止时的三颗星，由大到小：大的在左，中的在右上，小的在右下。占满方框的九成，和 Material 图标一样饱满。 */
    val REST = listOf(
        Star(Offset(0.37f, 0.53f), 0.33f),
        Star(Offset(0.78f, 0.22f), 0.20f),
        Star(Offset(0.80f, 0.80f), 0.13f),
    )

    /** 转的时候星星最多出方框这么远（实际不到 0.1）。静止时都在方框里。 */
    const val MARGIN = 0.1f

    /** 四条边往里凹的程度：控制点离中心多远（按半径）。0 是一根细十字，越大越胖。 */
    const val WAIST = 0.28f

    /** [depth] 是离镜头的远近，1 最近、-1 最远。 */
    class Star(val center: Offset, val radius: Float, val depth: Float = 0f)

    /**
     * [progress] 是这一拍转过的比例（快慢由 [EASING] 在动画那边加）。顺时针转：大星往上走、绕到右上。
     * 由远到近排：按这个顺序画，近的才盖得住远的。0 和 1 都是静态图标那个样子。
     */
    fun stars(progress: Float): List<Star> = ring(progress).sortedBy { it.depth }

    /** 按 [REST] 的顺序给出三颗星（第 i 颗静止时在 REST[i]），单测用来跟着同一颗星看它往哪走。 */
    fun ring(progress: Float): List<Star> = List(3) { i ->
        val angle = phase + i * THIRD + progress * THIRD
        val depth = cos(angle)
        Star(center + axisSin * sin(angle) + axisCos * depth, midRadius + radiusSwing * depth, depth)
    }

    private const val THIRD = (2 * PI / 3).toFloat()

    /*
     * 反推圈：第 i 颗星在角 phase + 120°·i 上，中心 = center + axisSin·sin + axisCos·cos，半径 = midRadius + radiusSwing·cos。
     * 三个角隔 120°，sin、cos 各自加起来是 0、平方加起来是 3/2，所以每个量都是对三颗星的加权平均，一步算出来。
     */
    private val midRadius = REST.map { it.radius }.average().toFloat()
    private val phase: Float
    private val radiusSwing: Float

    init {
        // 半径减去平均，正好是 radiusSwing·cos(phase + 120°·i)：拿它在 120° 的三个方向上一投，就知道 phase 和幅度
        var re = 0f
        var im = 0f
        REST.forEachIndexed { i, star ->
            val d = star.radius - midRadius
            re += d * cos(i * THIRD)
            im -= d * sin(i * THIRD)
        }
        phase = atan2(im, re)
        radiusSwing = 2f / 3 * hypot(re, im)
    }

    private val center = REST.fold(Offset.Zero) { sum, star -> sum + star.center } / 3f
    private val axisSin = REST.foldIndexed(Offset.Zero) { i, sum, star -> sum + (star.center - center) * sin(phase + i * THIRD) } * (2f / 3)
    private val axisCos = REST.foldIndexed(Offset.Zero) { i, sum, star -> sum + (star.center - center) * cos(phase + i * THIRD) } * (2f / 3)
}

private fun Path.setStar(star: Sparkle.Star, origin: Offset, box: Float): Path {
    val c = origin + star.center * box
    val r = star.radius * box
    val w = Sparkle.WAIST * r
    reset()
    moveTo(c.x, c.y - r)
    quadraticTo(c.x + w, c.y - w, c.x + r, c.y)
    quadraticTo(c.x + w, c.y + w, c.x, c.y + r)
    quadraticTo(c.x - w, c.y + w, c.x - r, c.y)
    quadraticTo(c.x - w, c.y - w, c.x, c.y - r)
    close()
    return this
}
