package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 像素化（spec §8）：外接框区域 → 缩到 1/N → 最近邻放大 → 以 Quad 裁剪绘回。
 *
 * **块边长下限 = max(区域短边/8, 12px)。** 块太小的马赛克是可以被去码工具还原的。
 * 区域小到连一个块都放不下时降级为实色块——不画一个假的马赛克糊弄用户。
 */
class PixelateRenderer(private val fallback: MaskRenderer = SolidRenderer()) : MaskRenderer {

    override val style = MaskStyle.PIXELATE

    /**
     * 块边长 = max(区域短边 / divisor, 下限)。出厂的 divisor = 8 时下限就是 12px。
     *
     * 往粗调（divisor 变小）时下限按同样的比例抬高：截图上一条文字行的短边多半不到 96px，
     * 那里起作用的一直是下限，只放大比例的话，面板上的滑条在文字上拖不出任何变化。
     * divisor 比 8 大时下限不再降——12px 是安全底线，不跟着变细。
     *
     * @param renderScale 见 [MaskOptions.renderScale]：下限是原图口径，按它折算到当前坐标系。
     */
    fun blockSizeFor(quad: Quad, divisor: Int, renderScale: Float = 1f): Float {
        val d = max(1, divisor)
        val coarsen = max(1f, MaskOptions.FINEST_PIXEL_DIVISOR.toFloat() / d)
        return max(quad.shortEdge() / d, MIN_BLOCK_PX * renderScale * coarsen)
    }

    fun willFallBack(quad: Quad, divisor: Int, renderScale: Float = 1f): Boolean =
        blockSizeFor(quad, divisor, renderScale) >= quad.shortEdge()

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        if (willFallBack(quad, options.pixelBlockDivisor, options.renderScale)) {
            fallback.render(canvas, source, quad, options)
            return
        }

        val bounds = quad.bounds()
        val src = Rect(
            bounds.left.toInt().coerceIn(0, source.width),
            bounds.top.toInt().coerceIn(0, source.height),
            bounds.right.roundToInt().coerceIn(0, source.width),
            bounds.bottom.roundToInt().coerceIn(0, source.height),
        )
        if (src.width() <= 0 || src.height() <= 0) return

        val block = blockSizeFor(quad, options.pixelBlockDivisor, options.renderScale)
        val smallW = max(1, (src.width() / block).roundToInt())
        val smallH = max(1, (src.height() / block).roundToInt())

        // 缩小时用双线性（取块内平均色），放大时用最近邻（保持硬边）
        val cropped = Bitmap.createBitmap(source, src.left, src.top, src.width(), src.height())
        val small = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawBitmap(small, null, RectF(src), NEAREST)
        canvas.restoreToCount(save)

        if (small !== cropped) small.recycle()
        cropped.recycle()
    }

    companion object {
        const val MIN_BLOCK_PX = 12f
        private val NEAREST = Paint().apply {
            isFilterBitmap = false      // 最近邻：马赛克边缘必须是硬的
            isAntiAlias = false
            isDither = false
        }
    }
}
