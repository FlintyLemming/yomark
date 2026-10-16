package moe.flinty.yomark.ui.canvas

import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.min

/**
 * 图像坐标 ↔ 屏幕坐标。
 * scale 的语义是「屏幕像素 / 图像像素」。
 * 屏幕点 = 图像点 * scale + offset。
 */
data class Viewport(val scale: Float, val offsetX: Float, val offsetY: Float) {

    fun imageToScreen(p: PointF) = PointF(p.x * scale + offsetX, p.y * scale + offsetY)

    fun imageToScreen(r: RectF) = RectF(
        r.left * scale + offsetX, r.top * scale + offsetY,
        r.right * scale + offsetX, r.bottom * scale + offsetY,
    )

    fun screenToImage(p: PointF) = PointF((p.x - offsetX) / scale, (p.y - offsetY) / scale)

    fun pan(dx: Float, dy: Float) = copy(offsetX = offsetX + dx, offsetY = offsetY + dy)

    /** 以屏幕上的 pivot 为不动点缩放。 */
    fun zoomAround(pivot: PointF, factor: Float): Viewport {
        val ns = scale * factor
        return Viewport(
            scale = ns,
            offsetX = pivot.x - (pivot.x - offsetX) * factor,
            offsetY = pivot.y - (pivot.y - offsetY) * factor,
        )
    }

    fun matrix(): Matrix = Matrix().apply {
        setScale(scale, scale)
        postTranslate(offsetX, offsetY)
    }

    /**
     * 把缩放收进 [fitScale, fitScale * MAX_SCALE_FACTOR]，
     * 并保证图像不会被拖出视口：图像比视口大时贴边，比视口小时居中。
     */
    fun clamped(imageW: Int, imageH: Int, viewW: Int, viewH: Int, fitScale: Float): Viewport {
        val s = scale.coerceIn(fitScale, fitScale * MAX_SCALE_FACTOR)
        val w = imageW * s
        val h = imageH * s
        // 缩放被夹住时，围绕视口中心重算 offset，避免夹紧后画面跳动
        val k = if (abs(scale) < 1e-6f) 1f else s / scale
        var ox = viewW / 2f - (viewW / 2f - offsetX) * k
        var oy = viewH / 2f - (viewH / 2f - offsetY) * k
        ox = if (w <= viewW) (viewW - w) / 2f else ox.coerceIn(viewW - w, 0f)
        oy = if (h <= viewH) (viewH - h) / 2f else oy.coerceIn(viewH - h, 0f)
        return Viewport(s, ox, oy)
    }

    /**
     * 平移到让图像上的 [area] 落进 viewW × viewH 的视口里（四边各留 [margin]），已经在里面就不动；放不下时让它的左上角露出来。
     * 大爆炸的面板出来、画布变矮时用：放大着看的图上，选中的字可能落到了画布下面。之后照常要 [clamped]。
     */
    fun revealing(area: RectF, viewW: Int, viewH: Int, margin: Float): Viewport {
        val r = imageToScreen(area)
        fun shift(lo: Float, hi: Float, size: Int): Float = when {
            hi > size - margin -> maxOf(size - margin - hi, margin - lo)
            lo < margin -> margin - lo
            else -> 0f
        }
        return pan(shift(r.left, r.right, viewW), shift(r.top, r.bottom, viewH))
    }

    companion object {
        const val MAX_SCALE_FACTOR = 8f

        fun fit(imageW: Int, imageH: Int, viewW: Int, viewH: Int): Viewport {
            if (imageW <= 0 || imageH <= 0 || viewW <= 0 || viewH <= 0) return Viewport(1f, 0f, 0f)
            val s = min(viewW.toFloat() / imageW, viewH.toFloat() / imageH)
            return Viewport(s, (viewW - imageW * s) / 2f, (viewH - imageH * s) / 2f)
        }
    }
}
