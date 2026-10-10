package moe.flinty.yomark.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SparkleTest {

    @Test
    fun `at rest the stars sit exactly like the static icon`() {
        val p = Sparkle.pose(0f)
        assertThat(p.bigDegrees).isWithin(1e-4f).of(0f)
        assertThat(p.smallDegrees).isWithin(1e-4f).of(0f)
        assertThat(p.bigScale).isWithin(1e-4f).of(1f)
        assertThat(p.smallScale).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `a cycle ends a half turn later, which a four-point star cannot tell from the start`() {
        val p = Sparkle.pose(1f)
        assertThat(p.bigDegrees).isWithin(1e-3f).of(180f)
        assertThat(p.smallDegrees).isWithin(1e-3f).of(-180f)
        assertThat(p.bigScale).isWithin(1e-4f).of(1f)
        assertThat(p.smallScale).isWithin(1e-4f).of(1f)
    }

    @Test
    fun `mid-cycle the big star shrinks while the small one grows, and the small one stays inside the icon`() {
        val p = Sparkle.pose(0.5f)
        assertThat(p.bigScale).isLessThan(1f)
        assertThat(p.smallScale).isGreaterThan(1f)
        val reach = Sparkle.SMALL_RADIUS * p.smallScale
        assertThat(Sparkle.SMALL_CENTER.y - reach).isAtLeast(0f)
        assertThat(Sparkle.SMALL_CENTER.x + reach).isAtMost(1f)
    }
}
