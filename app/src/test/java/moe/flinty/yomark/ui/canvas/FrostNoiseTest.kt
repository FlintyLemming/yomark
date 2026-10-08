package moe.flinty.yomark.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class FrostNoiseTest {

    @Test
    fun `triangle noise stays in range and piles up around zero`() {
        var nearZero = 0
        var nearEdge = 0
        var sum = 0.0
        val n = 200 * 200
        for (r in 0 until 200) for (c in 0 until 200) {
            val v = FrostNoise.triangleNoise(c * 1.37f + 0.31f, r * 1.73f + 0.57f)
            assertThat(v).isAtLeast(-1f)
            assertThat(v).isLessThan(1f)
            sum += v
            if (abs(v) < 0.2f) nearZero++
            if (abs(v) > 0.8f) nearEdge++
        }
        // 三角分布：|v| < 0.2 约占 36%，|v| > 0.8 约占 4%
        assertThat(abs(sum / n)).isLessThan(0.05)
        assertThat(nearZero.toDouble() / n).isWithin(0.06).of(0.36)
        assertThat(nearEdge.toDouble() / n).isWithin(0.03).of(0.04)
    }

    @Test
    fun `only a narrow band of cells ever sparkles`() {
        val o = FloatArray(FrostNoise.BANDS)
        var lit = 0
        val n = 300 * 300
        for (r in 0 until 300) for (c in 0 until 300) {
            FrostNoise.sparkleBands(FrostNoise.triangleNoise(c * 1.37f + 0.31f, r * 1.73f + 0.57f), o)
            if (o.max() >= FrostEffect.SPARKLE_THRESHOLD) lit++
        }
        // 噪声值落在 0.077..0.16 那条窄带里的格子才会亮，约占 7%
        assertThat(lit.toDouble() / n).isWithin(0.025).of(0.073)
    }

    @Test
    fun `a cell well outside the band never lights up`() {
        val o = FloatArray(FrostNoise.BANDS)
        listOf(-0.9f, -0.3f, 0.3f, 0.9f).forEach { n ->
            FrostNoise.sparkleBands(n, o)
            assertThat(o.max()).isLessThan(0.05f)
        }
        // 正落在窄带里的格子，至少有一档很亮
        FrostNoise.sparkleBands(0.11f, o)
        assertThat(o.max()).isGreaterThan(0.9f)
    }

    @Test
    fun `a sparkle twinkles over time and stays bounded`() {
        val o = FloatArray(FrostNoise.BANDS)
        FrostNoise.sparkleBands(0.12f, o)
        val samples = (0 until 200).map { FrostNoise.twinkle(o, 0, 4f + it * 0.01f) }
        samples.forEach {
            assertThat(it).isAtLeast(0f)
            assertThat(it).isAtMost(FrostNoise.BANDS.toFloat())
        }
        // 两秒里明暗交替，不是一直亮着
        assertThat(samples.max() - samples.min()).isGreaterThan(0.5f)
    }

    @Test
    fun `sparkles gather where the noise is low`() {
        assertThat(FrostNoise.sparkleMask(-0.6f)).isGreaterThan(FrostNoise.sparkleMask(0f))
        assertThat(FrostNoise.sparkleMask(0f)).isGreaterThan(0f)
        assertThat(FrostNoise.sparkleMask(0.3f)).isEqualTo(0f)
        assertThat(FrostNoise.sparkleMask(1f)).isEqualTo(0f)
    }

    @Test
    fun `simplex noise varies smoothly and stays roughly within -1 and 1`() {
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (i in 0 until 60) for (j in 0 until 60) {
            val x = i * 0.137f
            val y = j * 0.113f
            val z = 2.5f
            val v = FrostNoise.simplex3d(x, y, z)
            assertThat(abs(v)).isAtMost(1.2f)
            // 挪一点点，值也只变一点点：没有接缝、没有跳变
            assertThat(abs(FrostNoise.simplex3d(x + 0.01f, y, z) - v)).isLessThan(0.08f)
            assertThat(abs(FrostNoise.simplex3d(x, y, z + 0.01f) - v)).isLessThan(0.08f)
            sum += v; sumSq += v * v; count++
        }
        val mean = sum / count
        val std = sqrt(sumSq / count - mean * mean)
        assertThat(abs(mean)).isLessThan(0.15)
        assertThat(std).isGreaterThan(0.1)
    }

    @Test
    fun `simplex noise is deterministic`() {
        assertThat(FrostNoise.simplex3d(1.234f, 5.678f, 0.9f)).isEqualTo(FrostNoise.simplex3d(1.234f, 5.678f, 0.9f))
    }
}
