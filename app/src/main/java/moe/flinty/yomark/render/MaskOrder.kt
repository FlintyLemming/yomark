package moe.flinty.yomark.render

import android.graphics.Bitmap
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle

/**
 * 码的绘制顺序。预览（ImageCanvas）与导出（Exporter）共用，所见即所得靠它。
 *
 * 后来的盖在先来的上面：识别出的在底下（同一批里大的先画、小的盖在上面），用户自己加的（手动框、点一行字打上的码）
 * 按加的先后叠上去。
 * 在色块上再拉一个模糊框，模糊就该在色块上面——早先读像素的样式一律先画，模糊反倒被压在色块底下。
 *
 * 读像素的样式（马赛克、模糊）读的是画到它这一层时底下的样子：导出本来就在同一张位图上一块接一块地画，
 * 模糊盖在色块上，糊的是色块，色块挡住的字仍然看不见；预览照同一个顺序画，读像素的码照它底下那时的样子算（见 PreparedMasks）。
 *
 * 近似色是例外：它要的是背景的颜色，隔壁先画上去的天蓝色块不该掺进来。所以两边都先在原图上把近似色取好
 * （[pinNearColor]），再开始一块块地画。
 */
object MaskOrder {

    /** 这一块要读底下的像素。近似色不算：它在开画之前就从原图上取好了（见 [pinNearColor]）。 */
    fun readsPixels(look: MaskLook): Boolean = when (look.style) {
        MaskStyle.PIXELATE, MaskStyle.BLUR -> true
        MaskStyle.SOLID, MaskStyle.MARKER, MaskStyle.PEN, MaskStyle.TEXT -> false
    }

    fun forDrawing(items: List<MaskItem>): List<MaskItem> {
        val (byUser, detected) = items.partition { it.byUser }
        return detected.sortedByDescending { it.quad.area() } + byUser
    }

    /**
     * 开了近似色的，在 [original]（还没画任何码的图）上把颜色取好、写成固定颜色。
     * 这样后面一块块地画时，近似色的采样环碰到隔壁已经画上去的码也不受影响。
     */
    fun pinNearColor(options: MaskOptions, original: Bitmap, quad: Quad): MaskOptions =
        if (options.solidNear) {
            options.copy(solidNear = false, solidColor = NearColor.around(original, quad, options.renderScale))
        } else options
}
