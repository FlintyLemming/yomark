package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle

/**
 * 实色块（默认）：沿 Quad 描路径填充不透明色。不可还原。
 *
 * 颜色是 [MaskOptions.solidColor]；开了近似色（[MaskOptions.solidNear]）时每块各取四周的颜色（见 [NearColor]）。
 * 近似色总能取到一种颜色，开着它就不会再用 solidColor，背景再杂、框盖满整张图也一样。
 */
class SolidRenderer : MaskRenderer {
    override val style = MaskStyle.SOLID

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions, ink: InkPath?, text: String?) {
        paint.color = colorFor(source, quad, options)
        paint.alpha = 255                      // 不透明是安全属性，不接受 options 覆盖
        canvas.drawPath(quad.toPath(), paint)
    }

    private fun colorFor(source: Bitmap, quad: Quad, options: MaskOptions): Int =
        if (options.solidNear) NearColor.around(source, quad, options.renderScale) else options.solidColor
}
