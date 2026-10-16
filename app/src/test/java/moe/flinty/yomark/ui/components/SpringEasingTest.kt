package moe.flinty.yomark.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp

/** 弹簧走成的曲线（见 SpringEasing）：照 FlClash 的 SpringCurve，收回去用的就是它。 */
class SpringEasingTest {

    private val collapse = SpringEasing(response = 0.27f, seconds = 0.3f)

    @Test fun `starts at 0 and lands exactly on 1`() {
        assertThat(collapse.transform(0f)).isWithin(1e-6f).of(0f)
        assertThat(collapse.transform(1f)).isWithin(1e-6f).of(1f)
    }

    /** ease-out 一下就是最快，头一帧（60 Hz，300 ms 里的 1/18）就走掉 16%；弹簧从静止起步。 */
    @Test fun `starts from rest instead of at full speed`() {
        assertThat(collapse.transform(0.001f) / 0.001f).isLessThan(0.1f)
        assertThat(collapse.transform(1f / 18)).isLessThan(0.08f)
    }

    @Test fun `without bounce it never goes back or past the end`() {
        var last = 0f
        for (i in 1..1000) {
            val v = collapse.transform(i / 1000f)
            assertThat(v).isAtLeast(last)
            assertThat(v).isAtMost(1f + 1e-6f)
            last = v
        }
    }

    /** 时长给得够长时补的那一点可以不计，就是临界阻尼的弹簧本身：1 - (1 + ωt)e^(-ωt)，ω = 2π / 感知时长。 */
    @Test fun `follows a critically damped spring`() {
        val spring = SpringEasing(response = 0.3f, seconds = 10f)
        val omega = 2 * PI / 0.3
        for (t in listOf(0.05, 0.1, 0.2)) {
            val expected = 1 - (1 + omega * t) * exp(-omega * t)
            assertThat(spring.transform((t / 10).toFloat()).toDouble()).isWithin(1e-4).of(expected)
        }
    }

    @Test fun `with bounce it overshoots and still lands on 1`() {
        val bouncy = SpringEasing(response = 0.3f, bounce = 0.3f, seconds = 0.6f)
        assertThat((1..100).maxOf { bouncy.transform(it / 100f) }).isGreaterThan(1.01f)
        assertThat(bouncy.transform(1f)).isWithin(1e-6f).of(1f)
    }
}
