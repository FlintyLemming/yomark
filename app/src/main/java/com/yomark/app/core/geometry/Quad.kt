package com.yomark.app.core.geometry

import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 图像上的任意四边形。OCR 的文本框会是倾斜的，一律用四点表示，不退化成 Rect。
 * 点按顺时针或逆时针给出（不要求特定绕向，但必须是一个简单多边形）。
 */
@Parcelize
data class Quad(val p0: PointF, val p1: PointF, val p2: PointF, val p3: PointF) : Parcelable {

    fun points(): List<PointF> = listOf(p0, p1, p2, p3)

    fun bounds(): RectF {
        val xs = points().map { it.x }
        val ys = points().map { it.y }
        return RectF(xs.min(), ys.min(), xs.max(), ys.max())
    }

    /** 面积，鞋带公式。绕向不影响结果（取绝对值）。 */
    fun area(): Float = abs(signedArea(points()))

    /** 四条边中最短的一条的长度。像素化块尺寸下限、丢弃小框都用它。 */
    fun shortEdge(): Float {
        val p = points()
        return (0 until 4).minOf { i ->
            val a = p[i]; val b = p[(i + 1) % 4]
            hypot(b.x - a.x, b.y - a.y)
        }
    }

    /**
     * 把**每条边**沿它自己的外法线向外推 px，再取相邻两条平移后直线的交点作为新顶点。
     * 用于抹除时采样周边像素（spec §8）——那里要的是一圈**均匀**的余量。
     *
     * 不要改成「顶点沿背离质心的方向推 px」：那样正方形的角只会在每个轴上移动
     * px/√2，边上的余量比要求的窄 30%，抹除会采到还没被遮住的原始像素。
     */
    fun expand(px: Float): Quad {
        val v = points()
        // 外法线的朝向由多边形自身的绕向决定：signedArea 为正时，边 (a→b) 的外法线是 (dy, -dx)。
        val sign = if (signedArea(v) >= 0f) 1f else -1f
        val offsetEdges = (0 until 4).map { i ->
            val a = v[i]
            val b = v[(i + 1) % 4]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len = hypot(dx, dy)
            if (len < 1e-6f) return@map a to b          // 退化边，原样放回
            val nx = dy / len * sign * px
            val ny = -dx / len * sign * px
            PointF(a.x + nx, a.y + ny) to PointF(b.x + nx, b.y + ny)
        }
        // 新顶点 i = 平移后的边 (i-1) 与边 i 的交点
        val moved = (0 until 4).map { i ->
            val prev = offsetEdges[(i + 3) % 4]
            val cur = offsetEdges[i]
            lineIntersection(prev.first, prev.second, cur.first, cur.second) ?: cur.first
        }
        return Quad(moved[0], moved[1], moved[2], moved[3])
    }

    /** 降采样坐标 ↔ 原图坐标。导出时用 1/scale 反算。 */
    fun scaled(factor: Float): Quad = Quad(
        PointF(p0.x * factor, p0.y * factor),
        PointF(p1.x * factor, p1.y * factor),
        PointF(p2.x * factor, p2.y * factor),
        PointF(p3.x * factor, p3.y * factor),
    )

