package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle

/**
 * 实色块（默认）：沿 Quad 描路径填充不透明色。不可还原。
 *
 * 颜色是 [MaskOptions.solidColor]；开了近似色（[MaskOptions.solidNear]）时每块各取四周的颜色（见 [NearColor]），
 * 取不到（框盖满了整张图）就还是 solidColor。
 */
class SolidRenderer : MaskRenderer {
    override val style = MaskStyle.SOLID

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        paint.color = colorFor(source, quad, options)
        paint.alpha = 255                      // 不透明是安全属性，不接受 options 覆盖
        canvas.drawPath(quad.toPath(), paint)
    }

    private fun colorFor(source: Bitmap, quad: Quad, options: MaskOptions): Int =
        (if (options.solidNear) NearColor.around(source, quad, options.renderScale) else null) ?: options.solidColor
}
