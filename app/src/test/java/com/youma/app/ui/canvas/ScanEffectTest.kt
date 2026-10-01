package com.youma.app.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanEffectTest {

    private fun alpha(progress: Float, centerY: Float, manual: Boolean = false, scanning: Boolean = false) =
        ScanEffect.revealAlpha(manual, scanning, progress, centerY, imageHeight = 1000)

    @Test
    fun `detected items stay hidden while recognition runs`() {
        // 换方案重跑时旧结果先退场，不与扫描混在一起
        assertThat(alpha(1f, 100f, scanning = true)).isEqualTo(0f)
    }

    @Test
    fun `manual boxes are never faded`() {
        assertThat(alpha(0f, 500f, manual = true, scanning = true)).isEqualTo(1f)
        assertThat(alpha(0f, 500f, manual = true)).isEqualTo(1f)
    }

    @Test
    fun `results reveal top to bottom`() {
        val mid = 0.5f
        assertThat(alpha(mid, 0f)).isGreaterThan(alpha(mid, 500f))
        assertThat(alpha(mid, 500f)).isGreaterThan(alpha(mid, 1000f))
        assertThat(alpha(0f, 0f)).isEqualTo(0f)
    }

    @Test
    fun `everything is fully shown by the end of the reveal`() {
        listOf(0f, 333f, 1000f, 5000f, -20f).forEach { y ->
            assertThat(alpha(1f, y)).isEqualTo(1f)
        }
        // 最靠上的一块在淡入过半之前就已经完全出现
        assertThat(alpha(1f - ScanEffect.REVEAL_STAGGER, 0f)).isEqualTo(1f)
    }

    @Test
    fun `the reveal never runs backwards`() {
        listOf(0f, 400f, 1000f).forEach { y ->
            var prev = 0f
            for (i in 0..20) {
                val a = alpha(i / 20f, y)
                assertThat(a).isAtLeast(prev)
                prev = a
            }
        }
    }

    @Test
    fun `the band is invisible at both ends so the wrap-around does not flash`() {
        assertThat(ScanEffect.bandEnvelope(0f)).isWithin(1e-6f).of(0f)
        assertThat(ScanEffect.bandEnvelope(1f)).isWithin(1e-6f).of(0f)
        assertThat(ScanEffect.bandEnvelope(0.5f)).isWithin(1e-6f).of(1f)
    }
}
