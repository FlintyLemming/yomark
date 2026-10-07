package com.yomark.app.engine.mlkit

import android.graphics.Bitmap
import com.yomark.app.core.geometry.Quad
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 解不出内容的疑似条码，再看一眼像素：它是不是**两种墨色**印出来的。
 *
 * 条码（一维、二维都一样）只有两种颜色：墨和底。模糊、缩放、JPEG 只会把这两种颜色**混合**，
 * 混出来的颜色仍落在两色之间那条线上——所以一个码哪怕糊到明暗分不出两档，颜色也还在一条线上。
 * 彩色照片不是：绿植、蓝天、红灯笼离那条线都很远。真机上被框成「条码」的酒店缩略图就是这种。
 *
 * 为什么不干脆要求「解得出内容才算」：解不出的里面有真码——分析图是降采样过的（1080×2400 的截图
 * 按 540×1200 识别），小二维码在这张图上模块不到 2 像素，ML Kit 解不出，导出的原图却一扫就开；
 * 被文字压住一角的收款码也解不出。自造 36 张评测图里，只认解得出的会丢 6 张。
 *
 * 判丢要两条**同时**成立：
 * 1. 颜色：不在两色连线上的像素占 [MAX_OFF_LINE_SHARE] 以上；
 * 2. 明暗：也分不出清楚的两档（Otsu 的类间方差占总方差的比例 η < [TWO_TONE_ETA]）。
 * 第 2 条是给彩色真码留的后路：渐变色的码、拍屏幕带彩色摩尔纹的码，颜色可能离线，明暗仍是两档。
 *
 * 码中间那一块不看（[LOGO_ZONE]）：收款码的 logo、名片码的头像按惯例都放在正中间，
 * 一张彩色头像足以让离线像素超标。
 *
 * 阈值是用 `tools/barcode-eval/two_inks_eval.py` 定的：756 个必须保留的码（36 张评测图，
 * 加模糊、缩小、透视、反色、彩色墨、带 logo、渐变色、摩尔纹的合成码，以 zxing 或微信扫码引擎
 * 读得出为准）离线像素占比最高 0.06；真机上那张酒店缩略图 0.45，同页另外两张 0.61、0.22。
 * 门槛取 0.15。三张酒店图的 η 最高 0.75，门槛取 0.8。
 *
 * **筛不掉的**：灰调照片、蓝白格子这类本身就只有两种颜色的图案——像素上它们跟一个糊掉的码
 * 确实分不开。那部分维持原样，只圈不打码，交给导出拦截。
 */
object TwoInks {

    data class Measure(
        /** Otsu 的类间方差 / 总方差。1 = 明暗恰好两档，连续色调的照片在 0.6–0.75 一带。 */
        val eta: Float,
        /** 墨与底（暗、亮两类各自的中位色）在 RGB 里隔多远。 */
        val separation: Float,
        /** 离墨–底连线超过 [OFF_LINE_DISTANCE] × separation 的像素占比。 */
        val offLineShare: Float,
    )

    /** 少于这么多像素不下判断，按「像」处理——拿不准就留着，漏检是事故。 */
    const val MIN_PIXELS = 64

    /**
     * 墨与底至少隔这么远才有「连线」可言（RGB 距离，满量程约 441）。
     * 更近的是一片近乎纯色的区域，谈不上两种墨色，同样不下判断。
     */
    const val MIN_SEPARATION = 32f

    /** 离线多远算「不在线上」：墨–底间距的 15%。JPEG 的色度噪声、轻微色差都在这以内。 */
    const val OFF_LINE_DISTANCE = 0.15f

    /** 不在线上的像素占到这么多，颜色就不是两种墨混出来的。 */
    const val MAX_OFF_LINE_SHARE = 0.15f

    /** 明暗分两档到这个程度，颜色再花也留下。 */
    const val TWO_TONE_ETA = 0.8f

    /**
     * 中间不看的那一块：四边形以自身重心为中心缩到这个比例（面积 16%）。
     * 比微信、支付宝的 logo 都大一圈；检测框比码大一些、小一些，logo 也还落在里面。
     */
    const val LOGO_ZONE = 0.4f

    /** 大框隔行隔列取样，取到这么多像素就够了——判的是比例，用不着每个像素。 */
    const val MAX_SAMPLES = 160_000