    /** 射线法命中测试。边界上视为命中。 */
    fun contains(p: PointF): Boolean {
        val v = points()
        var inside = false
        var j = v.size - 1
        for (i in v.indices) {
            val a = v[i]; val b = v[j]
            if ((a.y > p.y) != (b.y > p.y)) {
                val x = (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x
                if (p.x < x) inside = !inside
            }
            j = i
        }
        return inside || distanceTo(p) < 1e-3f
    }

    /** 点到四边形边界的最短距离。0 表示在边上。 */
    fun distanceTo(p: PointF): Float {
        val v = points()
        return (0 until 4).minOf { i ->
            distanceToSegment(p, v[i], v[(i + 1) % 4])
        }
    }

    fun toPath(): Path = Path().apply {
        moveTo(p0.x, p0.y)
        lineTo(p1.x, p1.y)
        lineTo(p2.x, p2.y)
        lineTo(p3.x, p3.y)
        close()
    }

    /**
     * 交并比。用凸多边形裁剪（Sutherland–Hodgman）求交集面积——
     * 用外接矩形近似会让倾斜文本行的去重完全失准。
     */
    fun iou(other: Quad): Float {
        val inter = intersectionArea(this, other)
        if (inter <= 0f) return 0f
        val union = area() + other.area() - inter
        return if (union <= 0f) 0f else inter / union
    }

    companion object {
        fun fromRect(r: RectF): Quad = Quad(
            PointF(r.left, r.top), PointF(r.right, r.top),
            PointF(r.right, r.bottom), PointF(r.left, r.bottom),
        )

        /**
         * 一组四边形的最小面积**旋转**矩形（spec §5.3 的「最小外接四边形」）。
         * 不用轴对齐外接矩形：倾斜文字的轴对齐框会盖住相邻内容。
         * 做法：所有点求凸包，再沿凸包每条边做一次旋转卡壳，取面积最小的那个方向。
         */
        fun boundingQuad(quads: List<Quad>): Quad {
            require(quads.isNotEmpty()) { "boundingQuad 需要至少一个四边形" }
            if (quads.size == 1) return quads.first()
            val hull = convexHull(quads.flatMap { it.points() })
            if (hull.size < 3) return fromRect(quads.map { it.bounds() }.reduce { a, b -> RectF(a).apply { union(b) } })

            var best: Quad? = null
            var bestArea = Float.MAX_VALUE
            for (i in hull.indices) {
                val a = hull[i]
                val b = hull[(i + 1) % hull.size]
                val ang = kotlin.math.atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble()).toFloat()
                val c = cos(-ang); val s = sin(-ang)
                var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
                var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
                for (p in hull) {
                    val x = p.x * c - p.y * s
                    val y = p.x * s + p.y * c
                    minX = min(minX, x); maxX = max(maxX, x)
                    minY = min(minY, y); maxY = max(maxY, y)
                }
                val area = (maxX - minX) * (maxY - minY)
                if (area < bestArea) {
                    bestArea = area
                    val ic = cos(ang); val isn = sin(ang)
                    fun back(x: Float, y: Float) = PointF(x * ic - y * isn, x * isn + y * ic)
                    best = Quad(back(minX, minY), back(maxX, minY), back(maxX, maxY), back(minX, maxY))
                }
            }
            return best!!
        }

        /** Andrew monotone chain，逆时针输出，不含共线冗余点。 */
        private fun convexHull(pts: List<PointF>): List<PointF> {
            val p = pts.distinctBy { it.x to it.y }.sortedWith(compareBy({ it.x }, { it.y }))
            if (p.size < 3) return p
            fun cross(o: PointF, a: PointF, b: PointF) =
                (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

            val lower = ArrayList<PointF>()
            for (q in p) {
                while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], q) <= 0) lower.removeAt(lower.size - 1)
                lower.add(q)
            }
            val upper = ArrayList<PointF>()
            for (q in p.asReversed()) {
                while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], q) <= 0) upper.removeAt(upper.size - 1)
                upper.add(q)
            }
            lower.removeAt(lower.size - 1)
            upper.removeAt(upper.size - 1)
            return lower + upper
        }

        private fun intersectionArea(a: Quad, b: Quad): Float {
            var poly = a.points()
            val clip = b.points()
            // 绕向必须取自**裁剪多边形自己**，不能从被裁剪多边形反推 —— 见 clipEdge 的注释。
            val clipSign = if (signedArea(clip) >= 0f) 1f else -1f
            for (i in clip.indices) {
                val c1 = clip[i]
                val c2 = clip[(i + 1) % clip.size]
                poly = clipEdge(poly, c1, c2, clipSign)
                if (poly.isEmpty()) return 0f
            }
            return abs(signedArea(poly))
        }

        /**
         * Sutherland–Hodgman 的单边裁剪。
         *
         * clipSign 必须由调用方从**裁剪多边形自身的签名面积**算出并传进来。
         * 曾经的写法是拿被裁剪多边形各顶点的 side 值求和来反推绕向 —— 那是错的：
         * 两个多边形完全不相交时，被裁剪多边形所有顶点都落在同一（外）侧，
         * 求和的符号跟着翻转，inside() 于是对所有点都成立，四条边裁下来原样返回，
         * 交集面积等于被裁剪多边形的面积，iou 算出 1.0。
         * 后果是候选去重会把两个毫不相干的框判成同一个。
         */
        private fun clipEdge(poly: List<PointF>, c1: PointF, c2: PointF, clipSign: Float): List<PointF> {
            if (poly.isEmpty()) return poly
            fun side(p: PointF) = (c2.x - c1.x) * (p.y - c1.y) - (c2.y - c1.y) * (p.x - c1.x)
            fun inside(p: PointF) = side(p) * clipSign >= -1e-5f
            fun intersect(p: PointF, q: PointF): PointF {
                val d1 = side(p); val d2 = side(q)
                val t = d1 / (d1 - d2)
                return PointF(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
            }
            val out = ArrayList<PointF>()
            for (i in poly.indices) {
                val cur = poly[i]
                val prev = poly[(i + poly.size - 1) % poly.size]
                if (inside(cur)) {
                    if (!inside(prev)) out.add(intersect(prev, cur))
                    out.add(cur)
                } else if (inside(prev)) {
                    out.add(intersect(prev, cur))
                }
            }
            return out
        }

        /** 鞋带公式的签名面积。符号即绕向，外法线方向和 inside 判定都靠它。 */
        private fun signedArea(v: List<PointF>): Float {
            var s = 0f
            for (i in v.indices) {
                val q = v[(i + 1) % v.size]
                s += v[i].x * q.y - q.x * v[i].y
            }
            return s / 2f
        }

        /** 两条直线（不是线段）的交点；平行时返回 null。 */
        private fun lineIntersection(a1: PointF, a2: PointF, b1: PointF, b2: PointF): PointF? {
            val d1x = a2.x - a1.x
            val d1y = a2.y - a1.y
            val d2x = b2.x - b1.x
            val d2y = b2.y - b1.y
            val den = d1x * d2y - d1y * d2x
            if (abs(den) < 1e-6f) return null      // 相邻边平行：退化四边形，交给调用方兜底
            val t = ((b1.x - a1.x) * d2y - (b1.y - a1.y) * d2x) / den
            return PointF(a1.x + d1x * t, a1.y + d1y * t)
        }

        private fun distanceToSegment(p: PointF, a: PointF, b: PointF): Float {
            val dx = b.x - a.x; val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            if (len2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
            var t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2
            t = t.coerceIn(0f, 1f)
            return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
        }
    }
}
