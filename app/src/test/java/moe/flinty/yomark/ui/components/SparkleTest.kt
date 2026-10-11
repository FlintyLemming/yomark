package moe.flinty.yomark.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SparkleTest {

    @Test
    fun `at rest the big star sits in front at the lower left and the small one behind at the upper right`() {
        val (back, front) = Sparkle.stars(0f)
        assertThat(front.radius).isGreaterThan(back.radius)
        assertThat(front.center.x).isLessThan(back.center.x)
        assertThat(front.center.y).isGreaterThan(back.center.y)
    }

    @Test
    fun `the star drawn last is always the nearer, bigger one, so it covers the other`() {
        for (i in 0..200) {
            val (back, front) = Sparkle.stars(i / 200f)
            assertThat(front.depth).isAtLeast(back.depth)
            assertThat(front.radius).isAtLeast(back.radius)
        }
    }

    @Test
    fun `a quarter of the way the two cross, nearest and farthest, close enough to overlap`() {
        val (back, front) = Sparkle.stars(0.25f)
        assertThat(front.radius).isWithin(1e-4f).of(Sparkle.FRONT_RADIUS)
        assertThat(back.radius).isWithin(1e-4f).of(Sparkle.BACK_RADIUS)
        assertThat((front.center - back.center).getDistance()).isLessThan(front.radius)
        // 镜头稍高：后面那颗在前面那颗的左上方露出来
        assertThat(back.center.x).isLessThan(front.center.x)
        assertThat(back.center.y).isLessThan(front.center.y)
    }

    @Test
    fun `three quarters of the way the two are side by side at the far ends, equally near and equally big`() {
        val (a, b) = Sparkle.stars(0.75f)
        assertThat(a.radius).isWithin(1e-4f).of(b.radius)
        assertThat((a.center - b.center).getDistance()).isWithin(1e-4f).of(2 * Sparkle.TO_END.getDistance())
    }

    @Test
    fun `after half a turn the picture is back to the resting icon, so the loop has no seam`() {
        for ((a, b) in Sparkle.stars(0f).zip(Sparkle.stars(1f))) {
            assertThat(b.radius).isWithin(1e-4f).of(a.radius)
            assertThat(b.center.x).isWithin(1e-4f).of(a.center.x)
            assertThat(b.center.y).isWithin(1e-4f).of(a.center.y)
        }
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
