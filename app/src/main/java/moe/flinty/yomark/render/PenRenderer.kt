package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle

/**
 * 画笔：不透明的线。手画的一笔照着手指走过的地方画；拉的框描一圈框线，线整个落在框里面。
 *
 * **安全性：不遮蔽，仅标记。** 和马克笔一样是拿来圈出来给人看的，线底下的东西照样看得见。
 */
class PenRenderer : MaskRenderer {

    override val style = MaskStyle.PEN

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions, ink: InkPath?) {
        val half = InkStroke.halfWidth(style, options, source)
        val paint = InkStroke.paint(options.penColor, half)
        if (ink != null) {
            canvas.drawPath(InkStroke.path(ink, quad, half), paint)
            return
        }
        // 框线往里让半个笔宽：整条线在框里，拖手柄、选中框都对得上
        val b = quad.bounds()
        val dx = half.coerceAtMost(b.width() / 2f)
        val dy = half.coerceAtMost(b.height() / 2f)
        canvas.drawRect(b.left + dx, b.top + dy, b.right - dx, b.bottom - dy, paint)
    }
}
