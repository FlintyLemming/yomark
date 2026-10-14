package moe.flinty.yomark.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 用途水印的图标：一张图上斜着铺满一行行字，和 PurposeWatermarkDrawer 画出来的一个样子——
 * 逆时针斜 30°、隔行错开半格。Material 的 BrandingWatermark 是右下角一小块，说的是角标水印，正是用途水印刻意不做的。
 * 字是一段段短横，整段都在图框里面、离框留一点空：被框切掉的半截在 20 dp 下只是一片噪点。按 24 的网格画，和 Material 图标一样用 Icon 着色。
 */
val PurposeWatermarkIcon: ImageVector by lazy { purposeWatermarkIcon(ROWS, DASH_LENGTH, DASH_WIDTH) }

/**
 * @param rows 每一行：离图中心多远（垂直于字的方向，往右下为正），这一行上每一段字的中心沿字的方向离图中心多远
 */
internal fun purposeWatermarkIcon(rows: List<Pair<Float, List<Float>>>, length: Float, width: Float): ImageVector =
    ImageVector.Builder(
        name = "PurposeWatermark",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        // 图框
        path(stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(5f, 4f)
            horizontalLineTo(19f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 21f, 6f)
            verticalLineTo(18f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 19f, 20f)
            horizontalLineTo(5f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 3f, 18f)
            verticalLineTo(6f)
            arcTo(2f, 2f, 0f, isMoreThanHalf = false, isPositiveArc = true, 5f, 4f)
            close()
        }
        // 斜着的字：沿字的方向是 (cos, -sin)，往下一行是 (sin, cos)
        val rad = (WATERMARK_ANGLE_DEGREES * PI / 180).toFloat()
        val alongX = cos(rad)
        val alongY = -sin(rad)
        path(stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round) {
            for ((row, dashes) in rows) {
                val rowX = CENTER + row * sin(rad)
                val rowY = CENTER + row * cos(rad)
                for (t in dashes) {
                    val cx = rowX + t * alongX
                    val cy = rowY + t * alongY
                    moveTo(cx - alongX * length / 2, cy - alongY * length / 2)
                    lineTo(cx + alongX * length / 2, cy + alongY * length / 2)
                }
            }
        }
    }.build()

/** 和 PurposeWatermarkStyle.DEFAULT_ANGLE 一样斜 30°（那边逆时针记负数）。 */
private const val WATERMARK_ANGLE_DEGREES = 30f
private const val CENTER = 12f

/** 中间一行两段，上下两行各一段、错开半格。 */
private val ROWS = listOf(
    -3.5f to listOf(0f),
    0f to listOf(-3.25f, 3.25f),
    3.5f to listOf(0f),
)
private const val DASH_LENGTH = 3.5f
private const val DASH_WIDTH = 1.75f
