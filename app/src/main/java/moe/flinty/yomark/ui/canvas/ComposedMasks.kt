package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.PixelMaskRenderer
import moe.flinty.yomark.render.PreparedMask
import moe.flinty.yomark.render.RendererRegistry

/**
 * 识别动效（落码、散开）里用的合成图：[items]（已按 MaskOrder 排好）照导出的办法在一张位图上一块接一块合成好的样子。
 * 识别出的码在结果到达那一刻才有，当场逐块算模糊、马赛克的话，十几块码这一帧要几十毫秒，落码那一遍一顿一顿的。
 *
 * 所以在后台合成（[compose]），合成好之前识别出的码先不落（见 drawScene）。码变了就作废，等新的那张。
 * 合成时各块读像素的码算好的样子（[patches]，与 [items] 一一对应，别的码是 null）交给 PreparedMasks，
 * 动效停下来的第一帧就不用再算（见 PreparedMasks.adopt）。
 */
internal class ComposedMasks(
    val items: List<MaskItem>,
    val bitmap: Bitmap,
    val patches: List<PreparedMask?>,
) {
    companion object {
        /**
         * 照导出的办法把 [items]（已按 MaskOrder 排好）一块接一块合成。后台线程上调：渲染器里有跨调用复用的画笔，
         * 得另给一套 [registry]，别和界面上正在画的共用。
         */
        fun compose(image: SourceImage, items: List<MaskItem>, registry: RendererRegistry): ComposedMasks {
            val into = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(into)
            canvas.drawBitmap(image.bitmap, 0f, 0f, COPY)
            val patches = items.map {
                val options = MaskOrder.pinNearColor(it.look.options.copy(renderScale = image.scale), image.bitmap, it.quad)
                val renderer = registry[it.look.style]
                if (renderer is PixelMaskRenderer) {
                    renderer.prepare(into, it.quad, options)?.also { patch -> patch.draw(canvas) }
                } else {
                    renderer.render(canvas, into, it.quad, options, it.ink)
                    null
                }
            }
            return ComposedMasks(items, into, patches)
        }

        /** 连 alpha 一起覆盖，透明底的 PNG 也和原图一样。 */
        private val COPY = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
    }
}
