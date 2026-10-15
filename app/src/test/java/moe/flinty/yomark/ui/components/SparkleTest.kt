package moe.flinty.yomark.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SparkleTest {

    @Test
    fun `at rest the ring puts the three stars exactly where the icon has them`() {
        for ((star, rest) in Sparkle.ring(0f).zip(Sparkle.REST)) {
            assertThat(star.radius).isWithin(1e-4f).of(rest.radius)
            assertThat(star.center.x).isWithin(1e-4f).of(rest.center.x)
            assertThat(star.center.y).isWithin(1e-4f).of(rest.center.y)
        }
    }

    @Test
    fun `at rest a big star at the left, a middle one at the upper right and a small one at the lower right`() {
        val (big, middle, small) = Sparkle.ring(0f)
        assertThat(big.radius).isGreaterThan(middle.radius)
        assertThat(middle.radius).isGreaterThan(small.radius)
        assertThat(big.center.x).isLessThan(middle.center.x)
        assertThat(big.center.x).isLessThan(small.center.x)
        assertThat(middle.center.y).isLessThan(small.center.y)
        // 大小来自远近：大的最近
        assertThat(big.depth).isGreaterThan(middle.depth)
        assertThat(middle.depth).isGreaterThan(small.depth)
    }

    @Test
    fun `the stars are drawn far to near and the nearer is never the smaller`() {
        for (i in 0..300) {
            val stars = Sparkle.stars(i / 300f)
            for ((far, near) in stars.zipWithNext()) {
                assertThat(near.depth).isAtLeast(far.depth)
                assertThat(near.radius).isAtLeast(far.radius)
            }
        }
    }

    @Test
    fun `they turn clockwise, the big star rising over the top towards the upper right`() {
        val big = { p: Float -> Sparkle.ring(p)[0] }
        assertThat(big(0.2f).center.y).isLessThan(big(0f).center.y)
        // 一拍走完，大星到了中星的位置上，跟着就变成中星那么大
        assertThat(big(1f).center.x).isWithin(1e-4f).of(Sparkle.REST[1].center.x)
        assertThat(big(1f).center.y).isWithin(1e-4f).of(Sparkle.REST[1].center.y)
        assertThat(big(1f).radius).isWithin(1e-4f).of(Sparkle.REST[1].radius)
    }

    @Test
    fun `after a third of a turn the picture is back to the resting icon, so the loop has no seam`() {
        for ((a, b) in Sparkle.stars(0f).zip(Sparkle.stars(1f))) {
            assertThat(b.radius).isWithin(1e-4f).of(a.radius)
            assertThat(b.center.x).isWithin(1e-4f).of(a.center.x)
            assertThat(b.center.y).isWithin(1e-4f).of(a.center.y)
        }
    }

    @Test
    fun `each beat starts and ends at rest instead of spinning at constant speed`() {
        assertThat(Sparkle.EASING.transform(0.05f)).isLessThan(0.01f)
        assertThat(Sparkle.EASING.transform(0.95f)).isGreaterThan(0.99f)
        // 中间比匀速快
        assertThat(Sparkle.EASING.transform(0.55f) - Sparkle.EASING.transform(0.45f)).isGreaterThan(0.15f)
    }

    @Test
    fun `at rest the icon fills the box like a Material icon but stays inside it`() {
        val stars = Sparkle.stars(0f)
        val left = stars.minOf { it.center.x - it.radius }
        val right = stars.maxOf { it.center.x + it.radius }
        val top = stars.minOf { it.center.y - it.radius }
        val bottom = stars.maxOf { it.center.y + it.radius }
        assertThat(left).isAtLeast(0f)
        assertThat(top).isAtLeast(0f)
        assertThat(right).isAtMost(1f)
        assertThat(bottom).isAtMost(1f)
        // Material 图标的墨迹约占 24 格里的 20 格
        assertThat(right - left).isAtLeast(0.8f)
        assertThat(bottom - top).isAtLeast(0.8f)
    }

    @Test
    fun `while turning the stars leave the box by no more than the margin`() {
        for (i in 0..300) {
            for (star in Sparkle.stars(i / 300f)) {
                assertThat(star.center.x - star.radius).isAtLeast(-Sparkle.MARGIN)
                assertThat(star.center.x + star.radius).isAtMost(1f + Sparkle.MARGIN)
                assertThat(star.center.y - star.radius).isAtLeast(-Sparkle.MARGIN)
                assertThat(star.center.y + star.radius).isAtMost(1f + Sparkle.MARGIN)
            }
        }
    }
}
