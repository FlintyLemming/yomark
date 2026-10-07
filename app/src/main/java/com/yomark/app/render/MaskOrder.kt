package com.yomark.app.render

import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskStyle

/**
 * 码的绘制顺序。预览（ImageCanvas）与导出（Exporter）共用，所见即所得靠它。
 *
 * 每块码有自己的样式之后（spec §7.5 修订），顺序就不只是谁盖住谁了：抹除、马赛克、模糊要读图上的像素，
 * 而导出是在同一张位图上一块接一块地画，先画上去的色块会被后面读像素的样式读进去。
 * 比如抹除的采样环碰到隔壁的天蓝色块，导出时颜色一杂就降级成色块；预览读的却是原图，不降级——两边就对不上了。
 *
 * 所以先画读像素的三种，再画直接上色的三种（色块、表情、马克笔）；同一组里大的先画、小的盖在上面，
 * 与 GestureRules.hitTest 的「取最小」互为对应。
 */
object MaskOrder {

    /** 这种样式要读底下的像素：画在直接上色的样式之前。 */
    fun readsPixels(style: MaskStyle): Boolean = when (style) {
        MaskStyle.ERASE, MaskStyle.PIXELATE, MaskStyle.BLUR -> true
        MaskStyle.SOLID, MaskStyle.EMOJI, MaskStyle.MARKER -> false
    }

    fun forDrawing(items: List<MaskItem>): List<MaskItem> =
        items.sortedWith(
            compareBy<MaskItem> { !readsPixels(it.look.style) }.thenByDescending { it.quad.area() }
        )
}
