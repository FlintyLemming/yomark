package com.youma.app.ui.canvas

import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * 类型小标签的摆放：贴在自己框的四周任意一处，避开别的框和已经摆好的标签。
 *
 * 标签固定贴左上角时，上一行的框正好被下一行的标签压住（实机：一列日期框的「日期时间」
 * 一个叠一个），用户看不清被压住的那块还要不要打码。这里改成在框四周挑一个位置：
 * 上、下两边沿水平方向滑，左、右两边沿竖直方向滑，按偏好顺序试，取遮挡最少的那个；
 * 一处都不挡就直接用，所以不拥挤时仍是原来的左上角。
 *
 * 纯几何，图像坐标；标签量好的宽高由调用方给。
 */
object LabelLayout {

    /** 一个要摆的标签：[owner] 是它所属的框在 boxes 里的下标。 */
    data class Request(val owner: Int, val width: Float, val height: Float)

    /** 沿一条边滑动的取样点，按偏好排：先两端（左/上对齐最先），再中间。 */
    private val SLIDE = floatArrayOf(0f, 1f, 0.5f, 0.25f, 0.75f)

    /**
     * @param boxes 画面上所有框的外接矩形，都要避让（包括标签自己的框：不压自己的内容）
     * @param requests 要摆的标签；按给出的顺序贪心摆放，先摆的先挑
     * @param area 标签必须留在里面的范围（图像范围）
     * @param gap 标签与自己的框之间留的空隙
     * @return 与 [requests] 一一对应的标签矩形
     */
    fun place(boxes: List<RectF>, requests: List<Request>, area: RectF, gap: Float): List<RectF> {
        val placed = ArrayList<RectF>(requests.size)
        for (req in requests) {
            val box = boxes[req.owner]
            // 只有落在候选范围附近的框和标签才可能被挡到，先筛一遍，密集截图上省得逐个比
            val reach = RectF(
                box.left - req.width - gap, box.top - req.height - gap,
                box.right + req.width + gap, box.bottom + req.height + gap,
            )
            val nearby = (boxes.asSequence() + placed.asSequence())
                .filter { RectF.intersects(it, reach) }
                .toList()

            var best: RectF? = null
            var bestCost = Float.MAX_VALUE
            for (candidate in candidates(box, req.width, req.height, gap)) {
                val rect = clampInto(candidate, area)
                val cost = nearby.sumOf { overlap(rect, it).toDouble() }.toFloat()
                if (cost < bestCost) {
                    best = rect
                    bestCost = cost
                    if (cost == 0f) break
                }
            }
            placed += best!!
        }
        return placed
    }

    /** 框四周的候选位置，按偏好排：上、下、右、左，每条边上按 [SLIDE] 滑。 */
    private fun candidates(box: RectF, w: Float, h: Float, gap: Float): Sequence<RectF> = sequence {
        val above = box.top - gap - h
        val below = box.bottom + gap
        val toRight = box.right + gap
        val toLeft = box.left - gap - w
        // 框比标签窄时 slack 为负：标签以框为中心向两侧探出，两端对齐仍各取一次
        val xSlack = box.width() - w
        val ySlack = box.height() - h
        for (y in floatArrayOf(above, below)) {
            for (t in SLIDE) yield(rectAt(box.left + xSlack * t, y, w, h))
        }
        for (x in floatArrayOf(toRight, toLeft)) {
            for (t in SLIDE) yield(rectAt(x, box.top + ySlack * t, w, h))
        }
    }

    private fun rectAt(left: Float, top: Float, w: Float, h: Float) = RectF(left, top, left + w, top + h)

    /** 平移进 [area]；放不下的那个方向贴左/上边。 */
    private fun clampInto(r: RectF, area: RectF): RectF {
        val dx = when {
            r.width() >= area.width() || r.left < area.left -> area.left - r.left
            r.right > area.right -> area.right - r.right
            else -> 0f
        }
        val dy = when {
            r.height() >= area.height() || r.top < area.top -> area.top - r.top
            r.bottom > area.bottom -> area.bottom - r.bottom
            else -> 0f
        }
        return RectF(r.left + dx, r.top + dy, r.right + dx, r.bottom + dy)
    }

    private fun overlap(a: RectF, b: RectF): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w > 0f && h > 0f) w * h else 0f
    }
}
