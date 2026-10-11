package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 近似色（spec §8 的「抹除」，2026-10-11 并进了色块）：采样 Quad 外扩 4px 的一圈，取逐通道的中位数。
 *
 * 色块开了近似色（MaskOptions.solidNear）就用它填，编辑器面板上那颗「近似色」也用它画出这张图上取到的颜色。
 * 原先背景太杂时降级成固定颜色的色块，现在不降级：名字只说颜色相近，不说看不出来，
 * 杂的背景上取到的中位色也比一块天蓝更融得进去。
 */
object NearColor {

    /**
     * [quad] 四周的颜色，不透明。四周几乎没有像素可取（框盖满了整张图）时为 null，调用方退回固定的颜色。
     *
     * @param renderScale 见 MaskOptions.renderScale。预览画在分析图上、导出画在原图上，
     *   采样环宽度不折算的话两边取到的颜色会不一样。
     */
    fun around(source: Bitmap, quad: Quad, renderScale: Float = 1f): Int? {
        val samples = ringSamples(source, quad, renderScale)
        if (samples.size < MIN_SAMPLES) return null
        fun median(channel: (Int) -> Int): Int = samples.map(channel).sorted()[samples.size / 2]
        return Color.rgb(median { Color.red(it) }, median { Color.green(it) }, median { Color.blue(it) })
    }

    /**
     * 整张图上占地最多的颜色，多半就是页面的底色。图上还没有一块码时，编辑器拿它当下一块大概会取到的近似色。
     * 在 [DOMINANT_GRID]² 个均匀分布的点上取色，相近的归成一堆（见 [heaps]），取最大的一堆。
     */
    fun dominant(source: Bitmap): Int? {
        if (source.width <= 0 || source.height <= 0) return null
        val samples = ArrayList<Int>(DOMINANT_GRID * DOMINANT_GRID)
        for (i in 0 until DOMINANT_GRID) for (j in 0 until DOMINANT_GRID) {
            samples += source.getPixel(i * source.width / DOMINANT_GRID, j * source.height / DOMINANT_GRID)
        }
        return heaps(samples).firstOrNull()?.color
    }

    /** 一堆相近的颜色：堆里的平均色，和这一堆有几个。 */
    class Heap(val color: Int, val count: Int)

    /**
     * 把颜色按相近归堆，个数多的排前面，一样多的按先来后到。和一堆的头一个颜色三个通道的差加起来不超过 [SAME_COLOR]，
     * 就归进那一堆：白底上几块各取到的白、JPEG 的噪点，差不过几个数，算同一种；
     * 白卡片和聊天截图那种浅灰的底差三四十，还是两种。
     */
    fun heaps(colors: List<Int>): List<Heap> {
        val seeds = ArrayList<Int>()
        val sums = ArrayList<IntArray>()               // [个数, r 之和, g 之和, b 之和]
        colors.forEach { c ->
            val i = seeds.indexOfFirst { distance(it, c) <= SAME_COLOR }
            val sum = if (i >= 0) sums[i] else IntArray(4).also { seeds += c; sums += it }
            sum[0]++
            sum[1] += Color.red(c)
            sum[2] += Color.green(c)
            sum[3] += Color.blue(c)
        }
        return sums.map { Heap(Color.rgb(it[1] / it[0], it[2] / it[0], it[3] / it[0]), it[0]) }
            .sortedByDescending { it.count }
    }

    private fun distance(a: Int, b: Int): Int =
        abs(Color.red(a) - Color.red(b)) + abs(Color.green(a) - Color.green(b)) + abs(Color.blue(a) - Color.blue(b))

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
                    if (!quad.contains(PointF(x.toFloat(), y.toFloat()))) out += source.getPixel(x, y)
                }
                y += stepY
            }
            x += stepX
        }
        return out.toIntArray()
    }

    const val RING_PX = 4f

    /** 三个通道的差加起来不超过这么多，就算同一种颜色（见 [heaps]）。 */
    const val SAME_COLOR = 24
    private const val MIN_SAMPLES = 24
    private const val SAMPLE_STEPS = 40
    private const val DOMINANT_GRID = 32
}
