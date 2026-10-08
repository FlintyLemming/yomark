package moe.flinty.yomark.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

enum class WatermarkCorner { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }

data class WatermarkPlacement(
    val rect: RectF,
    val corner: WatermarkCorner,
    val outlined: Boolean,
    val darkInk: Boolean,
)

/**
 * 免费导出的品牌水印（spec §9.3）。
 *
 * 硬约束：**水印不得与任何 MASKED 区域相交**。半透明水印压在实色块上，
 * 会让人以为那个遮罩本身也是半透明的、底下的东西还在——这直接损害核心承诺。
 *
 * 水印是营销手段，不是安全边界。用户裁掉它是预期内的，不做任何对抗。
 */
class WatermarkDrawer(private val text: String = "有码 Yomark") {

    fun placement(canvasW: Int, canvasH: Int, maskedBounds: List<RectF>): WatermarkPlacement =
        placement(canvasW, canvasH, maskedBounds, darkInk = true)

    private fun placement(
        canvasW: Int,
        canvasH: Int,
        maskedBounds: List<RectF>,
        darkInk: Boolean,
    ): WatermarkPlacement {
        val shortEdge = min(canvasW, canvasH).toFloat()
        val h = max(shortEdge * HEIGHT_RATIO, MIN_HEIGHT_PX)
        val margin = shortEdge * MARGIN_RATIO
        val w = measureWidth(h)

        for (corner in ORDER) {
            val r = rectFor(corner, canvasW.toFloat(), canvasH.toFloat(), w, h, margin)
            if (maskedBounds.none { RectF(r).intersect(it) }) {
                return WatermarkPlacement(r, corner, outlined = false, darkInk = darkInk)
            }
        }
        // 四角全被占：回到右下并加不透明描边保证可读
        val r = rectFor(WatermarkCorner.BOTTOM_RIGHT, canvasW.toFloat(), canvasH.toFloat(), w, h, margin)
        return WatermarkPlacement(r, WatermarkCorner.BOTTOM_RIGHT, outlined = true, darkInk = darkInk)
    }

    /** 绘制时机：所有遮罩绘制完成之后，编码之前。 */
    fun draw(canvas: Canvas, source: Bitmap, maskedBounds: List<RectF>): WatermarkPlacement {
        val probe = placement(source.width, source.height, maskedBounds, darkInk = true)
        val dark = isBright(source, probe.rect)
        val p = probe.copy(darkInk = dark)

        val h = p.rect.height()
        val glyph = RectF(p.rect.left, p.rect.top, p.rect.left + h, p.rect.bottom)
        val ink = if (dark) Color.argb(150, 0, 0, 0) else Color.argb(170, 255, 255, 255)
        val counterInk = if (dark) Color.argb(200, 255, 255, 255) else Color.argb(200, 0, 0, 0)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ink }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = counterInk
            strokeWidth = max(1f, h * 0.06f)
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = h * 0.72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }

        // 图标：圆角方块上一条横杠，与启动图标同构
        val radius = h * 0.22f
        canvas.drawRoundRect(glyph, radius, radius, fill)
        val bar = RectF(
            glyph.left + h * 0.22f, glyph.centerY() - h * 0.09f,
            glyph.right - h * 0.22f, glyph.centerY() + h * 0.09f,
        )
        canvas.drawRoundRect(bar, h * 0.05f, h * 0.05f, Paint(fill).apply { color = counterInk })

        val baseline = p.rect.bottom - (h - textPaint.textSize) / 2f - textPaint.descent() * 0.6f
        val textX = glyph.right + h * GAP_RATIO
        if (p.outlined) {
            canvas.drawText(text, textX, baseline, Paint(textPaint).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1.5f, h * 0.10f)
                color = counterInk
                alpha = 255
            })
            canvas.drawRoundRect(glyph, radius, radius, stroke)
        }
        canvas.drawText(text, textX, baseline, textPaint)
        return p
    }

    private fun measureWidth(h: Float): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = h * 0.72f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        return h + h * GAP_RATIO + paint.measureText(text)
    }

    private fun rectFor(
        corner: WatermarkCorner,
        canvasW: Float,
        canvasH: Float,
        w: Float,
        h: Float,
        margin: Float,
    ): RectF = when (corner) {
        WatermarkCorner.BOTTOM_RIGHT -> RectF(canvasW - margin - w, canvasH - margin - h, canvasW - margin, canvasH - margin)
        WatermarkCorner.BOTTOM_LEFT -> RectF(margin, canvasH - margin - h, margin + w, canvasH - margin)
        WatermarkCorner.TOP_LEFT -> RectF(margin, margin, margin + w, margin + h)
        WatermarkCorner.TOP_RIGHT -> RectF(canvasW - margin - w, margin, canvasW - margin, margin + h)
    }

    /** 落点区域偏亮 → 用半透明黑；偏暗 → 用半透明白。 */
    private fun isBright(source: Bitmap, rect: RectF): Boolean {
        var sum = 0.0
        var n = 0
        val stepX = max(1, (rect.width() / 8f).toInt())
        val stepY = max(1, (rect.height() / 4f).toInt())
        var x = rect.left.toInt().coerceIn(0, source.width - 1)
        while (x < rect.right.toInt().coerceIn(0, source.width - 1)) {
            var y = rect.top.toInt().coerceIn(0, source.height - 1)
            while (y < rect.bottom.toInt().coerceIn(0, source.height - 1)) {
                val c = source.getPixel(x, y)
                sum += 0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)
                n++
                y += stepY
            }
            x += stepX
        }
        return if (n == 0) true else sum / n > 140.0
    }

    private companion object {
        const val HEIGHT_RATIO = 0.04f
        const val MARGIN_RATIO = 0.03f
        const val MIN_HEIGHT_PX = 24f
        const val GAP_RATIO = 0.28f
        val ORDER = listOf(
            WatermarkCorner.BOTTOM_RIGHT,
            WatermarkCorner.BOTTOM_LEFT,
            WatermarkCorner.TOP_LEFT,
            WatermarkCorner.TOP_RIGHT,
        )
    }
}
