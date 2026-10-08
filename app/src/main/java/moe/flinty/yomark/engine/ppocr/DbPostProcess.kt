package moe.flinty.yomark.engine.ppocr

/**
 * DB 检测头的后处理：概率图 → 文字框。
 *
 * PaddleOCR 原版靠 OpenCV（findContours + minAreaRect + pyclipper 外扩），
 * 这里不引 OpenCV（几十 MB 的 .so 只为三个函数），按同样的步骤手写：
 * 1. 概率 > thresh 二值化，四连通分量就是一个个文字块；
 * 2. 分量内平均概率 < boxThresh 的丢掉（PaddleOCR 的 box_score）；
 * 3. 分量的凸包求最小外接矩形——倾斜的文字行也能框正；
 * 4. 按 PaddleOCR 的 unclip 公式外扩：距离 = 面积 × ratio / 周长。
 *    DB 预测的是**缩进过**的文字核，不外扩的话框会切掉笔画边缘，识别掉字。
 */
object DbPostProcess {

    fun boxes(
        prob: FloatArray,
        width: Int,
        height: Int,
        thresh: Float = 0.3f,
        boxThresh: Float = 0.6f,
        unclipRatio: Float = 1.5f,
        minSize: Float = 3f,
    ): List<Box> {
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val out = ArrayList<Box>()

        for (start in prob.indices) {
            if (seen[start] || prob[start] <= thresh) continue

            // 一个分量：逐行记下最左、最右，凸包只需要这两列
            val rowMin = HashMap<Int, Int>()
            val rowMax = HashMap<Int, Int>()
            var sum = 0.0
            var count = 0
            var top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                sum += prob[i]
                count++
                rowMin[y] = minOf(rowMin[y] ?: x, x)
                rowMax[y] = maxOf(rowMax[y] ?: x, x)
                if (x > 0) top = push(i - 1, prob, thresh, seen, stack, top)
                if (x < width - 1) top = push(i + 1, prob, thresh, seen, stack, top)
                if (y > 0) top = push(i - width, prob, thresh, seen, stack, top)
                if (y < height - 1) top = push(i + width, prob, thresh, seen, stack, top)
            }

            if (count < 4 || sum / count < boxThresh) continue

            // 像素是格子不是点：取每行首尾像素的四个角，外接矩形才是真实的像素范围
            val pts = ArrayList<Pt>(rowMin.size * 4)
            rowMin.forEach { (y, x0) ->
                val x1 = rowMax.getValue(y) + 1
                pts += Pt(x0.toFloat(), y.toFloat())
                pts += Pt(x0.toFloat(), y + 1f)
                pts += Pt(x1.toFloat(), y.toFloat())
                pts += Pt(x1.toFloat(), y + 1f)
            }
            val rect = minAreaRect(convexHull(pts)) ?: continue
            if (minOf(rect.w, rect.h) < minSize) continue

            val d = rect.w * rect.h * unclipRatio / (2 * (rect.w + rect.h))
            val grown = rect.grow(d)
            if (minOf(grown.w, grown.h) < minSize + 2) continue
            out += grown.toBox()
        }
        return out
    }

    private fun push(j: Int, prob: FloatArray, thresh: Float, seen: BooleanArray, stack: IntArray, top: Int): Int {
        if (seen[j] || prob[j] <= thresh) return top
        seen[j] = true
        stack[top] = j
        return top + 1
    }

    /** 旋转矩形：中心、两条单位轴、两轴上的边长。 */
    internal class Rect(val c: Pt, val u: Pt, val v: Pt, val w: Float, val h: Float) {
        fun grow(d: Float) = Rect(c, u, v, w + 2 * d, h + 2 * d)

        fun toBox(): Box {
            val hu = u * (w / 2)
            val hv = v * (h / 2)
            return order(listOf(c - hu - hv, c + hu - hv, c + hu + hv, c - hu + hv))
        }
    }

    /**
     * 最小外接矩形：最优矩形必有一条边与凸包的某条边共线（rotating calipers 的结论），
     * 所以逐条凸包边试一遍即可。
     */
    internal fun minAreaRect(hull: List<Pt>): Rect? {
        if (hull.size < 3) return null
        var best: Rect? = null
        var bestArea = Float.MAX_VALUE
        for (i in hull.indices) {
            val e = hull[(i + 1) % hull.size] - hull[i]
            val len = e.length()
            if (len < 1e-6f) continue
            val u = e * (1f / len)
            val v = Pt(-u.y, u.x)
            var uMin = Float.MAX_VALUE; var uMax = -Float.MAX_VALUE
            var vMin = Float.MAX_VALUE; var vMax = -Float.MAX_VALUE
            for (p in hull) {
                val pu = p.x * u.x + p.y * u.y
                val pv = p.x * v.x + p.y * v.y
                if (pu < uMin) uMin = pu
                if (pu > uMax) uMax = pu
                if (pv < vMin) vMin = pv
                if (pv > vMax) vMax = pv
            }
            val area = (uMax - uMin) * (vMax - vMin)
            if (area < bestArea) {
                bestArea = area
                val cu = (uMin + uMax) / 2
                val cv = (vMin + vMax) / 2
                best = Rect(u * cu + v * cv, u, v, uMax - uMin, vMax - vMin)
            }
        }
        return best
    }

    /** Andrew 单调链。 */
    internal fun convexHull(points: List<Pt>): List<Pt> {
        val pts = points.distinct().sortedWith(compareBy<Pt> { it.x }.thenBy { it.y })
        if (pts.size < 3) return pts
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), p) <= 0) lower.removeAt(lower.lastIndex)
            lower += p
        }
        val upper = ArrayList<Pt>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), p) <= 0) upper.removeAt(upper.lastIndex)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * 四个角按阅读方向排：x+y 最小的是左上、最大的是右下，y−x 最小的是右上、最大的是左下
     * （PaddleOCR 的 order_points_clockwise 同一个口径，倾斜不超过 45° 时成立）。
     */
    internal fun order(pts: List<Pt>): Box {
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val rest = pts.filter { it !== tl && it !== br }
        val tr = if (rest.size == 2) rest.minBy { it.y - it.x } else pts.minBy { it.y - it.x }
        val bl = if (rest.size == 2) rest.maxBy { it.y - it.x } else pts.maxBy { it.y - it.x }
        return Box(tl, tr, br, bl)
    }
}
