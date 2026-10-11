package moe.flinty.yomark.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class SparkleTest {

    @Test
    fun `at rest the big star sits in front at the lower left and the small one behind at the upper right`() {
        val (big, small) = Sparkle.stars(0f).sortedByDescending { it.radius }
        assertThat(big.radius).isWithin(1e-4f).of(Sparkle.FRONT_RADIUS)
        assertThat(small.radius).isWithin(1e-4f).of(Sparkle.BACK_RADIUS)
        assertThat(big.center.x).isLessThan(small.center.x)
        assertThat(big.center.y).isGreaterThan(small.center.y)
    }

    @Test
    fun `after one swap the picture is back to the resting icon, so the loop has no seam`() {
        val start = Sparkle.stars(0f).sortedBy { it.radius }
        val end = Sparkle.stars(1f).sortedBy { it.radius }
        for ((a, b) in start.zip(end)) {
            assertThat(b.radius).isWithin(1e-4f).of(a.radius)
            assertThat(b.center.x).isWithin(1e-4f).of(a.center.x)
            assertThat(b.center.y).isWithin(1e-4f).of(a.center.y)
        }
    }

    @Test
    fun `halfway through a swap both stars are equally far, side by side at the upper left and lower right`() {
        val halfway = (0..1000).map { it / 1000f }
            .minBy { p -> Sparkle.stars(p).let { abs(it[0].radius - it[1].radius) } }
        val (upperLeft, lowerRight) = Sparkle.stars(halfway).sortedBy { it.center.y }
        val middle = (Sparkle.FRONT_RADIUS + Sparkle.BACK_RADIUS) / 2
        assertThat(upperLeft.radius).isWithin(0.005f).of(middle)
        assertThat(lowerRight.radius).isWithin(0.005f).of(middle)
        assertThat(upperLeft.center.x).isLessThan(Sparkle.ORBIT_CENTER.x)
        assertThat(lowerRight.center.x).isGreaterThan(Sparkle.ORBIT_CENTER.x)
    }

    @Test
    fun `the stars stay inside the icon all the way round`() {
        for (i in 0..200) {
            for (star in Sparkle.stars(i / 200f)) {
                assertThat(star.center.x - star.radius).isAtLeast(0f)
                assertThat(star.center.x + star.radius).isAtMost(1f)
                assertThat(star.center.y - star.radius).isAtLeast(0f)
                assertThat(star.center.y + star.radius).isAtMost(1f)
            }
        }
    }
}
