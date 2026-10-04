package com.youma.app.ui.canvas

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScanEffectTest {

    // 图像坐标下的尺寸：一张 1000 高的图，光尾 150、前沿 36、渐显区 110
    private val height = 1000f
    private val trail = 150f
    private val lead = 36f
    private val feather = 110f

    private fun loop(ms: Long) = ScanEffect.loopCenter(ScanEffect.loopPhase(ms, null)!!, height, lead, trail)
    private fun front(ms: Long) = ScanEffect.revealFront(ms, height, lead, trail, feather)
    private fun lightOnImage(center: Float) = (0..1000 step 10).maxOf { ScanEffect.glow(it.toFloat(), center, trail, lead) }

    @Test
    fun `the looping light starts and ends each pass entirely off the image so the wrap-around never pops`() {
        assertThat(lightOnImage(loop(0L))).isWithin(1e-6f).of(0f)
        assertThat(lightOnImage(loop(ScanEffect.PASS_MS - 1))).isWithin(1e-3f).of(0f)
        // 跳回顶部之后又是同一个起点
        assertThat(loop(ScanEffect.PASS_MS)).isEqualTo(loop(0L))
    }

    @Test
    fun `within a pass the light only moves down`() {
        var prev = loop(0L)
        for (ms in 20L until ScanEffect.PASS_MS step 20L) {
            val c = loop(ms)
            assertThat(c).isGreaterThan(prev)
            prev = c
        }
    }

    @Test
    fun `the looping light fades in when recognition starts and stays on`() {
        assertThat(ScanEffect.loopStrength(0L)).isEqualTo(0f)
        assertThat(ScanEffect.loopStrength(ScanEffect.FADE_IN_MS)).isEqualTo(1f)
        assertThat(ScanEffect.loopStrength(10_000L)).isEqualTo(1f)
    }

    @Test
    fun `when results arrive the light in flight speeds up and finishes its pass instead of vanishing`() {
        val scanAt = 10_000L + ScanEffect.PASS_MS / 3   // 结果到达时光正走到一趟的三分之一
        val finish = ScanEffect.finishMs(scanAt)
        val phase = { since: Long -> ScanEffect.loopPhase(scanAt + since, since) }
        // 那一刻光原地接着走，不跳
        assertThat(phase(0L)).isEqualTo(ScanEffect.loopPhase(scanAt, null))
        // 一直往下走，越走越快，最后提到落码那一遍的速度
        var prev = phase(0L)!!
        var prevStep = 0f
        for (since in 20L until finish step 20L) {
            val p = phase(since)!!
            assertThat(p).isGreaterThan(prev)
            assertThat(p - prev).isAtLeast(prevStep - 1e-3f)
            prevStep = p - prev
            prev = p
        }
        assertThat(prevStep / 20f).isWithin(1e-3f).of(ScanEffect.PASS_MS.toFloat() / ScanEffect.REVEAL_MS)
        // 比按原速走完要快
        assertThat(finish).isLessThan(ScanEffect.PASS_MS * 2 / 3)
        // 走完这一趟、整道光离开图像之后才轮到落码，光不再从顶上重来
        assertThat(lightOnImage(ScanEffect.loopCenter(phase(finish - 1)!!, height, lead, trail))).isWithin(1e-3f).of(0f)
        assertThat(phase(finish)).isNull()
        assertThat(ScanEffect.revealMs(scanAt + finish - 1, finish - 1)).isNull()
        assertThat(ScanEffect.revealMs(scanAt + finish, finish)).isEqualTo(0L)
        assertThat(ScanEffect.settleMs(scanAt)).isEqualTo(finish + ScanEffect.REVEAL_MS)
    }

    @Test
    fun `a light about to leave the image needs only a moment to finish`() {
        assertThat(ScanEffect.finishMs(ScanEffect.PASS_MS - 1)).isAtMost(1L)
        // 刚出发的那道光提速走完一整趟，也不比落码那一遍慢多少
        assertThat(ScanEffect.finishMs(0L)).isAtMost(ScanEffect.REVEAL_MS + ScanEffect.SPEED_UP_MS)
    }

    @Test
    fun `nothing is revealed when results arrive and everything is by the end of the pass`() {
        listOf(0f, 1f, 500f, 999f, 1000f).forEach { y ->
            assertThat(ScanEffect.coverage(y, front(0L), feather)).isEqualTo(0f)
            assertThat(ScanEffect.coverage(y, front(ScanEffect.REVEAL_MS), feather)).isEqualTo(1f)
        }
        // 终点处整道光也已离开图像，落码结束换回静止画法时画面不跳
        assertThat(lightOnImage(front(ScanEffect.REVEAL_MS))).isWithin(1e-6f).of(0f)
        assertThat(lightOnImage(front(0L))).isWithin(1e-6f).of(0f)
    }

    @Test
    fun `the scrim fades in with the light and stays even while recognition runs`() {
        assertThat(ScanEffect.scrim(500f, 0L, null, feather)).isEqualTo(0f)
        listOf(0f, 500f, 1000f).forEach { y ->
            assertThat(ScanEffect.scrim(y, ScanEffect.FADE_IN_MS, null, feather)).isEqualTo(ScanEffect.SCRIM_ALPHA)
        }
    }

    @Test
    fun `the scrim lifts exactly where results have been revealed`() {
        val f = front(ScanEffect.REVEAL_MS / 2)
        listOf(f - feather - 1f, f - feather / 2, f, f + 100f).forEach { y ->
            val scrim = ScanEffect.scrim(y, 10_000L, f, feather)
            val revealed = ScanEffect.coverage(y, f, feather)
            assertThat(scrim).isWithin(1e-6f).of(ScanEffect.SCRIM_ALPHA * (1f - revealed))
        }
        // 落码结束时整张图都已揭开
        assertThat(ScanEffect.scrim(1000f, 10_000L, front(ScanEffect.REVEAL_MS), feather)).isEqualTo(0f)
    }

    @Test
    fun `results reveal top to bottom and never run backwards`() {
        val mid = front(ScanEffect.REVEAL_MS / 2)
        assertThat(ScanEffect.coverage(100f, mid, feather)).isAtLeast(ScanEffect.coverage(500f, mid, feather))
        assertThat(ScanEffect.coverage(500f, mid, feather)).isAtLeast(ScanEffect.coverage(900f, mid, feather))
        listOf(0f, 400f, 1000f).forEach { y ->
            var prev = 0f
            for (ms in 0L..ScanEffect.REVEAL_MS step 35L) {
                val c = ScanEffect.coverage(y, front(ms), feather)
                assertThat(c).isAtLeast(prev)
                prev = c
            }
        }
    }

    @Test
    fun `every block is lit up by the light before its mask settles`() {
        // 渐显区的前半段整个落在光里：块还半透明的时候，底下的字正被光照着，用户看得见原来是什么
        for (ms in 0L..ScanEffect.REVEAL_MS step 10L) {
            val f = front(ms)
            (0..1000 step 5).map { it.toFloat() }.forEach { y ->
                val c = ScanEffect.coverage(y, f, feather)
                if (c > 0f && c < 0.5f) assertThat(ScanEffect.glow(y, f, trail, lead)).isGreaterThan(0.5f)
            }
        }
        // 光的中心以下一点都还没盖上
        assertThat(ScanEffect.coverage(501f, 500f, feather)).isEqualTo(0f)
    }

    @Test
    fun `the light is brightest at its centre and fades to nothing at both ends`() {
        assertThat(ScanEffect.glow(500f, 500f, trail, lead)).isEqualTo(1f)
        assertThat(ScanEffect.glow(500f - trail, 500f, trail, lead)).isEqualTo(0f)
        assertThat(ScanEffect.glow(500f + lead, 500f, trail, lead)).isEqualTo(0f)
        // 长尾在上（光走过的地方），前沿在下：同样的距离，上面比下面亮
        assertThat(ScanEffect.glow(480f, 500f, trail, lead)).isGreaterThan(ScanEffect.glow(520f, 500f, trail, lead))
    }

    @Test
    fun `travel eases at both ends and covers the whole way`() {
        assertThat(ScanEffect.travel(0f)).isEqualTo(0f)
        assertThat(ScanEffect.travel(1f)).isWithin(1e-6f).of(1f)
        assertThat(ScanEffect.travel(0.5f)).isWithin(1e-6f).of(0.5f)
        // 起步比中段慢
        assertThat(ScanEffect.travel(0.1f) - ScanEffect.travel(0f)).isLessThan(ScanEffect.travel(0.55f) - ScanEffect.travel(0.45f))
    }

    @Test
    fun `the hues keep flowing across the hand-over from scanning to revealing`() {
        assertThat(ScanEffect.drift(0L)).isEqualTo(0f)
        assertThat(ScanEffect.drift(ScanEffect.DRIFT_MS / 4)).isWithin(1e-6f).of(0.25f)
        assertThat(ScanEffect.drift(ScanEffect.DRIFT_MS)).isEqualTo(0f)
    }
}
