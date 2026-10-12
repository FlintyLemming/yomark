package moe.flinty.yomark.render

import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle

/**
 * 码的绘制顺序。预览（ImageCanvas）与导出（Exporter）共用，所见即所得靠它。
 *
 * 每块码有自己的样式之后（spec §7.5 修订），顺序就不只是谁盖住谁了：马赛克、模糊、开了近似色的色块要读图上的像素，
 * 而导出是在同一张位图上一块接一块地画，先画上去的色块会被后面读像素的样式读进去。
 * 比如近似色的采样环碰到隔壁的天蓝色块，导出时就取成了偏蓝的颜色；预览读的却是原图——两边就对不上了。
 *
 * 所以先画读像素的，再画直接上色的（固定颜色的色块、马克笔）；同一组里大的先画、小的盖在上面，
 * 与 GestureRules.hitTest 的「取最小」互为对应。
 */
object MaskOrder {

    /** 这一块要读底下的像素：画在直接上色的之前。色块开了近似色也要读，它取的是四周的颜色。 */
    fun readsPixels(look: MaskLook): Boolean = when (look.style) {
        MaskStyle.PIXELATE, MaskStyle.BLUR -> true
        MaskStyle.SOLID -> look.options.solidNear
        MaskStyle.MARKER -> false
    }

    fun forDrawing(items: List<MaskItem>): List<MaskItem> =
        items.sortedWith(
            compareBy<MaskItem> { !readsPixels(it.look) }.thenByDescending { it.quad.area() }
        )
}
