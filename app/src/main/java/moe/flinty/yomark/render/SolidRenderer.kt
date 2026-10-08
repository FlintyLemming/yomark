package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle

/** 实色块（默认）：沿 Quad 描路径填充不透明色。不可还原。 */
class SolidRenderer : MaskRenderer {
    override val style = MaskStyle.SOLID

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        paint.color = options.solidColor
        paint.alpha = 255                      // 不透明是安全属性，不接受 options 覆盖
        canvas.drawPath(quad.toPath(), paint)
    }
}
