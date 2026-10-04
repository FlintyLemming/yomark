package com.youma.app.export

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * 用途水印：在证件照上叠「仅供办理 XX 使用」。
 *
 * **与品牌水印（WatermarkDrawer）是两回事**：品牌水印是商业模式的一部分，付费即去除；
 * 用途水印是安全功能，防止照片被挪作他用，不受购买态影响。
 *
 * 斜向平铺整张图是刻意的——单个角落的水印一裁就没。
 *
 * 编辑器预览和导出用的是同一个 drawer：尺寸全按短边比例算，分析图上与原图上观感一致。
 */
class PurposeWatermarkDrawer {

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        text: String,
        style: PurposeWatermarkStyle = PurposeWatermarkStyle(),
    ) {
        if (text.isBlank() || width <= 0 || height <= 0) return

        val s = style.normalized()
        val shortEdge = minOf(width, height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = s.color
            alpha = s.alpha
            textSize = max(shortEdge * SIZE_RATIO, MIN_SIZE_PX)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textWidth = paint.measureText(text)
        if (textWidth <= 0f) return

        val stepX = textWidth + shortEdge * GAP_RATIO / s.density
        val stepY = paint.textSize * LINE_RATIO / s.density

        val cx = width / 2f
        val cy = height / 2f
        val save = canvas.save()
        // 导出位图本身就到边为止；编辑器预览的画布比图大，不裁会铺到图外的留白上
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.rotate(s.angle, cx, cy)
        // 绕中心转任意角度后，原矩形都落在以中心为圆心、半对角线为半径的圆里，
        // 只铺这个圆的外接方块就够了。网格锚在中心，转角度时图案绕中心转，不会整片漂走。
        val radius = hypot(width.toFloat(), height.toFloat()) / 2f
        val rows = ceil(radius / stepY).toInt() + 1
        val cols = ceil(radius / stepX).toInt() + 1
        for (row in -rows..rows) {
            val y = cy + row * stepY
            val offset = if (row % 2 == 0) 0f else stepX / 2f    // 错行，避免竖直空隙
            for (col in -cols - 1..cols) {
                canvas.drawText(text, cx + col * stepX + offset - textWidth / 2f, y, paint)
            }
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        const val SIZE_RATIO = 0.045f
        const val MIN_SIZE_PX = 14f
        const val GAP_RATIO = 0.12f
        const val LINE_RATIO = 3.2f
    }
}
