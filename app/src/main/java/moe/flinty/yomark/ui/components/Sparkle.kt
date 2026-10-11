package moe.flinty.yomark.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos

/**
 * 一大一小两颗四角星，「AI 增强识别」按钮上的图标。
 *
 * Android 和 Material 只给了静态的 AutoAwesome，没有会动的版本（Gemini、Galaxy AI 那种转动的星星
 * 是各家 App 自己的资源），所以这里自己画：[animating] 时两颗星各绕自己的中心反向转、一涨一缩，
 * 停下时就是一枚静态图标。
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
            animationSpec = infiniteRepeatable(tween(Sparkle.CYCLE_MS, easing = LinearEasing)),
            label = "sparkle-progress",
        ).value
    } else 0f
    val path = remember { Path() }
    Canvas(modifier) {
        val pose = Sparkle.pose(progress)
        drawStar(path, Sparkle.BIG_CENTER, Sparkle.BIG_RADIUS * pose.bigScale, pose.bigDegrees, tint)
        drawStar(path, Sparkle.SMALL_CENTER, Sparkle.SMALL_RADIUS * pose.smallScale, pose.smallDegrees, tint)
    }
}

/** 星星摆在哪、多大，以及动起来时每一帧的姿态。坐标都按图标边长归一。 */
internal object Sparkle {
    /** 转一轮的时长。四角星转 180° 与原样重合，所以一轮首尾接得上。 */
    const val CYCLE_MS = 1600

    val BIG_CENTER = Offset(0.42f, 0.58f)
    const val BIG_RADIUS = 0.40f
    val SMALL_CENTER = Offset(0.77f, 0.23f)
    const val SMALL_RADIUS = 0.20f

    /** 四条边往里凹的程度：控制点离中心多远（按半径）。0 是一根细十字，越大越胖。 */
    const val WAIST = 0.14f

    class Pose(val bigDegrees: Float, val bigScale: Float, val smallDegrees: Float, val smallScale: Float)

    /**
     * [progress] 在 0..1 里走一轮。转动加了缓入缓出，像一下一下地拧过去；
     * 大小一涨一缩错开半拍，0 时两颗都是原尺寸，跟静态图标一样。
     */
    fun pose(progress: Float): Pose {
        val turn = FastOutSlowInEasing.transform(progress) * 180f
        val wave = (1f - cos(2 * PI * progress).toFloat()) / 2f   // 0 → 1 → 0
        return Pose(
            bigDegrees = turn,
            bigScale = 1f - 0.18f * wave,
            smallDegrees = -turn,
            smallScale = 1f + 0.12f * wave,
        )
    }
}

private fun DrawScope.drawStar(path: Path, center: Offset, radius: Float, degrees: Float, color: Color) {
    val c = Offset(center.x * size.width, center.y * size.height)
    val r = radius * size.minDimension
    val w = Sparkle.WAIST * r
    path.reset()
    path.moveTo(c.x, c.y - r)
    path.quadraticTo(c.x + w, c.y - w, c.x + r, c.y)
    path.quadraticTo(c.x + w, c.y + w, c.x, c.y + r)
    path.quadraticTo(c.x - w, c.y + w, c.x - r, c.y)
    path.quadraticTo(c.x - w, c.y - w, c.x, c.y - r)
    path.close()
    rotate(degrees, pivot = c) { drawPath(path, color) }
}
