package com.dama.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskStyle
import kotlin.math.sin

/**
 * 马克笔（spec §8）：半透明色叠加，路径带轻微手绘抖动。
 *
 * **安全性：不遮蔽，仅标记。** 这是六种样式里唯一不遮住内容的，
 * 用途是「圈出来给人看」而不是「盖住不让人看」。UI 必须标注。
 */
class MarkerRenderer : MaskRenderer {

    override val style = MaskStyle.MARKER

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
            color = options.markerColor
        }
        canvas.drawPath(jitter(quad), paint)
    }

    /** 沿边插值几个点并加正弦扰动，让边缘像手画的而不是尺子画的。 */
    private fun jitter(quad: Quad): Path {
        val pts = quad.points()
        val amplitude = quad.shortEdge() * JITTER_RATIO
        val path = Path()
        pts.forEachIndexed { i, p ->
            val next = pts[(i + 1) % pts.size]
            for (s in 0 until SEGMENTS) {
                val t = s.toFloat() / SEGMENTS
                val phase = (i * SEGMENTS + s).toFloat()
                val nx = -(next.y - p.y)
                val ny = (next.x - p.x)
                val len = kotlin.math.hypot(nx, ny).coerceAtLeast(1e-3f)
                val off = sin(phase * 1.7f) * amplitude
                val x = p.x + (next.x - p.x) * t + nx / len * off
                val y = p.y + (next.y - p.y) * t + ny / len * off
                if (i == 0 && s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        path.close()
        return path
    }

    private companion object {
        const val SEGMENTS = 6
        const val JITTER_RATIO = 0.03f
    }
}