    /** 四边形里（中间 logo 区除外）的像素像不像两种墨色印出来的。只读四边形外接矩形内的像素。 */
    fun looksPrinted(bitmap: Bitmap, quad: Quad): Boolean {
        val colors = sampleInside(bitmap.width, bitmap.height, quad) { y, left, row ->
            bitmap.getPixels(row, 0, row.size, left, y, row.size, 1)
        }
        return looksPrinted(measure(colors))
    }

    /** null（像素太少）与墨底不分的，一律按「像」处理。 */
    fun looksPrinted(m: Measure?): Boolean {
        if (m == null || m.separation < MIN_SEPARATION) return true
        return !(m.offLineShare >= MAX_OFF_LINE_SHARE && m.eta < TWO_TONE_ETA)
    }

    /**
     * 颜色统计，纯函数。colors 是 ARGB，alpha 忽略。
     *
     * 墨与底取 Otsu 在亮度上分出的两类各自的**逐通道中位数**，不是平均色：彩色 logo 混在暗类里
     * 会把平均色拉偏，连线跟着歪，整片墨点都会被算成「不在线上」。连线是过这两点的直线，
     * 落在两点之外的（比墨还深、比底还亮）也算在线上。
     */
    fun measure(colors: IntArray): Measure? {
        val n = colors.size
        if (n < MIN_PIXELS) return null

        val hist = LongArray(256)
        for (c in colors) hist[luma(c)]++

        val total = n.toDouble()
        var sumAll = 0.0
        for (t in 0..255) sumAll += t * hist[t].toDouble()
        var w0 = 0.0
        var sum0 = 0.0
        var bestT = -1
        var bestBetween = -1.0
        for (t in 0..255) {
            w0 += hist[t]
            sum0 += t * hist[t].toDouble()
            val w1 = total - w0
            if (w0 == 0.0 || w1 == 0.0) continue
            val d = sum0 / w0 - (sumAll - sum0) / w1
            val between = w0 * w1 * d * d
            if (between > bestBetween) {
                bestBetween = between
                bestT = t
            }
        }
        // 整块只有一种亮度：没有两类可分
        if (bestT < 0) return Measure(eta = 0f, separation = 0f, offLineShare = 0f)

        val mean = sumAll / total
        var varTotal = 0.0
        for (t in 0..255) {
            val d = t - mean
            varTotal += hist[t] * d * d
        }
        val eta = if (varTotal > 0.0) bestBetween / total / varTotal else 0.0

        // [类][通道][取值] 的计数，取逐通道中位数
        val channels = Array(2) { Array(3) { IntArray(256) } }
        for (c in colors) {
            val cls = if (luma(c) <= bestT) 0 else 1
            channels[cls][0][(c shr 16) and 0xFF]++
            channels[cls][1][(c shr 8) and 0xFF]++
            channels[cls][2][c and 0xFF]++
        }
        var darkCount = 0
        for (t in 0..bestT) darkCount += hist[t].toInt()
        val ink = DoubleArray(3) { lowerMedian(channels[0][it], darkCount).toDouble() }
        val paper = DoubleArray(3) { lowerMedian(channels[1][it], n - darkCount).toDouble() }
        val dr = paper[0] - ink[0]
        val dg = paper[1] - ink[1]
        val db = paper[2] - ink[2]
        val separation = sqrt(dr * dr + dg * dg + db * db)
        if (separation < MIN_SEPARATION) return Measure(eta.toFloat(), separation.toFloat(), 0f)

        val ur = dr / separation
        val ug = dg / separation
        val ub = db / separation
        val limit = OFF_LINE_DISTANCE * separation
        val limitSq = limit * limit
        var off = 0
        for (c in colors) {
            val vr = ((c shr 16) and 0xFF) - ink[0]
            val vg = ((c shr 8) and 0xFF) - ink[1]
            val vb = (c and 0xFF) - ink[2]
            val along = vr * ur + vg * ug + vb * ub
            // 到直线的垂直距离的平方 = |v|² − (v·u)²
            if (vr * vr + vg * vg + vb * vb - along * along > limitSq) off++
        }
        return Measure(eta.toFloat(), separation.toFloat(), off.toFloat() / n)
    }

