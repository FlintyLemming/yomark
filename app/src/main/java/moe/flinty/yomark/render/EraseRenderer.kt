package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 抹除（spec §8）：采样 Quad 外扩 4px 的环形区域，取中位色填充。不可还原。
 *
 * **降级策略**：环形采样区颜色方差超阈值时（背景不是纯色，中位色填充会留下明显色块），
 * 自动降级为实色块。截图场景绝大多数是纯色或简单渐变背景，这条路径命中率很高；
 * 照片类复杂背景交给降级，不引 inpainting 模型。
 */
class EraseRenderer(private val fallback: MaskRenderer = SolidRenderer()) : MaskRenderer {

    override val style = MaskStyle.ERASE

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }

    /**
     * 给 UI 用：用户选了抹除但这张图会降级时，需要告诉他为什么。
     *
     * @param renderScale 见 [MaskOptions.renderScale]。UI 在分析图上问，导出在原图上画，
     *   采样环宽度不折算的话两边会给出不同的降级判断。
     */
    fun willDegrade(source: Bitmap, quad: Quad, renderScale: Float = 1f): Boolean {
        val samples = ringSamples(source, quad, renderScale)
        if (samples.size < MIN_SAMPLES) return true
        return variance(samples) > VARIANCE_THRESHOLD
    }

    override fun render(canvas: Canvas, source: Bitmap, quad: Quad, options: MaskOptions) {
        val samples = ringSamples(source, quad, options.renderScale)
        if (samples.size < MIN_SAMPLES || variance(samples) > VARIANCE_THRESHOLD) {
            fallback.render(canvas, source, quad, options)
            return
        }
        paint.color = medianColor(samples)
        canvas.drawPath(quad.toPath(), paint)
    }

    /** Quad 外扩 RING_PX 的环形区域上的像素：在外扩框内、原框外。 */
    private fun ringSamples(source: Bitmap, quad: Quad, renderScale: Float): IntArray {
        val outer = quad.expand(RING_PX * renderScale).bounds()
        val out = ArrayList<Int>(256)
        val stepX = max(1, (outer.width() / SAMPLE_STEPS).roundToInt())
        val stepY = max(1, (outer.height() / SAMPLE_STEPS).roundToInt())

        var x = outer.left.toInt()
        while (x < outer.right) {
            var y = outer.top.toInt()
            while (y < outer.bottom) {
                if (x in 0 until source.width && y in 0 until source.height) {
                    val p = android.graphics.PointF(x.toFloat(), y.toFloat())
                    if (!quad.contains(p)) out += source.getPixel(x, y)
                }
                y += stepY
            }
            x += stepX
        }
        return out.toIntArray()
    }

    private fun medianColor(samples: IntArray): Int {
        fun median(channel: (Int) -> Int): Int =
            samples.map(channel).sorted()[samples.size / 2]
        return Color.rgb(median { Color.red(it) }, median { Color.green(it) }, median { Color.blue(it) })
    }

    /** 三通道方差之和。纯色背景接近 0；照片背景轻松上千。 */
    private fun variance(samples: IntArray): Double {
        fun varOf(channel: (Int) -> Int): Double {
            val mean = samples.sumOf { channel(it).toDouble() } / samples.size
            return samples.sumOf { val d = channel(it) - mean; d * d } / samples.size
        }
        return varOf { Color.red(it) } + varOf { Color.green(it) } + varOf { Color.blue(it) }
    }

    companion object {
        const val RING_PX = 4f
        const val VARIANCE_THRESHOLD = 900.0     // 每通道标准差约 17，纯色与浅渐变都在这以下
        private const val MIN_SAMPLES = 24
        private const val SAMPLE_STEPS = 40
    }
}
