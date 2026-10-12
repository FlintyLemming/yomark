package moe.flinty.yomark.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.PathParser
import kotlin.math.max

/**
 * 品牌水印上的图标：应用图标去掉墨色底板，只剩截图、虚线框和胶带。
 *
 * 几何照 docs/icon/src/icons.mjs，坐标是图标母版的 1024，画的时候整体缩放进水印左边那一格。
 * 标签、虚线框、胶带三段路径原样取自生成出来的 docs/icon/icon.svg，BrandGlyphTest 核对两边一致。
 * 胶带的亮边、暗边和投影在水印这么小的尺寸上看不出来，不画。
 */
internal object BrandGlyph {

    const val TAG_PATH = "M290 580V530a10 10 0 0 1 10 -10H388a10 10 0 0 1 10 10V566H316A14 14 0 0 0 302 580Z"
    const val MARQUEE_PATH = "M296 597.5V580A20 20 0 0 1 316 560H404M645 560H660A20 20 0 0 1 680 580V597.5" +
        "M680 614.5V632A20 20 0 0 1 660 652H645M331 652H316A20 20 0 0 1 296 632V614.5" +
        "M421 560h27.8M465.8 560h27.8M510.6 560h27.8M555.4 560h27.8M600.2 560h27.8" +
        "M348 652h32.5M397.5 652h32.5M447 652h32.5M496.5 652h32.5M546 652h32.5M595.5 652h32.5"
    const val TAPE_PATH = "M837.5 306L848 319.32L835.61 325.05L848 338.51L834.54 347.38L848 359.24L832.61 369.55" +
        "L848 379.33L830.15 389.26L848 396.71L831.13 407.95L848 420.57L831.34 426.22L848 439.48L829.2 450" +
        "L194.4 450L176 441.37L191.52 432.58L176 417.67L190.36 407.4L176 396.07L189.49 390.71L176 376.81" +
        "L189.85 370.65L176 360.39L191.7 348.18L176 338.21L194.81 326.67L176 313.57L193.77 306Z"

    /** 截图。胶带左右探出去，上下不出截图，所以图标有多高就看截图。 */
    val PAPER = RectF(232f, 156f, 792f, 868f)
    const val PAPER_RADIUS = 64f
    val TITLE = RectF(296f, 220f, 504f, 264f)
    /** 虚线框里那一行，和下面两行字 */
    val LINES = listOf(RectF(326f, 584f, 650f, 628f), RectF(296f, 708f, 696f, 752f), RectF(296f, 788f, 564f, 832f))
    const val LINE_RADIUS = 22f
    const val MARQUEE_WIDTH = 12f
    const val TAPE_ANGLE = -7f
    const val TAPE_PIVOT_X = 512f
    const val TAPE_PIVOT_Y = 378f

    private const val SKY = 0xFF87CEEB.toInt()
    private const val AMBER = 0xFFFFB300.toInt()
    private const val TITLE_INK = 0xFFBAC3CF.toInt()
    private const val LINE_INK = 0xFFD3D9E2.toInt()

    /**
     * 画进 [box]：高度撑满，水平居中，宽约为高的 0.94。
     *
     * @param edge 白纸一圈细边的颜色，亮图上靠它显出纸的边；null 不描
     * @param outline 水印只能压在码上时（四角全被占）沿纸外沿描的不透明粗边；null 不描
     * @param outlineWidth [outline] 的宽，像素
     */
    fun draw(canvas: Canvas, box: RectF, edge: Int?, outline: Int?, outlineWidth: Float) {
        val s = box.height() / PAPER.height()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        canvas.save()
        canvas.translate(box.centerX(), box.top)
        canvas.scale(s, s)
        canvas.translate(-PAPER.centerX(), -PAPER.top)

        if (outline != null) {
            // 描边压在纸下面，宽度加倍，露在纸外的正好是一份
            stroke.color = outline
            stroke.strokeWidth = 2 * outlineWidth / s
            canvas.drawRoundRect(PAPER, PAPER_RADIUS, PAPER_RADIUS, stroke)
        }
        fill.color = Color.WHITE
        canvas.drawRoundRect(PAPER, PAPER_RADIUS, PAPER_RADIUS, fill)
        if (edge != null) {
            stroke.color = edge
            stroke.strokeWidth = 1f / s
            canvas.drawRoundRect(PAPER, PAPER_RADIUS, PAPER_RADIUS, stroke)
        }

        fill.color = TITLE_INK
        canvas.drawRoundRect(TITLE, LINE_RADIUS, LINE_RADIUS, fill)
        fill.color = AMBER
        canvas.drawPath(PathParser.createPathFromPathData(TAG_PATH), fill)
        // 虚线框至少一像素粗，小水印上才不会淡得像脏点
        stroke.color = AMBER
        stroke.strokeWidth = max(MARQUEE_WIDTH, 1f / s)
        canvas.drawPath(PathParser.createPathFromPathData(MARQUEE_PATH), stroke)
        fill.color = LINE_INK
        for (line in LINES) canvas.drawRoundRect(line, LINE_RADIUS, LINE_RADIUS, fill)

        canvas.rotate(TAPE_ANGLE, TAPE_PIVOT_X, TAPE_PIVOT_Y)
        fill.color = SKY
        canvas.drawPath(PathParser.createPathFromPathData(TAPE_PATH), fill)
        canvas.restore()
    }
}
