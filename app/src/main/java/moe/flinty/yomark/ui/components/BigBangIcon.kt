package moe.flinty.yomark.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 大爆炸的图标：面板上的字块。上面一排三个空心的字，下面一排一整条实心的是选中的两个字连成一块（打出来的码也是一整块），
 * 旁边一个空心的。按 24 的网格画，和 Material 图标一样用 Icon 着色。
 */
val BigBangIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "BigBang",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 空心的边画在里面：外沿和实心的那一条对齐
        val half = STROKE / 2
        path(stroke = SolidColor(Color.Black), strokeLineWidth = STROKE) {
            for ((l, t, r, b) in IDLE) roundRect(l + half, t + half, r - half, b - half, RADIUS - half)
        }
        path(fill = SolidColor(Color.Black)) {
            val (l, t, r, b) = PICKED
            roundRect(l, t, r, b, RADIUS)
        }
    }.build()
}

private fun PathBuilder.roundRect(l: Float, t: Float, r: Float, b: Float, radius: Float) {
    moveTo(l + radius, t)
    horizontalLineTo(r - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, r, t + radius)
    verticalLineTo(b - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, r - radius, b)
    horizontalLineTo(l + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, l, b - radius)
    verticalLineTo(t + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, l + radius, t)
    close()
}

private const val STROKE = 1.5f
private const val RADIUS = 1.5f

/** 没选中的字块：左、上、右、下。 */
private val IDLE = listOf(
    listOf(3f, 4.5f, 8f, 10.5f),
    listOf(9.5f, 4.5f, 14.5f, 10.5f),
    listOf(16f, 4.5f, 21f, 10.5f),
    listOf(16f, 13.5f, 21f, 19.5f),
)

/** 选中、连成一块的两个字。 */
private val PICKED = listOf(3f, 13.5f, 14.5f, 19.5f)
