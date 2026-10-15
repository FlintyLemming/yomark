package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.InkPath
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle

/**
 * 渲染层（spec §4.3）。每种打码样式一个实现，样式与识别完全解耦。
 * 同一个实现既画预览也画导出——这是「所见即所得」的唯一保证。
 *
 * @param source 被打码的位图。像素化/模糊、开了近似色的色块需要读它的原始像素；
 *               固定颜色的色块不需要，但签名统一。
 * @param quad   与 canvas 同一坐标系下的目标区域。
 * @param ink    手画的一笔（见 MaskItem.ink），[quad] 是它的外接框。只有马克笔、画笔照着它画；
 *               打码的几种不看它，盖住整个外接框。
 */
interface MaskRenderer {
    val style: MaskStyle
    fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions, ink: InkPath? = null)
}

/**
 * 读像素的样式（马赛克、模糊）。费时的是读像素、算出要贴上去的那张小图（[prepare]），贴上去只是一次缩放绘制。
 * 拆成两步，预览就能把算好的留着，缩放、平移、点选时只重贴不重算（见 ImageCanvas 的 PreparedMasks）；
 * 导出照旧一步画完（[render]）。两边贴的是同一张图、同一个画法。
 */
interface PixelMaskRenderer : MaskRenderer {

    /** 按 [source] 上 [quad] 这一块算好要贴的样子。null：这一块什么也不画（框出了图、小得放不下）。 */
    fun prepare(source: Bitmap, quad: Quad, options: MaskOptions): PreparedMask?

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions, ink: InkPath?) {
        val prepared = prepare(source, quad, options) ?: return
        prepared.draw(canvas)
        prepared.release()
    }
}

/** 算好的一块码。[draw] 画多少遍都一样：只用自己手里的东西，不再读图。 */
interface PreparedMask {
    fun draw(canvas: Canvas)

    /** 不再画了，放掉手里的位图。画到硬件画布上的可能还被显示列表引用着，那种留给 GC，别调这个。 */
    fun release() {}
}

/** 一张小图放大贴进 [dst]，裁到 [quad] 里：模糊、马赛克都这样贴，只差放大时滤不滤波（[paint]）。 */
class ScaledPatch(
    private val bitmap: Bitmap,
    private val dst: RectF,
    quad: Quad,
    private val paint: Paint,
) : PreparedMask {
    private val clip = quad.toPath()

    override fun draw(canvas: Canvas) {
        val save = canvas.save()
        canvas.clipPath(clip)
        canvas.drawBitmap(bitmap, null, dst, paint)
        canvas.restoreToCount(save)
    }

    override fun release() = bitmap.recycle()
}
