package com.youma.app.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.hypot

class FrostEffectTest {

    // 图像坐标下的尺寸：一张 1000 × 1600 的图，从正中散开；起始半径 12、边缘 20、渐显区 110
    private val width = 1000f
    private val height = 1600f
    private val ox = 500f
    private val oy = 800f
    private val start = 12f
    private val edge = 20f
    private val feather = 110f
    private val end = FrostEffect.endRadius(ox, oy, width, height, feather)

    private fun radius(ms: Long) = FrostEffect.holeRadius(ms, start, end)

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
    fun `the hole needs to reach the farthest corner plus the fade-in zone`() {
        assertThat(end).isWithin(1e-3f).of(hypot(500f, 800f) + feather)
        // 圆心不在正中时，量的是最远的那个角
        assertThat(FrostEffect.endRadius(100f, 200f, width, height, 0f)).isWithin(1e-3f).of(hypot(900f, 1400f))
        assertThat(FrostEffect.endRadius(950f, 1500f, width, height, 0f)).isWithin(1e-3f).of(hypot(950f, 1500f))
    }

    @Test
    fun `the hole grows from under the loader to past the farthest corner, only outwards`() {
        assertThat(radius(0L)).isEqualTo(start)
        assertThat(radius(FrostEffect.REVEAL_MS)).isWithin(1e-3f).of(end)
        assertThat(radius(FrostEffect.REVEAL_MS * 3)).isWithin(1e-3f).of(end)
        var prev = radius(0L)
        for (ms in 10L..FrostEffect.REVEAL_MS step 10L) {
            val r = radius(ms)
            assertThat(r).isGreaterThan(prev)
            prev = r
        }
        // 先慢后快再慢：起步与收尾都比中段慢
        val mid = radius(FrostEffect.REVEAL_MS / 2 + 50) - radius(FrostEffect.REVEAL_MS / 2 - 50)
        assertThat(radius(100L) - radius(0L)).isLessThan(mid)
        assertThat(radius(FrostEffect.REVEAL_MS) - radius(FrostEffect.REVEAL_MS - 100)).isLessThan(mid)
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
            assertThat(FrostEffect.coverage(d, radius(0L), feather)).isEqualTo(0f)
            assertThat(FrostEffect.frost(d, radius(0L), edge)).isEqualTo(1f)
        }
        distances.forEach { d ->
            assertThat(FrostEffect.coverage(d, radius(FrostEffect.REVEAL_MS), feather)).isEqualTo(1f)
            assertThat(FrostEffect.frost(d, radius(FrostEffect.REVEAL_MS), edge)).isEqualTo(0f)
        }
    }

    @Test
    fun `results appear from the centre outwards and never run backwards`() {
        val mid = radius(FrostEffect.REVEAL_MS / 2)
        assertThat(FrostEffect.coverage(0f, mid, feather)).isAtLeast(FrostEffect.coverage(300f, mid, feather))
        assertThat(FrostEffect.coverage(300f, mid, feather)).isAtLeast(FrostEffect.coverage(800f, mid, feather))
        distances.forEach { d ->
            var prevCoverage = 0f
            var prevFrost = 1f
            for (ms in 0L..FrostEffect.REVEAL_MS step 25L) {
                val c = FrostEffect.coverage(d, radius(ms), feather)
                val f = FrostEffect.frost(d, radius(ms), edge)
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
        assertThat(FrostEffect.frost(r - edge - 1f, r, edge)).isEqualTo(0f)
        assertThat(FrostEffect.frost(r, r, edge)).isEqualTo(1f)
        assertThat(FrostEffect.frost(r + 100f, r, edge)).isEqualTo(1f)
        val halfway = FrostEffect.frost(r - edge / 2f, r, edge)
        assertThat(halfway).isGreaterThan(0f)
        assertThat(halfway).isLessThan(1f)
    }

    @Test
    fun `the original shows through before a block is covered`() {
        // 磨砂刚揭开的地方，识别结果还几乎透明：用户先看见底下原来是什么，再看着它被盖住
        for (ms in 0L..FrostEffect.REVEAL_MS step 25L) {
            val r = radius(ms)
            if (r - edge <= 0f) continue
            assertThat(FrostEffect.frost(r - edge, r, edge)).isEqualTo(0f)
            assertThat(FrostEffect.coverage(r - edge, r, feather)).isLessThan(0.1f)
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
        // 图标比洞先退场：洞散开的这一遍里，大半时间画面上只有洞
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
