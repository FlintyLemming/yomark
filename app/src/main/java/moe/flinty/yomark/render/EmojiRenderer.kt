package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.floor
import kotlin.math.max

/**
 * Emoji（spec §8）：按 Quad 短边定字号，居中绘制文本。不可还原。
 *
 * 先用不透明底色填满 Quad 再画字形——Emoji 是圆的，光靠字形盖不满矩形，
 * 不铺底的话四角会漏出原内容。底色可以换（[MaskOptions.emojiBackground]），但永远不透明。
 *
 * 平铺（[MaskOptions.emojiTiled]）时沿长边排满一排，字号不变；长条形的文字行上
 * 只在正中放一个，两边是一大段空底色，不像样。
 */
class EmojiRenderer : MaskRenderer {

    override val style = MaskStyle.EMOJI

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        bgPaint.color = options.emojiBackground
        bgPaint.alpha = 255                    // 不透明是安全属性，不接受 options 覆盖
        canvas.drawPath(quad.toPath(), bgPaint)

        val short = quad.shortEdge()
        if (short < MIN_RENDER_PX) return          // 太小画不下字形，铺底即可

        textPaint.textSize = short * SIZE_RATIO
        val b = quad.bounds()
        val metrics = textPaint.fontMetrics
        val baselineOffset = -(metrics.ascent + metrics.descent) / 2f

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        if (!options.emojiTiled) {
            canvas.drawText(options.emoji, b.centerX(), b.centerY() + baselineOffset, textPaint)
        } else {
            val horizontal = b.width() >= b.height()
            val step = textPaint.textSize * TILE_STEP
            val long = if (horizontal) b.width() else b.height()
            val count = max(1, floor(long / step).toInt())
            // 整排居中：两头剩下的空当一样宽
            val first = -(count - 1) * step / 2f
            repeat(count) { i ->
                val offset = first + i * step
                val x = if (horizontal) b.centerX() + offset else b.centerX()
                val y = if (horizontal) b.centerY() else b.centerY() + offset
                canvas.drawText(options.emoji, x, y + baselineOffset, textPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    private companion object {
        const val SIZE_RATIO = 0.86f
        /** 平铺时相邻两个表情中心的距离，按字号算。留一点空，挤在一起像一串乱码。 */
        const val TILE_STEP = 1.2f
        const val MIN_RENDER_PX = 8f
    }
}
