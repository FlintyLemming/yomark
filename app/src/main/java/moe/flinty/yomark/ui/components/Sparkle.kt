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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 一大一小两颗四角星，「AI 增强识别」按钮上的图标。大的在前、小的在后。
 *
 * Android 和 Material 只给了静态的 AutoAwesome，没有会动的版本（Gemini、Galaxy AI 那种星星
 * 是各家 App 自己的资源），所以这里自己画：[animating] 时两颗星绕着同一个中心前后转着换位——
 * 前面的大星绕到后面变小，后面的小星绕到前面变大。星星本身不自转。停下时就是一枚静态图标。
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
            animationSpec = infiniteRepeatable(tween(Sparkle.SWAP_MS, easing = LinearEasing)),
            label = "sparkle-progress",
        ).value
    } else 0f
    val path = remember { Path() }
    Canvas(modifier) {
        for (star in Sparkle.stars(progress)) drawStar(path, star, tint)
    }
}

/**
 * 两颗星绕的那一圈：一个斜着看过去的圆，投到图标上是一个椭圆。坐标都按图标边长归一。
 *
 * 前后沿左下—右上这条对角线：最前面在左下，最后面在右上。离镜头越近越大，所以
 * 静止时左下一颗大、右上一颗小。换位走到一半时两颗一样远，分在左上和右下，一样大。
 */
internal object Sparkle {
    /** 换一次位的时长。两颗星长得一样，换完的画面和开始时一样，所以一次接一次接得上。 */
    const val SWAP_MS = 1200

    /** 圈的中心。比图标中心偏右上一点：大星占地方，这样整圈走下来上下左右留的边一样。 */
    val ORBIT_CENTER = Offset(0.54f, 0.46f)

    /** 从中心到最前面那一点（左下）。最后面那一点在正对面（右上）。 */
    val TO_FRONT = Offset(-0.15f, 0.15f)

    /** 从中心到侧面那一点（右下），另一侧在左上。比 [TO_FRONT] 长：圈是斜着看的，前后方向被压扁了。 */
    val TO_SIDE = Offset(0.21f, 0.21f)

    /** 星的半径：最前面时最大，最后面时一半大，中间按远近过渡。 */
    const val FRONT_RADIUS = 0.26f
    const val BACK_RADIUS = 0.13f

    /** 四条边往里凹的程度：控制点离中心多远（按半径）。0 是一根细十字，越大越胖。 */
    const val WAIST = 0.14f

    class Star(val center: Offset, val radius: Float)

    /**
     * [progress] 在 0..1 里走完一次换位：两颗星各绕半圈。缓入缓出，换好之后停一停再换下一次。
     * 0 和 1 都是静态图标那个样子。
     */
    fun stars(progress: Float): List<Star> {
        val angle = PI.toFloat() * FastOutSlowInEasing.transform(progress)
        return listOf(star(angle), star(angle + PI.toFloat()))
    }

    private fun star(angle: Float): Star {
        val depth = cos(angle)   // 1 最前，-1 最后
        return Star(
            center = ORBIT_CENTER + TO_FRONT * depth + TO_SIDE * sin(angle),
            radius = BACK_RADIUS + (FRONT_RADIUS - BACK_RADIUS) * (depth + 1) / 2,
        )
    }
}

/** 两颗星不会叠在一起（圈的大小就是按这个挑的），所以先画哪颗都一样。 */
private fun DrawScope.drawStar(path: Path, star: Sparkle.Star, color: Color) {
    val c = Offset(star.center.x * size.width, star.center.y * size.height)
    val r = star.radius * size.minDimension
    val w = Sparkle.WAIST * r
    path.reset()
    path.moveTo(c.x, c.y - r)
    path.quadraticTo(c.x + w, c.y - w, c.x + r, c.y)
    path.quadraticTo(c.x + w, c.y + w, c.x, c.y + r)
    path.quadraticTo(c.x - w, c.y + w, c.x - r, c.y)
    path.quadraticTo(c.x - w, c.y - w, c.x, c.y - r)
    path.close()
    drawPath(path, color)
}
