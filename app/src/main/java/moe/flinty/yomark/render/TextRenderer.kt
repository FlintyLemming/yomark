package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.util.SizeF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.min

/**
 * 文本：在图上写一行字（2026-10-15）。
 *
 * **安全性：不遮蔽，仅标记。** 和马克笔、画笔一样是写给人看的，字底下的东西照样看得见。
 */
class TextRenderer : MaskRenderer {

    override val style = MaskStyle.TEXT

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions, ink: InkPath?, text: String?) {
        if (text.isNullOrBlank()) return
        TextStamp.draw(canvas, quad, text, options)
    }
}

/**
 * 一行字和包住它的框。编辑器摆框（EditorViewModel）和渲染器画字用的是这同一套量法。
 *
 * 框正好包住这行字，四周留一点空（[PAD_X]、[PAD_Y] 个字号）。画的时候字号由框定（[sizeIn]），不看参数里的字号：
 * 导出时框按比例换到原图上，字跟着到位；拖角放大缩小，字跟着变大变小，和选中框对得上。
 * 参数里的字号（MaskOptions.textSize）只在摆框时用。
 *
 * 垫着底色（MaskOptions.textBackground）时框里铺一块圆角底，字取黑白里和底色对得开的那个；不垫底色时字就是那个颜色。
 */
object TextStamp {

    /** 字号为 [size] 时这行字连同四周的空有多宽多高。 */
    fun boxSize(text: String, size: Float): SizeF =
        SizeF((width(text) + PAD_X * 2f) * size, (lineHeight + PAD_Y * 2f) * size)

    /** 框高 [height] 时的字号：拉框写字时按框的高定字号。 */
    fun sizeForHeight(height: Float): Float = height / (lineHeight + PAD_Y * 2f)

    /**
     * 字号 [size]、中心在 [center] 的框，挪进 [width]×[height] 的图里：出了图的字导出时就没了。
     * 一行比整张图还宽时字号缩到正好写得下。
     */
    fun boxAt(text: String, size: Float, center: PointF, width: Int, height: Int): Quad {
        val natural = boxSize(text, size)
        val fit = if (natural.width > width) width / natural.width else 1f
        val w = natural.width * fit
        val h = natural.height * fit
        val left = if (w >= width) 0f else (center.x - w / 2f).coerceIn(0f, width - w)
        val top = if (h >= height) 0f else (center.y - h / 2f).coerceIn(0f, height - h)
        return Quad.fromRect(RectF(left, top, left + w, top + h))
    }

    /** 框 [quad] 里放得下的字号：宽、高哪一边先顶到就按哪一边。 */
    fun sizeIn(text: String, quad: Quad): Float {
        val b = quad.bounds()
        return min(b.width() / (width(text) + PAD_X * 2f), b.height() / (lineHeight + PAD_Y * 2f))
    }

    /** 照 [quad] 的大小写下 [text]，在框里居中。 */
    fun draw(canvas: Canvas, quad: Quad, text: String, options: MaskOptions) {
        val size = sizeIn(text, quad)
        if (!(size > 0f) || size.isInfinite()) return
        val box = boxSize(text, size)
        val b = quad.bounds()
        val left = b.centerX() - box.width / 2f
        val top = b.centerY() - box.height / 2f
        val paint = paint(size)
        if (options.textBackground) {
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = options.textColor }
            val radius = size * CORNER
            canvas.drawRoundRect(left, top, left + box.width, top + box.height, radius, radius, fill)
            paint.color = onColor(options.textColor)
        } else {
            paint.color = options.textColor
        }
        canvas.drawText(text, left + PAD_X * size, top + (PAD_Y + ascent) * size, paint)
    }

    /** 垫着 [background] 的底色时字用什么颜色：亮的底上写黑字，暗的底上写白字。 */
    fun onColor(background: Int): Int = if (Color.luminance(background) > 0.5f) Color.BLACK else Color.WHITE

    /** 字号为 1 时这行字多宽。在 [REF_SIZE] 上量再折回来：小字号上量，字距按整像素取，量出来不成比例。 */
    private fun width(text: String): Float = paint(REF_SIZE).measureText(text) / REF_SIZE

    /** 字号为 1 时基线以上、一行有多高。字体定了就不变，量一次。 */
    private val ascent: Float by lazy { -paint(REF_SIZE).fontMetrics.ascent / REF_SIZE }
    private val lineHeight: Float by lazy { paint(REF_SIZE).fontMetrics.let { it.descent - it.ascent } / REF_SIZE }

    /** 每次新建：渲染器在后台线程上也画（见 ComposedMasks），不共用一支笔。 */
    private fun paint(size: Float) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG or Paint.LINEAR_TEXT_FLAG).apply {
        typeface = Typeface.DEFAULT_BOLD
        textSize = size
    }

    private const val REF_SIZE = 100f

    /** 字的左右、上下各留几个字号的空：垫着底色时字不贴边，选中框也不压着字。 */
    const val PAD_X = 0.3f
    const val PAD_Y = 0.12f

    /** 底色的圆角，按字号算。 */
    private const val CORNER = 0.25f
}
