package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * 近似色（spec §8 的「抹除」，2026-10-11 并进了色块）：沿 Quad 外面 4px 宽的一圈取色，取逐通道的中位数。
 *
 * 色块开了近似色（MaskOptions.solidNear）就用它填，编辑器面板上那颗「近似色」也用它画出这张图上取到的颜色。
 *
 * **总能取到一种颜色，从不退回固定颜色。** 原先的抹除背景一杂、或者四周取不到几个像素，就改用固定颜色的色块。
 * 近似色只说颜色相近，不说看不出来：背景再杂，取的也是四周颜色的中位数，比一块天蓝更融得进去；
 * 这一圈有一段出了图（框贴着图的边），就用还在图里的那几段；整圈都在图外（框盖满了整张图），就取框里面的颜色。
 */
object NearColor {

    /**
     * [quad] 四周的颜色，不透明。
     *
     * 在这一圈上取点（见 [ringSamples]），出了图的不要，取逐通道的中位数。一个点也没取到时取框里面的；
     * 框整个在图外，图里什么也盖不住，就取整张图的底色。
     *
     * @param renderScale 见 MaskOptions.renderScale。预览画在分析图上、导出画在原图上，
     *   这一圈的宽度不折算的话两边取到的颜色会不一样。
     */
    fun around(source: Bitmap, quad: Quad, renderScale: Float = 1f): Int {
        val samples = ringSamples(source, quad, RING_PX * renderScale).ifEmpty { insideSamples(source, quad) }
        return if (samples.isEmpty()) dominant(source) else median(samples)
    }

    /**
     * 整张图上占地最多的颜色，多半就是页面的底色。图上还没有一块码时，编辑器拿它当下一块大概会取到的近似色。
     * 在 [DOMINANT_GRID]² 个均匀分布的点上取色，相近的归成一堆（见 [heaps]），取最大的一堆。
     */
    fun dominant(source: Bitmap): Int {
        val samples = ArrayList<Int>(DOMINANT_GRID * DOMINANT_GRID)
        for (i in 0 until DOMINANT_GRID) for (j in 0 until DOMINANT_GRID) {
            samples += source.getPixel(i * source.width / DOMINANT_GRID, j * source.height / DOMINANT_GRID)
        }
        return heaps(samples).first().color
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

    private fun median(samples: List<Int>): Int {
        fun channel(of: (Int) -> Int): Int = samples.map(of).sorted()[samples.size / 2]
        return Color.rgb(channel { Color.red(it) }, channel { Color.green(it) }, channel { Color.blue(it) })
    }

    /**
     * [quad] 外面 [ring] 宽的一圈上的像素：在这一圈里离框 1/4、1/2、3/4 圈宽的三条线上，
     * 顺着线等距取 [POINTS_PER_LINE] 个点，出了图的不要。
     *
     * 顺着线走，框再大，四条边也都取得到。原先在外接框上按网格取点，大框的格子比这一圈还宽，
     * 右边和下边那两段常常整段漏掉；框再贴着图的左边和上边，就一个点也取不到了。
     */
    private fun ringSamples(source: Bitmap, quad: Quad, ring: Float): List<Int> {
        val out = ArrayList<Int>(RING_LINES.size * (POINTS_PER_LINE + 4))
        RING_LINES.forEach { f ->
            val line = quad.expand(ring * f).points()
            val lengths = (0 until 4).map { i -> line[i].distanceTo(line[(i + 1) % 4]) }
            val perimeter = lengths.sum()
            if (perimeter <= 0f) return@forEach
            for (i in 0 until 4) {
                val a = line[i]
                val b = line[(i + 1) % 4]
                val n = max(1, ceil(POINTS_PER_LINE * lengths[i] / perimeter).toInt())
                for (k in 0 until n) {
                    val t = k.toFloat() / n
                    out.addPixel(source, quad, PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t), inside = false)
                }
            }
        }
        return out
    }

    /** 框里面的像素：在框的外接矩形上按 [INSIDE_GRID]² 的网格取点，出了图的、不在框里的不要。只在这一圈全在图外时用。 */
    private fun insideSamples(source: Bitmap, quad: Quad): List<Int> {
        val bounds = quad.bounds()
        val out = ArrayList<Int>(INSIDE_GRID * INSIDE_GRID)
        for (i in 0 until INSIDE_GRID) for (j in 0 until INSIDE_GRID) {
            val p = PointF(
                bounds.left + (i + 0.5f) * bounds.width() / INSIDE_GRID,
                bounds.top + (j + 0.5f) * bounds.height() / INSIDE_GRID,
            )
            out.addPixel(source, quad, p, inside = true)
        }
        return out
    }

    /** [p] 落在图上、并且在框里（[inside]）或框外，就把那里的像素加进来。 */
    private fun MutableList<Int>.addPixel(source: Bitmap, quad: Quad, p: PointF, inside: Boolean) {
        val x = floor(p.x).toInt()
        val y = floor(p.y).toInt()
        if (x in 0 until source.width && y in 0 until source.height && quad.contains(p) == inside) {
            this += source.getPixel(x, y)
        }
    }

    private fun PointF.distanceTo(other: PointF): Float = hypot(other.x - x, other.y - y)

    const val RING_PX = 4f

    /** 三个通道的差加起来不超过这么多，就算同一种颜色（见 [heaps]）。 */
    const val SAME_COLOR = 24

    /** 这一圈里取色的三条线，离框分别是圈宽的几分之几。 */
    private val RING_LINES = floatArrayOf(0.25f, 0.5f, 0.75f)
    private const val POINTS_PER_LINE = 64
    private const val INSIDE_GRID = 24
    private const val DOMINANT_GRID = 32
}
