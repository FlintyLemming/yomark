package moe.flinty.yomark.engine.ppocr

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 竖长的大图（按原图识别的截图、长截图）分成横条检测：哪种图要分、怎么切、各条里的框怎么合回一张图。
 * 抽成对象是为了能单测：检测模型之外的这几步只是几何。
 *
 * 检测整张缩到长边 [PpOcrEngine.DET_LIMIT]，1080×2400 的截图跟着缩到 691 宽，1080×5148 的长截图缩到 322 宽，
 * 昵称这种小字就检测不出来了。横条按整宽切，每条不超过 [PpOcrEngine.DET_LIMIT] 高、不缩，
 * 一次推理的输入不比照片整张缩下来的大；一行横排的字也不会被竖着切开。
 */
internal object DetBands {

    /**
     * 这张图要不要分条：竖长、宽不超过 [limit]，整张缩下去要缩掉 [SHRINK] 以上（高过 [limit] 的 4/3）。
     * 照片宽过 [limit]，横着的长行会被竖着切开，照旧整张缩；1080×1920 这种只缩两成的也照旧。
     */
    fun wanted(width: Int, height: Int, limit: Float): Boolean =
        height > width && width <= limit && limit / height < 1f - SHRINK

    /** 高 [height] 切成不超过 [limit] 高的几条，均匀铺开，相邻两条至少重叠 [OVERLAP]。返回 [上沿, 下沿)。 */
    fun spans(height: Int, limit: Int): List<Pair<Int, Int>> {
        if (height <= limit) return listOf(0 to height)
        val n = ceil((height - OVERLAP).toDouble() / (limit - OVERLAP)).toInt()
        return (0 until n).map { i ->
            val top = ((height - limit).toLong() * i / (n - 1)).toInt()
            top to top + limit
        }
    }

    /**
     * 各条里检测到的框（已换到整张图的坐标）合成一份。
     *
     * 碰到切缝（从图中间切开的条边）的框是半截的。比 [OVERLAP] 矮的一行字总有一条把它整个装下，
     * 那一条里有整的，半截的就丢掉；两条里都只有半截的（竖着的长行，比重叠还长），几截拼成一个框——宁可框大一点也不丢。
     * 重叠区里两条都整个报了的同一行，留先报的那个。
     *
     * @param bands 每条一项：这条的 [上沿, 下沿) 和它的框
     * @param height 整张图的高
     */
    fun merge(bands: List<Pair<Pair<Int, Int>, List<Box>>>, height: Int): List<Box> {
        val whole = ArrayList<Pair<Int, Box>>()
        val cut = ArrayList<Box>()
        bands.forEachIndexed { i, (span, boxes) ->
            val (top, bottom) = span
            boxes.forEach { b ->
                val atSeam = (top > 0 && b.minY < top + EDGE) || (bottom < height && b.maxY > bottom - EDGE)
                if (atSeam) cut += b else whole += i to b
            }
        }
        val kept = ArrayList<Pair<Int, Box>>()
        whole.forEach { (i, b) -> if (kept.none { (j, k) -> j != i && covered(k, b) > SAME }) kept += i to b }
        val boxes = kept.map { it.second }
        return boxes + joined(cut.filter { c -> boxes.none { covered(it, c) > SAME } })
    }

    /** 互相压着的几截拼成一个框（几截外接框的外接框）。 */
    private fun joined(pieces: List<Box>): List<Box> {
        val groups = ArrayList<List<Box>>()
        pieces.forEach { p ->
            val touching = groups.filter { g -> g.any { overlap(it, p) > 0f } }
            groups.removeAll { g -> touching.any { it === g } }
            groups += touching.flatten() + p
        }
        return groups.map { g ->
            if (g.size == 1) g[0]
            else {
                val left = g.minOf { it.minX }
                val top = g.minOf { it.minY }
                val right = g.maxOf { it.maxX }
                val bottom = g.maxOf { it.maxY }
                DbPostProcess.order(listOf(Pt(left, top), Pt(right, top), Pt(right, bottom), Pt(left, bottom)))
            }
        }
    }

    /** 外接框相交的面积。 */
    private fun overlap(a: Box, b: Box): Float {
        val w = min(a.maxX, b.maxX) - max(a.minX, b.minX)
        val h = min(a.maxY, b.maxY) - max(a.minY, b.minY)
        return if (w <= 0f || h <= 0f) 0f else w * h
    }

    /** 相交的部分占小的那个框（外接框）的比例。 */
    private fun covered(a: Box, b: Box): Float {
        val smaller = min((a.maxX - a.minX) * (a.maxY - a.minY), (b.maxX - b.minX) * (b.maxY - b.minY))
        return if (smaller <= 0f) 0f else overlap(a, b) / smaller
    }

    /** 相邻两条至少重叠这么多：一屏宽的截图上一行字三四十像素高，转了 90° 的证件照上的竖行可以更长，那种靠拼。 */
    const val OVERLAP = 256

    /** 整张缩掉这么多以上才分条。 */
    const val SHRINK = 0.25f

    /** 离切缝不到这么近就算碰到了：DB 外扩出来的框本来就会探出条外。 */
    private const val EDGE = 2f

    private const val SAME = 0.5f
}
