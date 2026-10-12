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
 * 模糊（spec §8）。**安全性：外观优先，非安全** —— UI 必须明确标注这一点。
 *
 * 实现：先按块取平均缩小，再在小图上做三次可分离的盒式模糊（三次盒式 ≈ 高斯），最后双线性放大回去。
 *
 * 早先是三次「缩小-放大」：直接缩到「一个像素 = 一个模糊半径」再双线性放大。
 * 缩小时双线性只采到 2×2 个点，会漏掉大部分像素，块与块之间看得出色块；
 * 放大时双线性的核是帐篷形，在格点交汇处留下四角芒星。现在缩小按块求平均（不漏像素），
 * 缩到小图上的模糊半径仍有 [MIN_SMALL_RADIUS] 个像素，放大前图已经足够平滑，格子不再显形。
 *
 * 方案偏离：spec 原文写「API 31+ 走 RenderEffect」。本实现两条路径都用 CPU 计算，
 * 因为 RenderEffect 只能作用在硬件加速的 Canvas 上，而导出画在软件 Bitmap 上；
 * 分两条路径会让预览与导出的模糊强度不一致，破坏「所见即所得」。
 */
class BlurRenderer : MaskRenderer {

    override val style = MaskStyle.BLUR

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val bounds = quad.bounds()
        val src = Rect(
            bounds.left.toInt().coerceIn(0, source.width),
            bounds.top.toInt().coerceIn(0, source.height),
            bounds.right.roundToInt().coerceIn(0, source.width),
            bounds.bottom.roundToInt().coerceIn(0, source.height),
        )
        if (src.width() < 2 || src.height() < 2) return

        // 原图口径下每次盒式模糊的半径；三次叠起来的等效高斯 σ 约等于它
        val radius = max(2f, quad.shortEdge() * options.blurRadiusRatio) * STRENGTH
        val factor = max(1, (radius / MIN_SMALL_RADIUS).toInt())
        val smallRadius = max(1, (radius / factor).roundToInt())

        val w = src.width()
        val h = src.height()
        val full = IntArray(w * h)
        source.getPixels(full, 0, w, src.left, src.top, w, h)

        val sw = (w + factor - 1) / factor
        val sh = (h + factor - 1) / factor
        val small = downsample(full, w, h, factor, sw, sh)
        val tmp = IntArray(sw * sh)
        repeat(PASSES) {
            boxBlur(small, tmp, sw, sh, smallRadius, horizontal = true)
            boxBlur(tmp, small, sw, sh, smallRadius, horizontal = false)
        }

        val blurred = Bitmap.createBitmap(small, sw, sh, Bitmap.Config.ARGB_8888)
        val dst = RectF(
            src.left.toFloat(),
            src.top.toFloat(),
            (src.left + sw * factor).toFloat(),
            (src.top + sh * factor).toFloat(),
        )
        val save = canvas.save()
        canvas.clipPath(quad.toPath())
        canvas.drawBitmap(blurred, null, dst, SMOOTH)
        canvas.restoreToCount(save)
        blurred.recycle()
    }

    private companion object {
        const val PASSES = 3

        /** 缩小后小图上的模糊半径不少于这么多像素，放大时才看不出格子。 */
        const val MIN_SMALL_RADIUS = 4f

        /**
         * 「缩小倍数」到盒式半径的换算。旧算法缩小时漏掉的像素也起了遮挡作用，
         * 换成真高斯后 σ 要取到缩小倍数这么大，同一档位才遮得一样住。
         */
        const val STRENGTH = 1f

        val SMOOTH = Paint().apply { isFilterBitmap = true; isAntiAlias = true }

        /** 每 factor×factor 一块取平均；右、下边缘不满一块的按实际像素数平均。 */
        fun downsample(px: IntArray, w: Int, h: Int, factor: Int, sw: Int, sh: Int): IntArray {
            if (factor == 1) return px
            val out = IntArray(sw * sh)
            for (sy in 0 until sh) {
                val y0 = sy * factor
                val y1 = minOf(h, y0 + factor)
                for (sx in 0 until sw) {
                    val x0 = sx * factor
                    val x1 = minOf(w, x0 + factor)
                    var a = 0; var r = 0; var g = 0; var b = 0
                    for (y in y0 until y1) {
                        var i = y * w + x0
                        for (x in x0 until x1) {
                            val c = px[i++]
                            a += c ushr 24
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                        }
                    }
                    val n = (y1 - y0) * (x1 - x0)
                    out[sy * sw + sx] = ((a / n) shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
            return out
        }

        /** 一维滑动窗口盒式模糊，窗口越界的部分取边缘像素。 */
        fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val step = if (horizontal) 1 else w
            val window = 2 * radius + 1
            for (line in 0 until lines) {
                val base = if (horizontal) line * w else line
                fun at(k: Int) = src[base + k.coerceIn(0, len - 1) * step]
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val c = at(k)
                    a += c ushr 24
                    r += (c shr 16) and 0xFF
                    g += (c shr 8) and 0xFF
                    b += c and 0xFF
                }
                for (k in 0 until len) {
                    dst[base + k * step] = ((a / window) shl 24) or ((r / window) shl 16) or
                        ((g / window) shl 8) or (b / window)
                    val add = at(k + radius + 1)
                    val drop = at(k - radius)
                    a += (add ushr 24) - (drop ushr 24)
                    r += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
                    g += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
                    b += (add and 0xFF) - (drop and 0xFF)
                }
            }
        }
    }
}
