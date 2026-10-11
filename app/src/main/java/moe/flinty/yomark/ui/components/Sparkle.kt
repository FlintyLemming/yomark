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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一大一小两颗四角星，「AI 增强识别」按钮上的图标。大的在前、小的在后。
 *
 * Android 和 Material 只给了静态的 AutoAwesome，没有会动的版本（Gemini、Galaxy AI 那种星星
 * 是各家 App 自己的资源），所以这里自己画：[animating] 时两颗星绕着同一根轴转，一前一后地交错，
 * 前面那颗变大、把后面那颗挡住一部分。星星本身不自转。每转半圈都是慢起、加速、再慢慢停回静态图标，
 * 一拍一拍的，不是一直匀速地转。停下时就是一枚静态图标。
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
            animationSpec = infiniteRepeatable(tween(Sparkle.HALF_TURN_MS, easing = Sparkle.EASING)),
            label = "sparkle-progress",
        ).value
    } else 0f
    SparkleFrame(progress, modifier, tint)
}

/** 转到 [progress]（见 [Sparkle.stars]）时的一帧。 */
@Composable
internal fun SparkleFrame(progress: Float, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val path = remember { Path() }
    // 前面那颗星要在后面那颗上挖出一圈空隙，得在自己这一层里挖，不能挖穿到按钮上
    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val (back, front) = Sparkle.stars(progress)
        drawPath(path.setStar(back, size), tint)
        path.setStar(front, size)
        drawPath(
            path,
            Color.Black,
            style = Stroke(width = 2 * Sparkle.GAP * size.minDimension, join = StrokeJoin.Round),
            blendMode = BlendMode.Clear,
        )
        drawPath(path, tint)
    }
}

/**
 * 两颗星绕的那一圈。坐标都按图标边长归一。
 *
 * 圈立在左下—右上这条对角线上，几乎是侧着看：两颗星大多在这条线上来回，转到最前面时最大，
 * 最后面时最小，于是一前一后地交错，前面那颗挡住后面那颗。镜头稍高一点，最前面那一点偏右下、
 * 最后面那一点偏左上，交错时后面那颗从左上方露出一部分，不会整个不见。
 */
internal object Sparkle {
    /** 转半圈的时长。两颗星长得一样，转半圈两颗正好互换，画面和开始时一样，所以接得上。 */
    const val HALF_TURN_MS = 1600

    /**
     * 每个半圈的快慢：慢起、中间快、慢慢停下，两个半圈之间在静态图标上停约 0.3 秒。
     * 不用 FastOutSlowIn：它起步太急，交错正好落在最快那一段，遮挡一闪就过去了（不到 0.2 秒）；
     * 这条两头对称的曲线下，交错能看清约 0.3 秒。
     */
    val EASING: Easing = EaseInOut

    /** 圈的中心。比图标中心偏右上：静止时大星在左下，整圈走下来也不出图标。 */
    val ORBIT_CENTER = Offset(0.51f, 0.44f)

    /** 从中心到圈的左下端，右上端在正对面。转到两端时两颗星和镜头一样远，一样大。 */
    val TO_END = Offset(-0.20f, 0.20f)

    /** 从中心到圈的最前面一点，最后面那一点在正对面。 */
    val TO_FRONT = Offset(0.08f, 0.08f)

    /** 星的半径：最前面时最大，最后面时最小，中间按远近过渡。 */
    const val FRONT_RADIUS = 0.28f
    const val BACK_RADIUS = 0.12f

    /** 静止时大星在圈上的位置：偏前、偏左下，小星在它正对面。 */
    val REST_ANGLE = (PI / 4).toFloat()

    /** 前面那颗星周围挖掉的空隙宽度：两颗星同色，没有这道缝，叠在一起就看不出谁挡着谁。 */
    const val GAP = 0.05f

    /** 四条边往里凹的程度：控制点离中心多远（按半径）。0 是一根细十字，越大越胖。 */
    const val WAIST = 0.14f

    class Star(val center: Offset, val radius: Float, val depth: Float)

    /**
     * [progress] 是这半圈转过的比例（快慢由 [EASING] 在动画那边加）。前面那颗从左下往右上走，后面那颗反过来。
     * 先后面再前面：按这个顺序画，前面那颗才盖得住后面那颗。0 和 1 都是静态图标那个样子。
     */
    fun stars(progress: Float): List<Star> {
        val angle = REST_ANGLE - PI.toFloat() * progress
        return listOf(star(angle), star(angle + PI.toFloat())).sortedBy { it.depth }
    }

    private fun star(angle: Float): Star {
        val depth = cos(angle)   // 1 最前，-1 最后
        return Star(
            center = ORBIT_CENTER + TO_END * sin(angle) + TO_FRONT * depth,
            radius = BACK_RADIUS + (FRONT_RADIUS - BACK_RADIUS) * (depth + 1) / 2,
            depth = depth,
        )
    }
}

private fun Path.setStar(star: Sparkle.Star, size: Size): Path {
    val c = Offset(star.center.x * size.width, star.center.y * size.height)
    val r = star.radius * size.minDimension
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
