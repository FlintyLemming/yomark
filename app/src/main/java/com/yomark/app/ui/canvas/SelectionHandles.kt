package com.yomark.app.ui.canvas

import android.graphics.Point
import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class HandleCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * 手动框的选中态（spec §7.3）：四角手柄 + 框上方的「删除」工具条，拖框体移动、拖手柄调整大小。
 *
 * 只对手动框出现。规则命中的候选进不了选中态——
 * 它们不可删也不可改，能做的只有 MASKED ⇄ OUTLINED 切换（spec §4.2 的不对称规则）。
 *
 * 手动框一律是轴对齐的（GestureRules.quadFromDrag 保证），所以这里用 bounds 运算是安全的。
 */
object SelectionHandles {

    fun cornerPoint(quad: Quad, corner: HandleCorner): PointF {
        val b = quad.bounds()
        return when (corner) {
            HandleCorner.TOP_LEFT -> PointF(b.left, b.top)
            HandleCorner.TOP_RIGHT -> PointF(b.right, b.top)
            HandleCorner.BOTTOM_RIGHT -> PointF(b.right, b.bottom)
            HandleCorner.BOTTOM_LEFT -> PointF(b.left, b.bottom)
        }
    }

    fun handleRects(quad: Quad, sizePx: Float): Map<HandleCorner, RectF> {
        val h = sizePx / 2f
        return HandleCorner.entries.associateWith { corner ->
            val p = cornerPoint(quad, corner)
            RectF(p.x - h, p.y - h, p.x + h, p.y + h)
        }
    }

    /**
     * 命中判定放宽到手柄的 1.5 倍，手指比图标粗。
     * 小框上几个角的命中区会叠在一起，取离手指最近的那个角，免得总是抓到左上角。
     */
    fun hitHandle(quad: Quad, point: PointF, sizePx: Float): HandleCorner? =
        handleRects(quad, sizePx * 1.5f).entries
            .filter { it.value.contains(point.x, point.y) }
            .minByOrNull { hypot(it.value.centerX() - point.x, it.value.centerY() - point.y) }
            ?.key

    /**
     * 工具条左上角摆在哪（屏幕像素）。[box] 是选中框在屏幕上的范围，[areaW]×[areaH] 是画布。
     *
     * 优先放在框上方、与框水平居中；上方放不下就放到框下方，再放不下（框几乎占满画布）就贴着画布顶边。
     * 水平方向不出画布。框整个被拖出了视野时返回 null：工具条不该孤零零地贴在画布边上。
     */
    fun toolbarPosition(
        box: RectF,
        barW: Int,
        barH: Int,
        areaW: Int,
        areaH: Int,
        gap: Float,
        margin: Float,
    ): Point? {
        if (box.right < 0f || box.left > areaW || box.bottom < 0f || box.top > areaH) return null
        val above = box.top - gap - barH
        val below = box.bottom + gap
        val y = when {
            above >= margin -> above
            below + barH <= areaH - margin -> below
            else -> margin
        }
        val x = (box.centerX() - barW / 2f).coerceAtMost(areaW - margin - barW).coerceAtLeast(margin)
        return Point(x.roundToInt(), y.roundToInt())
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
