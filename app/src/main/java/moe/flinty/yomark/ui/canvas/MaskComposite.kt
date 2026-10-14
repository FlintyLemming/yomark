package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.PixelateRenderer
import moe.flinty.yomark.render.RendererRegistry
import kotlin.math.min

/**
 * 预览里读像素的码（马赛克、模糊）压在别的码上时，照导出的办法在一张位图上一块接一块地合成一遍，画的时候从这张图上取。
 *
 * 导出是在同一张位图上一块接一块地画的，后画的模糊读到的是底下已经画上去的色块；预览要是还读原图，
 * 模糊就把色块挡住的字又糊着露了出来，和导出也对不上。没有哪块读像素的码压着别的码时用不着合成，返回 null，照常一块块地画。
 *
 * 合成结果按（图，码）缓存：识别动效每帧都画，码没变就不重算；拖着框走的时候每帧重算一次，与原先每帧重画模糊差不多。
 * 位图跨帧复用，不回收：上一帧的显示列表可能还引用着它。
 */
internal class MaskComposite {
    private var work: Bitmap? = null
    private var key: Pair<Bitmap, List<MaskItem>>? = null
    private var result: Bitmap? = null

    /** @param items 已按 MaskOrder 排好、只含 MASKED 的码。 */
    fun of(image: SourceImage, items: List<MaskItem>, registry: RendererRegistry): Bitmap? {
        val k = image.bitmap to items
        if (k == key) return result
        result = if (needed(image, items)) build(image, items, registry) else null
        key = k
        return result
    }

    private fun build(image: SourceImage, items: List<MaskItem>, registry: RendererRegistry): Bitmap {
        val bmp = work?.takeIf { it.width == image.width && it.height == image.height }
            ?: Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888).also { work = it }
        val canvas = Canvas(bmp)
        canvas.drawBitmap(image.bitmap, 0f, 0f, COPY)
        items.forEach {
            val options = MaskOrder.pinNearColor(it.look.options.copy(renderScale = image.scale), image.bitmap, it.quad)
            registry[it.look.style].render(canvas, bmp, it.quad, options, it.ink)
        }
        return bmp
    }

    private companion object {
        /** 连 alpha 一起覆盖：透明底的 PNG 上，上一次合成的码不能透过来。 */
        val COPY = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        val PIXELATE = PixelateRenderer()

        /** 有没有哪块读像素的码，读的范围碰到了先画的码。 */
        fun needed(image: SourceImage, items: List<MaskItem>): Boolean = items.indices.any { i ->
            val item = items[i]
            if (!MaskOrder.readsPixels(item.look)) return@any false
            val reads = readBounds(image, item)
            (0 until i).any { RectF.intersects(items[it].quad.bounds(), reads) }
        }

        /**
         * 这块码会读到的范围。模糊只读外接框；马赛克的块数向上取整，右边、下边最多多读出一块。
         * 外接框取整也会多出不到一像素，各放宽一像素。
         */
        fun readBounds(image: SourceImage, item: MaskItem): RectF {
            val extra = if (item.look.style == MaskStyle.PIXELATE) {
                PIXELATE.blockSizeFor(
                    min(image.width, image.height).toFloat(), item.look.options.pixelBlockDivisor, image.scale,
                )
            } else 0f
            return RectF(item.quad.bounds()).apply {
                left -= 1f; top -= 1f
                right += 1f + extra; bottom += 1f + extra
            }
        }
    }
}
