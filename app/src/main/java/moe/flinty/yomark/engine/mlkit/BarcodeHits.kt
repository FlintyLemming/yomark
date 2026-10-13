package moe.flinty.yomark.engine.mlkit

import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * ML Kit 报上来的码变成候选之前的几步：解没解出内容、整张和分块扫出来的去重、解不出的认不认，以及原图怎么分块。
 * 抽成对象是为了能单测，理由同 [BarcodeCandidates]。
 */
object BarcodeHits {

    /** 一个码，框在识别图坐标上。[decoded]：内容解得出来。 */
    data class Hit(val quad: Quad, val decoded: Boolean)

    /**
     * 解没解出内容。疑似条码（enableAllPotentialBarcodes）的 rawValue 是空串、不是 null（17.3.0 实测，格式是 FORMAT_UNKNOWN）：
     * 以前按 != null 判，宽松模式下每个疑似条码都算解出来了，默认打码，也不过 TwoInks。
     */
    fun decoded(rawValue: String?): Boolean = !rawValue.isNullOrEmpty()

    /**
     * 整张扫的和分块扫的是同一个码的，留一个，解出来的优先、大的优先。
     * 跟解出来的比按占小的那个的比例算（码跨在两块之间时，一块里只框到半个，IoU 不高却是同一个），
     * 疑似的之间按 IoU 算：一大片花纹框里套着一个真码，不能拿花纹顶掉它。
     */
    fun dedupe(hits: List<Hit>): List<Hit> {
        val kept = ArrayList<Hit>()
        hits.sortedWith(compareByDescending<Hit> { it.decoded }.thenByDescending { it.quad.area() }).forEach { h ->
            val same = kept.any { k ->
                val iou = k.quad.iou(h.quad)
                if (k.decoded) covered(k.quad, h.quad, iou) > SAME_CODE else iou > SAME_CODE
            }
            if (!same) kept += h
        }
        return kept
    }

    /** 交集占小的那个的比例。交集由 IoU 反推：I = IoU·(A + B) / (1 + IoU)。 */
    private fun covered(a: Quad, b: Quad, iou: Float): Float {
        val areaA = a.area()
        val areaB = b.area()
        val smaller = min(areaA, areaB)
        if (smaller <= 0f) return 0f
        return iou * (areaA + areaB) / (1 + iou) / smaller
    }

    /**
     * 解不出内容、但紧挨着一个解出来的码，大小也差不多：同一张贴纸、同一页上的另一个码。
     * 照片里 SIM 卡贴纸上带 logo 的那个码、糊一点的那个码，ML Kit 认得出是码，换什么分辨率都解不出内容。
     * 散热片、键盘、包装上的花纹也会被报成疑似条码，可它们不会恰好挨着一个解得出的码、又跟它一样大。
     *
     * 挨着：两个外接框之间的空当不过解出来那个的长边；一样大：长边差不到 [SIBLING_SIZE] 倍。
     */
    fun besideDecoded(quad: Quad, decoded: List<Quad>): Boolean {
        val a = quad.bounds()
        val la = max(a.width(), a.height())
        return decoded.any { d ->
            val b = d.bounds()
            val lb = max(b.width(), b.height())
            max(la, lb) <= SIBLING_SIZE * min(la, lb) &&
                max(gap(a.left, a.right, b.left, b.right), gap(a.top, a.bottom, b.top, b.bottom)) <= lb
        }
    }

    private fun gap(a0: Float, a1: Float, b0: Float, b1: Float): Float = max(0f, max(a0, b0) - min(a1, b1))

    /**
     * 码整个落在这一块里：四个角都在块内，离从原图中间切开的那几条边至少 [EDGE] px。
     * 块边切过的码 ML Kit 也会报成疑似条码，缺的那一角往块外推（照片上推出去 100 px），框歪成一个尖角；
     * 块之间重叠得够多，整个装下它的那一块会再报一次。原图自己的边切到的码没有别的块装得下，照留。
     *
     * @param width/height 块的像素尺寸，[corners] 在块的坐标上
     * @param cutLeft/cutTop/cutRight/cutBottom 这条边是从原图中间切开的
     */
    fun wholeInTile(
        corners: List<PointF>, width: Int, height: Int,
        cutLeft: Boolean, cutTop: Boolean, cutRight: Boolean, cutBottom: Boolean,
    ): Boolean = corners.all { p ->
        (!cutLeft || p.x >= EDGE) && (!cutTop || p.y >= EDGE) &&
            (!cutRight || p.x <= width - EDGE) && (!cutBottom || p.y <= height - EDGE)
    }

    /**
     * 边长 [length] 分成不超过 [tile] 的几段，均匀铺开，相邻两段至少重叠 [overlap]。返回 [起点, 终点)。
     * 不超过 [tile] 的就是一段。
     */
    fun spans(length: Int, tile: Int, overlap: Int): List<Pair<Int, Int>> {
        if (length <= tile) return listOf(0 to length)
        val n = ceil((length - overlap).toDouble() / (tile - overlap)).toInt()
        return (0 until n).map { i ->
            val start = ((length - tile).toLong() * i / (n - 1)).toInt()
            start to start + tile
        }
    }

    /** 原图长边超过 [MAX_TILED_EDGE] 的，先按 2 的幂降采样再分块：50MP 的照片也只切 6 块。 */
    fun sampleSize(longEdge: Int): Int {
        var s = 1
        while (longEdge / s > MAX_TILED_EDGE) s *= 2
        return s
    }

    /**
     * 一块的边长。ML Kit 自己会把长边超过 2048 的图缩到 2048 再找：同一个干净的二维码，贴在 2048 宽的图上 64 px 宽就解得出，
     * 贴在 3072 宽的图上要 96 px，4096 宽的要 128 px（模拟器上实测）。块再大，多出来的分辨率也白给。
     */
    const val TILE = 2048

    /** 相邻两块至少重叠这么多：比这小的码总有一块把它整个装下。更大的码整张扫就看得清。 */
    const val OVERLAP = 512

    const val MAX_TILED_EDGE = 4096

    const val SIBLING_SIZE = 1.5f

    private const val SAME_CODE = 0.5f

    private const val EDGE = 2f
}
