package com.youma.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.max

/**
 * 用途水印：在证件照上叠「仅供办理 XX 使用」。
 *
 * **与品牌水印（WatermarkDrawer）是两回事**：品牌水印是商业模式的一部分，付费即去除；
 * 用途水印是安全功能，防止照片被挪作他用，不受购买态影响。
 *
 * 斜向平铺整张图是刻意的——单个角落的水印一裁就没。
 */
class PurposeWatermarkDrawer {

    fun draw(canvas: Canvas, width: Int, height: Int, text: String) {
        if (text.isBlank() || width <= 0 || height <= 0) return

        val shortEdge = minOf(width, height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(ALPHA, 0, 0, 0)
            textSize = max(shortEdge * SIZE_RATIO, MIN_SIZE_PX)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textWidth = paint.measureText(text)
        if (textWidth <= 0f) return

        val stepX = textWidth + shortEdge * GAP_RATIO
        val stepY = paint.textSize * LINE_RATIO

        val save = canvas.save()
        // 导出位图本身就到边为止；编辑器预览的画布比图大，不裁会铺到图外的留白上
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.rotate(ANGLE, width / 2f, height / 2f)
        // 旋转后要覆盖原矩形，绘制范围向外扩一个对角线长度
        val diagonal = kotlin.math.hypot(width.toFloat(), height.toFloat())
        var y = -diagonal
        var row = 0
        while (y < height + diagonal) {
            val offset = if (row % 2 == 0) 0f else stepX / 2f    // 错行，避免竖直空隙
            var x = -diagonal + offset
            while (x < width + diagonal) {
                canvas.drawText(text, x, y, paint)
                x += stepX
            }
            y += stepY
            row++
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        const val ANGLE = -30f
        const val ALPHA = 46          // 约 18% 不透明度：看得见，不挡阅读
        const val SIZE_RATIO = 0.045f
        const val MIN_SIZE_PX = 14f
        const val GAP_RATIO = 0.12f
        const val LINE_RATIO = 3.2f
    }
}
