package moe.flinty.yomark.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.TopCrop
import moe.flinty.yomark.render.MaskOrder
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 手势的判定规则（spec §7.3）。全是纯函数，Compose 只负责喂事件。
 * 分工是绘图类 app 的通行做法：一指画、两指导航，不设模式切换按钮。
 */
object GestureRules {

    /** 手动框的短边下限（dp），防误触产生 1px 框。 */
    const val MIN_BOX_DP = 16f

    /**
     * 点字时手指可以偏出那一行多远（dp）。截图上一行字在手机上常常只有十几 dp 高，比手指头还窄：
     * 点在行与行之间的空当里，算点中近的那一行。
     */
    const val LINE_REACH_DP = 6f

    fun isDrag(dx: Float, dy: Float, touchSlopPx: Float): Boolean =
        hypot(dx, dy) > touchSlopPx

    fun acceptsBox(quad: Quad, minShortEdgePx: Float): Boolean =
        quad.shortEdge() >= minShortEdgePx

    /** 起点终点归一化成轴对齐四边形。手动框永远是正的。 */
    fun quadFromDrag(a: PointF, b: PointF): Quad = Quad.fromRect(
        RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    )

    /**
     * 命中测试：取最上面的那块码，即按 MaskOrder 最后画的那块。用户点到的应该是他看到的那个：
     * 识别出的大块先画、小块盖在上面，用户加的后加的盖在先加的上面。
     */
    fun hitTest(items: List<MaskItem>, imagePoint: PointF): MaskItem? =
        MaskOrder.forDrawing(items).lastOrNull { it.quad.contains(imagePoint) }

    /** 点一下点中了什么。 */
    sealed interface TapTarget {
        /** 一块码。 */
        data class Item(val item: MaskItem) : TapTarget

        /** 没打码的一段字：框是文字识别给的这一段（TextLine.segments 里的一个，不是拼好的整行）。 */
        data class Line(val quad: Quad) : TapTarget

        /** 点掉过的一块识别结果：原样打回去。 */
        data class Dismissed(val item: MaskItem) : TapTarget

        /** 顶上要裁掉的那一条（实验室的「裁切状态栏」）：裁着的不裁了，留着状态栏；不裁的又裁上。 */
        data object Crop : TapTarget
    }

    /**
     * 点在 [p] 上点中了什么（spec §7.3，2026-10-14 修订）：先看码（[hitTest]），再看是不是点掉过的识别结果（[dismissed]），
     * 都不是就看是不是没打码的一段字（[lines]，每一段的框）。都不是为 null：点了空白。
     *
     * 点掉的码排在行前面：误点掉了，原地再点一下就是原来那块，不是整行；头像这种底下没字的，也只有这样才点得回来。
     *
     * 顶上要裁掉的那一条（[crop]，2026-10-29）：裁着时压暗盖在最上面，点在上面就是点它，压在底下的码点不着；
     * 点掉了（留着状态栏）排在点掉的码后面、字前面，和点掉的码一样原地再点一下又裁上。状态栏上的时间这几个字因此点不上码。
     *
     * @param dismissed 点掉过的识别结果，后点掉的在后
     * @param reach 点没落在任何一段里时，离一段多近也算点中它（图像像素，见 [LINE_REACH_DP]）
     * @param width 图的宽：点在图外（左右两边的空白）不算点中顶上那一条
     */
    fun tapTarget(
        items: List<MaskItem>,
        lines: List<Quad>,
        p: PointF,
        reach: Float = 0f,
        dismissed: List<MaskItem> = emptyList(),
        crop: TopCrop? = null,
        width: Int = 0,
    ): TapTarget? {
        val band = crop?.takeIf { p.y >= 0f && p.y < it.height && p.x >= 0f && p.x < width }
        if (band?.on == true) return TapTarget.Crop
        hitTest(items, p)?.let { return TapTarget.Item(it) }
        dismissed.lastOrNull { it.quad.contains(p) }?.let { return TapTarget.Dismissed(it) }
        if (band != null) return TapTarget.Crop
        return lineAt(lines, p, reach)?.let { TapTarget.Line(it) }
    }

    /** [p] 所在的那一段：落在几段里（斜着的水印压在字上）取最小的那段；一段都不在，取 [reach] 以内最近的。 */
    fun lineAt(lines: List<Quad>, p: PointF, reach: Float): Quad? =
        lines.filter { it.contains(p) }.minByOrNull { it.area() }
            ?: lines.map { it to it.distanceTo(p) }.filter { it.second <= reach }.minByOrNull { it.second }?.first
}
