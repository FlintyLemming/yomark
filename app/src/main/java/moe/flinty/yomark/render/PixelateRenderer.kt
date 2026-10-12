package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 像素化（spec §8）：外接框区域 → 缩成每块一个像素 → 最近邻放大 → 以 Quad 裁剪绘回。
 *
 * **块边长只看整张图和「颗粒」设置，不看框多大。** 同一张图上大框小框的颗粒一样粗，
 * 拖动选框也不会让颗粒跟着变。块边长下限 12px（原图口径）：块太小的马赛克是可以被去码工具还原的。
 * 框小到连一个块都放不下时降级为实色块——不画一个假的马赛克糊弄用户。
 */
class PixelateRenderer(private val fallback: MaskRenderer = SolidRenderer()) : MaskRenderer {

    override val style = MaskStyle.PIXELATE

    /**
     * 块边长 = max(图片短边 / [IMAGE_DIVISOR], 12px) × 粗细倍数，与选框大小无关。
     *
     * 出厂的 divisor = 8 是倍数 1；往粗调（divisor 变小）倍数 = 8 / divisor，最粗约 2.7 倍。
     * 1080 宽的截图上出厂就是 12px，分辨率更高的图按比例放大，文字和块的比例大致不变。
     *
     * @param imageShortEdge 整张图在**当前坐标系**里的短边（预览是分析图，导出是原图）。
     * @param renderScale 见 [MaskOptions.renderScale]：12px 下限是原图口径，按它折算到当前坐标系。
     */
    fun blockSizeFor(imageShortEdge: Float, divisor: Int, renderScale: Float = 1f): Float {
        val d = max(1, divisor)
        val coarsen = max(1f, MaskOptions.FINEST_PIXEL_DIVISOR.toFloat() / d)
        return max(imageShortEdge / IMAGE_DIVISOR, MIN_BLOCK_PX * renderScale) * coarsen
    }

    fun willFallBack(quad: Quad, imageShortEdge: Float, divisor: Int, renderScale: Float = 1f): Boolean =
        blockSizeFor(imageShortEdge, divisor, renderScale) >= quad.shortEdge()

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val imageShortEdge = min(source.width, source.height).toFloat()
        val block = blockSizeFor(imageShortEdge, options.pixelBlockDivisor, options.renderScale)
        if (block >= quad.shortEdge()) {
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

        // 块数向上取整，画回去的每块都正好 block 见方：取整不往回拉伸，框大小不同颗粒也一样粗。
        // 多出来的一截落在框外，由下面的 clipPath 裁掉。
        val smallW = max(1, ceil(src.width() / block).toInt())
        val smallH = max(1, ceil(src.height() / block).toInt())
        val cropW = min(source.width - src.left, (smallW * block).roundToInt()).coerceAtLeast(1)
        val cropH = min(source.height - src.top, (smallH * block).roundToInt()).coerceAtLeast(1)

        // 缩小时用双线性（取块内平均色），放大时用最近邻（保持硬边）
        val cropped = Bitmap.createBitmap(source, src.left, src.top, cropW, cropH)
        val small = Bitmap.createScaledBitmap(cropped, smallW, smallH, true)
        val dst = RectF(
            src.left.toFloat(), src.top.toFloat(),
            src.left + smallW * block, src.top + smallH * block,
        )

        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawBitmap(small, null, dst, NEAREST)
        canvas.restoreToCount(save)

        if (small !== cropped) small.recycle()
        cropped.recycle()
    }

    companion object {
        const val MIN_BLOCK_PX = 12f
        /** 出厂一块 = 图片短边的 1/90：1080 宽的截图正好 12px，与原先文字行上的观感一致。 */
        const val IMAGE_DIVISOR = 90f
        private val NEAREST = Paint().apply {
            isFilterBitmap = false      // 最近邻：马赛克边缘必须是硬的
            isAntiAlias = false
            isDither = false
        }
    }
}
