package com.youma.app.core.geometry

import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
class QuadTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    @Test
    fun `bounds of an axis-aligned quad is the same rect`() {
        assertThat(rect(10f, 20f, 30f, 40f).bounds()).isEqualTo(RectF(10f, 20f, 30f, 40f))
    }

    @Test
    fun `bounds of a rotated quad covers all four points`() {
        val q = Quad(PointF(10f, 0f), PointF(20f, 10f), PointF(10f, 20f), PointF(0f, 10f))
        assertThat(q.bounds()).isEqualTo(RectF(0f, 0f, 20f, 20f))
    }

    @Test
    fun `expand pushes every edge outward by the given px`() {
        val e = rect(10f, 10f, 20f, 20f).expand(5f)
        assertThat(e.bounds()).isEqualTo(RectF(5f, 5f, 25f, 25f))
    }

    @Test
    fun `scaled multiplies every coordinate`() {
        val s = rect(10f, 20f, 30f, 40f).scaled(2f)
        assertThat(s.bounds()).isEqualTo(RectF(20f, 40f, 60f, 80f))
    }

    @Test
    fun `iou of identical quads is one`() {
        assertThat(rect(0f, 0f, 10f, 10f).iou(rect(0f, 0f, 10f, 10f))).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `iou of disjoint quads is zero`() {
        assertThat(rect(0f, 0f, 10f, 10f).iou(rect(20f, 20f, 30f, 30f))).isEqualTo(0f)
    }

    @Test
    fun `iou of half-overlapping quads is one third`() {
        // 两个 10x10，重叠 5x10=50，并集 150 → 1/3
        val v = rect(0f, 0f, 10f, 10f).iou(rect(5f, 0f, 15f, 10f))
        assertThat(v).isWithin(1e-3f).of(1f / 3f)
    }

    @Test
    fun `contains hits inside and misses outside for a rotated quad`() {
        val diamond = Quad(PointF(10f, 0f), PointF(20f, 10f), PointF(10f, 20f), PointF(0f, 10f))
        assertThat(diamond.contains(PointF(10f, 10f))).isTrue()
        assertThat(diamond.contains(PointF(1f, 1f))).isFalse()   // 在外接框内但在菱形外
    }

    @Test
    fun `shortEdge returns the shorter side length`() {
        assertThat(rect(0f, 0f, 30f, 10f).shortEdge()).isWithin(1e-3f).of(10f)
    }

    @Test
    fun `boundingQuad of a single quad returns an equivalent quad`() {
        val q = rect(5f, 5f, 15f, 25f)
        val b = Quad.boundingQuad(listOf(q))
        assertThat(b.bounds()).isEqualTo(q.bounds())
    }

    @Test
    fun `boundingQuad of two adjacent quads covers both`() {
        val b = Quad.boundingQuad(listOf(rect(0f, 0f, 10f, 10f), rect(20f, 0f, 30f, 10f)))
        assertThat(b.bounds()).isEqualTo(RectF(0f, 0f, 30f, 10f))
    }

    @Test
    fun `boundingQuad of tilted quads is tighter than the axis-aligned bounds`() {
        // 沿 45 度排列的两个小方块：轴对齐外接矩形面积远大于最小旋转矩形
        val a = Quad(PointF(0f, 0f), PointF(10f, 10f), PointF(8f, 12f), PointF(-2f, 2f))
        val b = Quad(PointF(20f, 20f), PointF(30f, 30f), PointF(28f, 32f), PointF(18f, 22f))
        val hull = Quad.boundingQuad(listOf(a, b))
        val axisArea = hull.bounds().width() * hull.bounds().height()
        assertThat(hull.area()).isLessThan(axisArea * 0.6f)
        // 且必须真的包住所有输入点
        (a.points() + b.points()).forEach { p ->
            assertThat(hull.contains(p) || hull.distanceTo(p) < 0.01f).isTrue()
        }
    }
}
