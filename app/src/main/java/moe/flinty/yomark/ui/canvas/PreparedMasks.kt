package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.PixelMaskRenderer
import moe.flinty.yomark.render.PreparedMask

/**
 * 预览里读像素的码（马赛克、模糊）各自算好的样子（见 PixelMaskRenderer），跨帧留着。
 *
 * 缩放、平移、点选时画布每帧都重画。没有哪块码压着别的码时不走合成图（见 MaskComposite），逐块现算的话
 * 十几块模糊一帧要近十毫秒，大框一块就要好几毫秒；留着算好的小图，重画只是贴一下，贴的还是同一张图、同一个画法。
 *
 * 按（样式，框，参数）认：拖框、拖滑杆时变了的那块当帧重算，别的照旧用留着的。画布上已经没有的码随时扔掉（见 [retain]），
 * 换了图全部作废。算好的位图不回收：上一帧的显示列表可能还引用着它，交给 GC。
 */
internal class PreparedMasks {
    private data class Key(val style: MaskStyle, val quad: Quad, val options: MaskOptions)

    private var source: Bitmap? = null
    private val kept = HashMap<Key, PreparedMask>()
    private var retainedFor: List<MaskItem>? = null

    /** 照 [renderer] 画 [item]，算过的直接贴。 */
    fun draw(canvas: Canvas, image: SourceImage, item: MaskItem, renderer: PixelMaskRenderer) {
        reset(image)
        val key = keyOf(image, item)
        kept.getOrPut(key) { renderer.prepare(image.bitmap, key.quad, key.options) ?: NOTHING }.draw(canvas)
    }

    /** 只留 [items]（加上正在揭开的 [leaving]）里的码算好的样子。码没变就不用再比。 */
    fun retain(image: SourceImage, items: List<MaskItem>, leaving: List<MaskItem> = emptyList()) {
        reset(image)
        if (items === retainedFor && leaving.isEmpty()) return
        retainedFor = items.takeIf { leaving.isEmpty() }
        val keep = HashSet<Key>()
        (items.asSequence() + leaving).filter { MaskOrder.readsPixels(it.look) }.forEach { keep += keyOf(image, it) }
        kept.keys.retainAll(keep)
    }

    private fun reset(image: SourceImage) {
        if (image.bitmap === source) return
        source = image.bitmap
        kept.clear()
        retainedFor = null
    }

    /** 渲染器里的绝对像素常数按分析图折算（见 MaskOptions.renderScale），与逐块现算时一样。 */
    private fun keyOf(image: SourceImage, item: MaskItem) =
        Key(item.look.style, item.quad, item.look.options.copy(renderScale = image.scale))

    private companion object {
        /** 算过了，什么也不画（框出了图、小得放不下）：记下来，免得每帧再算一遍。 */
        val NOTHING = object : PreparedMask {
            override fun draw(canvas: Canvas) {}
        }
    }
}
