package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.min

/** 马克笔、画笔共用的线：圆头圆角，手画的一笔照着手指走过的点连成平滑的曲线。 */
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
     * 只有一个点（点了一下）时画成一个圆点：圆头的零长线段。
     */
    fun path(ink: InkPath, quad: Quad, halfWidth: Float): Path {
        val pts = Ink.toImage(ink, quad, halfWidth)
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

    private fun midpoint(a: PointF, b: PointF) = PointF((a.x + b.x) / 2f, (a.y + b.y) / 2f)
}
