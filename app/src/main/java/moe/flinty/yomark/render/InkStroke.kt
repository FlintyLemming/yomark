package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.hypot
import kotlin.math.min

/** 马克笔、画笔共用的线：圆头圆角，手画的一笔照着手指走过的点连成平滑的曲线；画笔的箭头见 [drawArrow]。 */
internal object InkStroke {

    /** [style] 在 [source] 上的半个笔宽（见 MaskOptions.halfWidth）。 */
    fun halfWidth(style: MaskStyle, options: MaskOptions, source: Bitmap): Float =
        options.halfWidth(style, min(source.width, source.height).toFloat())

    fun paint(color: Int, halfWidth: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        this.color = color
        strokeWidth = halfWidth * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /**
     * 手画的一笔在 [quad] 里的路径。相邻两点之间取中点、以点为控制点连二次曲线，折线的拐角就圆了。
     * 只有一个点（点了一下）时画成一个圆点：圆头的零长线段。箭头只取它那一条直线。
     */
    fun path(ink: InkPath, quad: Quad, halfWidth: Float): Path {
        val pts = Ink.toImage(ink, quad, Ink.margin(halfWidth, ink.arrow))
        val path = Path()
        if (pts.isEmpty()) return path
        path.moveTo(pts[0].x, pts[0].y)
        if (pts.size == 1) {
            path.lineTo(pts[0].x, pts[0].y)
            return path
        }
        for (i in 1 until pts.size - 1) {
            val mid = midpoint(pts[i], pts[i + 1])
            path.quadTo(pts[i].x, pts[i].y, mid.x, mid.y)
        }
        path.lineTo(pts.last().x, pts.last().y)
        return path
    }

    /**
     * 一支箭头（[ink] 的第一个点是尾、最后一个点是箭尖）：一条直线，箭尖上一个实心的三角，和线一个颜色。
     * 三角的大小按笔宽算（见 Ink.ARROW_HEAD_LENGTH），箭头太短时头跟着缩，最长占全长的 [MAX_HEAD_SHARE]，看着还是一支箭头。
     * 线只画到三角的底边：圆头藏在三角里，不从箭尖上冒出来。头缩了线也跟着变细，不比头还宽。
     */
    fun drawArrow(canvas: Canvas, ink: InkPath, quad: Quad, halfWidth: Float, paint: Paint) {
        val pts = Ink.toImage(ink, quad, Ink.margin(halfWidth, arrow = true))
        val tail = pts.firstOrNull() ?: return
        val tip = pts.last()
        val length = hypot(tip.x - tail.x, tip.y - tail.y)
        if (length < 1e-3f) {
            canvas.drawPoint(tip.x, tip.y, paint)
            return
        }
        val ux = (tip.x - tail.x) / length
        val uy = (tip.y - tail.y) / length
        val head = min(halfWidth * Ink.ARROW_HEAD_LENGTH, length * MAX_HEAD_SHARE)
        val side = head * Ink.ARROW_HEAD_HALF / Ink.ARROW_HEAD_LENGTH
        val baseX = tip.x - ux * head
        val baseY = tip.y - uy * head
        // 线的圆头在底边中点上，半径不超过底边中点到两条斜边的距离，整个落在三角里
        val shaft = min(halfWidth, head * side / hypot(head, side))
        canvas.drawLine(tail.x, tail.y, baseX, baseY, if (shaft < halfWidth) Paint(paint).apply { strokeWidth = shaft * 2f } else paint)
        val triangle = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(baseX - uy * side, baseY + ux * side)
            lineTo(baseX + uy * side, baseY - ux * side)
            close()
        }
        canvas.drawPath(triangle, Paint(paint).apply { style = Paint.Style.FILL })
    }

    /** 箭头的头最多占全长的这么多。 */
    private const val MAX_HEAD_SHARE = 0.45f

    private fun midpoint(a: PointF, b: PointF) = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}
