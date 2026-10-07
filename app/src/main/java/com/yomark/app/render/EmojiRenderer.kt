package com.yomark.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskStyle

/**
 * Emoji（spec §8）：按 Quad 短边定字号，居中绘制文本。不可还原。
 *
 * 先用不透明底色填满 Quad 再画字形——Emoji 是圆的，光靠字形盖不满矩形，
 * 不铺底的话四角会漏出原内容。
 */
class EmojiRenderer : MaskRenderer {

    override val style = MaskStyle.EMOJI

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        bgPaint.color = BACKGROUND
        canvas.drawPath(quad.toPath(), bgPaint)

        val short = quad.shortEdge()
        if (short < MIN_RENDER_PX) return          // 太小画不下字形，铺底即可

        textPaint.textSize = short * SIZE_RATIO
        val b = quad.bounds()
        val metrics = textPaint.fontMetrics
        val baseline = b.centerY() - (metrics.ascent + metrics.descent) / 2f

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawText(options.emoji, b.centerX(), baseline, textPaint)
        canvas.restoreToCount(save)
    }

    private companion object {
        const val SIZE_RATIO = 0.86f
        const val MIN_RENDER_PX = 8f
        val BACKGROUND = Color.rgb(0x33, 0x33, 0x38)
    }
}