    /**
     * 取四边形里、中间 logo 区以外的像素。像素中心 (x + 0.5, y + 0.5) 落在四边形里、
     * 又不落在 logo 区里才算（射线法的逐行版本）；四边形超出图像的部分裁掉。
     * 外接矩形大于 [MAX_SAMPLES] 时按同一步长隔行隔列取。
     *
     * @param readRow 读第 y 行从 left 开始、长度为 row.size 的一段像素进 row。
     *   抽成参数是为了单测不必真的造 Bitmap。
     */
    internal fun sampleInside(
        width: Int,
        height: Int,
        quad: Quad,
        readRow: (y: Int, left: Int, row: IntArray) -> Unit,
    ): IntArray {
        val b = quad.bounds()
        val left = floor(b.left).toInt().coerceIn(0, width)
        val right = ceil(b.right).toInt().coerceIn(0, width)
        val top = floor(b.top).toInt().coerceIn(0, height)
        val bottom = ceil(b.bottom).toInt().coerceIn(0, height)
        if (right <= left || bottom <= top) return IntArray(0)

        val outer = FloatArray(8)
        quad.points().forEachIndexed { i, p -> outer[2 * i] = p.x; outer[2 * i + 1] = p.y }
        val cx = (outer[0] + outer[2] + outer[4] + outer[6]) / 4f
        val cy = (outer[1] + outer[3] + outer[5] + outer[7]) / 4f
        val logo = FloatArray(8) { i ->
            if (i % 2 == 0) cx + (outer[i] - cx) * LOGO_ZONE else cy + (outer[i] - cy) * LOGO_ZONE
        }

        val w = right - left
        val h = bottom - top
        val step = max(1, ceil(sqrt(w.toDouble() * h / MAX_SAMPLES)).toInt())
        // 凹四边形一行最多切出两段，每段取整各可能多出一个
        val out = IntArray(((w + step - 1) / step + 2) * ((h + step - 1) / step))
        var count = 0
        val row = IntArray(w)
        val spans = FloatArray(4)
        val logoSpans = FloatArray(4)
        for (y in top until bottom step step) {
            val centreY = y + 0.5f
            val k = crossings(outer, centreY, spans)
            if (k < 2) continue
            val kLogo = crossings(logo, centreY, logoSpans)
            readRow(y, left, row)
            var j = 0
            while (j + 1 < k) {
                // 中心落在 [spans[j], spans[j + 1]] 里的像素
                val from = max(left, ceil(spans[j] - 0.5f).toInt())
                val to = min(right - 1, floor(spans[j + 1] - 0.5f).toInt())
                var x = from
                while (x <= to) {
                    if (!within(logoSpans, kLogo, x + 0.5f)) out[count++] = row[x - left]
                    x += step
                }
                j += 2
            }
        }
        return out.copyOf(count)
    }

    /** 水平线 y 与四边形（xy 交错的 8 个数）各边的交点，升序写进 out，返回个数。 */
    private fun crossings(poly: FloatArray, y: Float, out: FloatArray): Int {
        var k = 0
        for (i in 0 until 4) {
            val ax = poly[2 * i]; val ay = poly[2 * i + 1]
            val bx = poly[(2 * i + 2) % 8]; val by = poly[(2 * i + 3) % 8]
            if ((ay > y) != (by > y)) out[k++] = ax + (y - ay) * (bx - ax) / (by - ay)
        }
        out.sort(0, k)
        return k
    }

    private fun within(spans: FloatArray, k: Int, x: Float): Boolean {
        var j = 0
        while (j + 1 < k) {
            if (x >= spans[j] && x <= spans[j + 1]) return true
            j += 2
        }
        return false
    }

    /** 计数直方图的下中位数（排序后第 (count − 1) / 2 个）。 */
    private fun lowerMedian(counts: IntArray, count: Int): Int {
        val target = (count - 1) / 2
        var seen = 0
        for (v in 0..255) {
            seen += counts[v]
            if (seen > target) return v
        }
        return 255
    }

    /** 与 Rec.601 同权重的整数亮度，0–255。 */
    private fun luma(c: Int): Int {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return (r * 299 + g * 587 + b * 114 + 500) / 1000
    }
}
