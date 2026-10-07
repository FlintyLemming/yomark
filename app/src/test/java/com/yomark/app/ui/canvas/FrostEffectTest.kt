package com.yomark.app.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class FrostEffectTest {

    // 图像坐标下的尺寸：一张 1000 × 1600 的图，从正中散开；起始半径 12、边缘 16..96、渐显区 110
    private val width = 1000f
    private val height = 1600f
    private val ox = 500f
    private val oy = 800f
    private val start = 12f
    private val minEdge = 16f
    private val maxEdge = 96f
    private val feather = 110f
    private val end = FrostEffect.endRadius(ox, oy, width, height, maxEdge + feather)

    private fun radius(ms: Long) = FrostEffect.holeRadius(ms, start, end)
    private fun edge(ms: Long) = FrostEffect.edgeWidth(ms, start, end, minEdge, maxEdge)
    private fun frost(d: Float, ms: Long) = FrostEffect.frost(d, radius(ms), edge(ms))
    private fun coverage(d: Float, ms: Long) = FrostEffect.coverage(d, radius(ms), edge(ms), feather)

    /** 图上若干点离圆心的距离：圆心、中段、四个角里最远的那个。 */
    private val distances = listOf(0f, 200f, 500f, 800f, hypot(500f, 800f))

    @Test
    fun `the frost fades in when recognition starts and then holds`() {
        assertThat(FrostEffect.strength(0L, null)).isEqualTo(0f)
        // 先快后慢：一半时间里已经过半
        assertThat(FrostEffect.strength(FrostEffect.FADE_IN_MS / 2, null)).isGreaterThan(0.5f)
        assertThat(FrostEffect.strength(FrostEffect.FADE_IN_MS, null)).isEqualTo(1f)
        assertThat(FrostEffect.strength(60_000L, null)).isEqualTo(1f)
        var prev = 0f
        for (ms in 0L..FrostEffect.FADE_IN_MS step 20L) {
            val s = FrostEffect.strength(ms, null)
            assertThat(s).isAtLeast(prev)
            prev = s
        }
    }

    @Test
    fun `a recognition faster than the fade-in freezes the frost where it was`() {
        val scanAt = FrostEffect.FADE_IN_MS / 3
        val frozen = FrostEffect.strength(scanAt, null)
        assertThat(frozen).isLessThan(1f)
        listOf(0L, 100L, 500L, FrostEffect.REVEAL_MS).forEach { since ->
            assertThat(FrostEffect.strength(scanAt + since, since)).isEqualTo(frozen)
        }
    }

    @Test
    fun `sparkles reach full strength before the frost does`() {
        assertThat(FrostEffect.sparkleStrength(0f)).isEqualTo(0f)
        assertThat(FrostEffect.sparkleStrength(0.5f)).isGreaterThan(0.5f)
        assertThat(FrostEffect.sparkleStrength(0.75f)).isEqualTo(1f)
        assertThat(FrostEffect.sparkleStrength(1f)).isEqualTo(1f)
    }

    @Test
    fun `the sparkles keep their clock across the hand-over to the reveal`() {
        assertThat(FrostEffect.timeSec(0L)).isEqualTo(FrostEffect.TIME_SEED_MS / 1000f)
        assertThat(FrostEffect.timeSec(1_500L) - FrostEffect.timeSec(500L)).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `the hole needs to reach the farthest corner plus the softest edge and the fade-in zone`() {
        assertThat(end).isWithin(1e-3f).of(hypot(500f, 800f) + maxEdge + feather)
        // 圆心不在正中时，量的是最远的那个角
        assertThat(FrostEffect.endRadius(100f, 200f, width, height, 0f)).isWithin(1e-3f).of(hypot(900f, 1400f))
        assertThat(FrostEffect.endRadius(950f, 1500f, width, height, 0f)).isWithin(1e-3f).of(hypot(950f, 1500f))
    }

    @Test
    fun `the hole grows from under the loader past the farthest corner, faster and faster`() {
        assertThat(radius(0L)).isEqualTo(start)
        assertThat(radius(FrostEffect.REVEAL_MS)).isWithin(1e-3f).of(end)
        assertThat(radius(FrostEffect.REVEAL_MS * 3)).isWithin(1e-3f).of(end)
        var prevStep = 0f
        for (ms in 10L..FrostEffect.REVEAL_MS step 10L) {
            val step = radius(ms) - radius(ms - 10L)
            assertThat(step).isGreaterThan(prevStep)
            prevStep = step
        }
        // 冲出去时比起步快得多，而且一直在加速，没有收尾时的减速
        assertThat(FrostEffect.holeSpeed(FrostEffect.REVEAL_MS, start, end))
            .isGreaterThan(10f * FrostEffect.holeSpeed(0L, start, end))
    }

    @Test
    fun `on a phone the frost is gone from every corner in under half a second`() {
        // 412 × 641 dp 的画布上整图适配，一 dp 一个单位
        val far = hypot(206f, 320.5f)
        val e = FrostEffect.endRadius(206f, 320.5f, 412f, 641f, FrostEffect.EDGE_MAX_DP + FrostEffect.FEATHER_DP)
        val s = FrostEffect.HOLE_START_DP
        fun cleared(ms: Long) = FrostEffect.holeRadius(ms, s, e) -
            FrostEffect.edgeWidth(ms, s, e, FrostEffect.EDGE_DP, FrostEffect.EDGE_MAX_DP)
        val gone = (0L..FrostEffect.REVEAL_MS).first { cleared(it) >= far }
        assertThat(gone).isAtMost(460L)
        // 洞在图标还没散完时就冒出来了，不留空档
        val peek = (0L..FrostEffect.REVEAL_MS).first { FrostEffect.holeRadius(it, s, e) >= MorphingLoader.INDICATOR_DP / 2f }
        assertThat(peek).isLessThan(FrostEffect.BURST_MS / 2)
    }

    @Test
    fun `the edge softens as the hole speeds up, within its bounds`() {
        assertThat(edge(0L)).isEqualTo(minEdge)
        var prev = edge(0L)
        for (ms in 10L..FrostEffect.REVEAL_MS step 10L) {
            val e = edge(ms)
            assertThat(e).isAtLeast(prev)
            assertThat(e).isAtMost(maxEdge)
            prev = e
        }
        // 冲出去时边缘正好是它最后这一段在 MOTION_BLUR_MS 里走过的距离（这张图大，夹到了上限）
        assertThat(edge(FrostEffect.REVEAL_MS)).isEqualTo(maxEdge)
        val speed = FrostEffect.holeSpeed(FrostEffect.REVEAL_MS / 2, start, end)
        assertThat(edge(FrostEffect.REVEAL_MS / 2)).isWithin(1e-3f).of((speed * FrostEffect.MOTION_BLUR_MS).coerceIn(minEdge, maxEdge))
    }

    @Test
    fun `the hole starts hidden under the loader`() {
        // 结果到达那一帧，磨砂只在图标底下那一小块里开始变薄
        assertThat(FrostEffect.HOLE_START_DP).isLessThan(MorphingLoader.INDICATOR_DP / 2f)
        assertThat(FrostEffect.frost(FrostEffect.HOLE_START_DP, FrostEffect.HOLE_START_DP, FrostEffect.EDGE_DP)).isEqualTo(1f)
    }

    @Test
    fun `nothing is revealed away from the centre when results arrive, and everything is by the end`() {
        distances.filter { it >= start }.forEach { d ->
            assertThat(coverage(d, 0L)).isEqualTo(0f)
            assertThat(frost(d, 0L)).isEqualTo(1f)
        }
        distances.forEach { d ->
            assertThat(coverage(d, FrostEffect.REVEAL_MS)).isEqualTo(1f)
            assertThat(frost(d, FrostEffect.REVEAL_MS)).isEqualTo(0f)
        }
    }

    @Test
    fun `results appear from the centre outwards and never run backwards`() {
        val mid = FrostEffect.REVEAL_MS * 3 / 4
        assertThat(coverage(0f, mid)).isAtLeast(coverage(300f, mid))
        assertThat(coverage(300f, mid)).isAtLeast(coverage(800f, mid))
        distances.forEach { d ->
            var prevCoverage = 0f
            var prevFrost = 1f
            for (ms in 0L..FrostEffect.REVEAL_MS step 5L) {
                val c = coverage(d, ms)
                val f = frost(d, ms)
                assertThat(c).isAtLeast(prevCoverage)
                assertThat(f).isAtMost(prevFrost)
                prevCoverage = c
                prevFrost = f
            }
        }
    }

    @Test
    fun `the frost clears exactly inside the hole, with a soft edge`() {
        val r = 400f
        val e = 20f
        assertThat(FrostEffect.frost(r - e - 1f, r, e)).isEqualTo(0f)
        assertThat(FrostEffect.frost(r, r, e)).isEqualTo(1f)
        assertThat(FrostEffect.frost(r + 100f, r, e)).isEqualTo(1f)
        val halfway = FrostEffect.frost(r - e / 2f, r, e)
        assertThat(halfway).isGreaterThan(0f)
        assertThat(halfway).isLessThan(1f)
    }

    @Test
    fun `the original shows through before a block is covered`() {
        // 磨砂刚好完全揭开的那一圈上，识别结果还一点没有：用户先看见底下原来是什么，再看着它被盖住
        for (ms in 0L..FrostEffect.REVEAL_MS step 10L) {
            val cleared = radius(ms) - edge(ms)
            if (cleared <= 0f) continue
            assertThat(frost(cleared, ms)).isEqualTo(0f)
            assertThat(coverage(cleared, ms)).isEqualTo(0f)
            assertThat(coverage(cleared - feather / 2f, ms)).isWithin(1e-4f).of(0.5f)
        }
    }

    @Test
    fun `the glitter ring rides the edge of the hole`() {
        val w = 36f
        assertThat(FrostEffect.ring(400f, 400f, w)).isEqualTo(1f)
        assertThat(FrostEffect.ring(400f + w, 400f, w)).isEqualTo(0f)
        assertThat(FrostEffect.ring(400f - w, 400f, w)).isEqualTo(0f)
        assertThat(FrostEffect.ring(410f, 400f, w)).isWithin(1e-6f).of(FrostEffect.ring(390f, 400f, w))
        assertThat(FrostEffect.ring(410f, 400f, w)).isGreaterThan(FrostEffect.ring(420f, 400f, w))
        assertThat(FrostEffect.ring(400f, 400f, 0f)).isEqualTo(0f)
    }

    @Test
    fun `the loader pops in, holds, and bursts out as the hole opens`() {
        assertThat(FrostEffect.loaderAlpha(0L, null)).isEqualTo(0f)
        assertThat(FrostEffect.loaderScale(0L, null)).isWithin(1e-6f).of(0.6f)
        assertThat(FrostEffect.loaderAlpha(FrostEffect.LOADER_IN_MS, null)).isEqualTo(1f)
        assertThat(FrostEffect.loaderScale(FrostEffect.LOADER_IN_MS, null)).isEqualTo(1f)
        assertThat(FrostEffect.loaderAlpha(30_000L, null)).isEqualTo(1f)

        val scanAt = 2_000L
        assertThat(FrostEffect.loaderAlpha(scanAt, 0L)).isEqualTo(1f)
        assertThat(FrostEffect.loaderScale(scanAt, 0L)).isEqualTo(1f)
        var prevAlpha = 1f
        var prevScale = 1f
        for (since in 10L..FrostEffect.BURST_MS step 10L) {
            val a = FrostEffect.loaderAlpha(scanAt + since, since)
            val s = FrostEffect.loaderScale(scanAt + since, since)
            assertThat(a).isAtMost(prevAlpha)
            assertThat(s).isAtLeast(prevScale)
            prevAlpha = a
            prevScale = s
        }
        assertThat(FrostEffect.loaderAlpha(scanAt + FrostEffect.BURST_MS, FrostEffect.BURST_MS)).isEqualTo(0f)
        assertThat(FrostEffect.loaderScale(scanAt + FrostEffect.BURST_MS, FrostEffect.BURST_MS)).isWithin(1e-6f).of(1.5f)
        // 图标比洞先退场：洞散开的这一遍里，后一半画面上只有洞
        assertThat(FrostEffect.BURST_MS).isLessThan(FrostEffect.REVEAL_MS / 2)
    }

    @Test
    fun `a recognition faster than the loader's entrance bursts out from where it got to`() {
        val scanAt = FrostEffect.LOADER_IN_MS / 2
        val alphaAtResult = FrostEffect.loaderAlpha(scanAt, null)
        assertThat(FrostEffect.loaderAlpha(scanAt, 0L)).isEqualTo(alphaAtResult)
        assertThat(FrostEffect.loaderAlpha(scanAt + 50L, 50L)).isLessThan(alphaAtResult)
    }

    @Test
    fun `the reveal settles in a fixed time`() {
        assertThat(FrostEffect.SETTLE_MS).isEqualTo(FrostEffect.REVEAL_MS)
    }
}
