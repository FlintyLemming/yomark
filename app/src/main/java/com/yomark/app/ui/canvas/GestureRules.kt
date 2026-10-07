package com.yomark.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.MaskItem
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手势的判定规则（spec §7.3）。全是纯函数，Compose 只负责喂事件。
 * 分工是绘图类 app 的通行做法：一指画、两指导航，不设模式切换按钮。
 */
object GestureRules {

    /** 类型小标签只在缩放比 ≥ 0.5 时绘制，避免密集截图上标签糊成一片。 */
    const val LABEL_MIN_SCALE = 0.5f

    /** 手动框的短边下限（dp），防误触产生 1px 框。 */
    const val MIN_BOX_DP = 16f

    fun isDrag(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        hypot(dx, dy) > touchSlopPx

    fun acceptsBox(quad: Quad, minShortEdgePx: Float): Boolean =
        quad.shortEdge() >= minShortEdgePx

    /** 起点终点归一化成轴对齐四边形。手动框永远是正的。 */
    fun quadFromDrag(a: PointF, b: PointF): Quad = Quad.fromRect(
        RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    )

    /**
     * 命中测试：取面积最小的命中项。
     * 渲染时大块先画、小块盖在上面，用户点到的应该是他看到的那个。
     */
    fun hitTest(items: List<MaskItem>, imagePoint: PointF): MaskItem? =
        items.filter { it.quad.contains(imagePoint) }.minByOrNull { it.quad.area() }
}
