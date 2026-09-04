package com.youma.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.youma.app.core.geometry.Quad

enum class HandleCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * 手动框的选中态（spec §7.3）：四角手柄 + 删除按钮，拖框体移动、拖手柄调整大小。
 *
 * 只对 kind == MANUAL 的框出现。规则命中的候选进不了选中态——
 * 它们不可删也不可改，能做的只有 MASKED ⇄ OUTLINED 切换（spec §4.2 的不对称规则）。
 *
 * 手动框一律是轴对齐的（GestureRules.quadFromDrag 保证），所以这里用 bounds 运算是安全的。
 */
object SelectionHandles {

    fun handleRects(quad: Quad, sizePx: Float): Map<HandleCorner, RectF> {
        val b = quad.bounds()
        val h = sizePx / 2f
        fun at(x: Float, y: Float) = RectF(x - h, y - h, x + h, y + h)
        return mapOf(
            HandleCorner.TOP_LEFT to at(b.left, b.top),
            HandleCorner.TOP_RIGHT to at(b.right, b.top),
            HandleCorner.BOTTOM_RIGHT to at(b.right, b.bottom),
            HandleCorner.BOTTOM_LEFT to at(b.left, b.bottom),
        )
    }

    /** 命中判定放宽到手柄的 1.5 倍，手指比图标粗。 */
    fun hitHandle(quad: Quad, point: PointF, sizePx: Float): HandleCorner? =
        handleRects(quad, sizePx * 1.5f).entries
            .firstOrNull { it.value.contains(point.x, point.y) }
            ?.key

    fun deleteButtonRect(quad: Quad, sizePx: Float): RectF {
        val b = quad.bounds()
        val h = sizePx / 2f
        val cx = b.right
        val cy = b.top - sizePx * 1.2f
        return RectF(cx - h, cy - h, cx + h, cy + h)
    }

    fun move(quad: Quad, dx: Float, dy: Float): Quad {
        val b = quad.bounds()
        return Quad.fromRect(RectF(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy))
    }

    /**
     * 拖某个角到 [to]。对角固定不动。
     * 拖过头时不允许翻转，也不允许短边小于 minShortEdge——与新建框用同一个下限。
     */
    fun resize(quad: Quad, corner: HandleCorner, to: PointF, minShortEdge: Float): Quad {
        val b = quad.bounds()
        var left = b.left; var top = b.top; var right = b.right; var bottom = b.bottom
        when (corner) {
            HandleCorner.TOP_LEFT -> { left = to.x; top = to.y }
            HandleCorner.TOP_RIGHT -> { right = to.x; top = to.y }
            HandleCorner.BOTTOM_RIGHT -> { right = to.x; bottom = to.y }
            HandleCorner.BOTTOM_LEFT -> { left = to.x; bottom = to.y }
        }
        // 不翻转：把移动的那条边推回去，保证与固定边至少相隔 minShortEdge
        when (corner) {
            HandleCorner.TOP_LEFT -> {
                if (left > right - minShortEdge) left = right - minShortEdge
                if (top > bottom - minShortEdge) top = bottom - minShortEdge
            }
            HandleCorner.TOP_RIGHT -> {
                if (right < left + minShortEdge) right = left + minShortEdge
                if (top > bottom - minShortEdge) top = bottom - minShortEdge
            }
            HandleCorner.BOTTOM_RIGHT -> {
                if (right < left + minShortEdge) right = left + minShortEdge
                if (bottom < top + minShortEdge) bottom = top + minShortEdge
            }
            HandleCorner.BOTTOM_LEFT -> {
                if (left > right - minShortEdge) left = right - minShortEdge
                if (bottom < top + minShortEdge) bottom = top + minShortEdge
            }
        }
        return Quad.fromRect(RectF(left, top, right, bottom))
    }
}
