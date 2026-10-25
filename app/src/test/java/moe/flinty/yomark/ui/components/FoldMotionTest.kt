package moe.flinty.yomark.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 底栏一组展开、收回去时几样东西各走各的（见 foldSpec、panelFoldSpec、contentFoldSpec）。
 * 下面的「进度」都是 1 展开、0 收着，按毫秒取。
 */
class FoldMotionTest {

    private fun AnimationSpec<Float>.at(ms: Int, from: Float, to: Float) =
        TargetBasedAnimation(this, Float.VectorConverter, from, to).getValueFromNanos(ms * 1_000_000L)

    private fun AnimationSpec<Float>.closing(ms: Int) = at(ms, 1f, 0f)
    private fun AnimationSpec<Float>.opening(ms: Int) = at(ms, 0f, 1f)

    /** 「打码」「编辑」在这一行收到一半时淡回来；这时工具连最左边那个都淡没了，面板上的（淡得更早）也一样。 */
    @Test fun `leaving tools are gone before the groups fade back`() {
        val groupsBack = (0..300).first { foldSpec(false).closing(it) < FADE_OUT }
        assertThat(contentFoldSpec(false).closing(groupsBack)).isAtMost(TOOL_IN_START)
    }

    /** 面板先落回这一行，再跟着它缩回去：半路上高总比宽收得多，胶囊的角走一道弧。 */
    @Test fun `the panel drops ahead of the row narrowing`() {
        for (ms in listOf(33, 67, 100, 150)) {
            assertThat(panelFoldSpec(false).closing(ms)).isLessThan(foldSpec(false).closing(ms))
        }
    }

    @Test fun `opening keeps everything on one curve`() {
        for (ms in 0..350 step 10) {
            val row = foldSpec(true).opening(ms)
            assertThat(panelFoldSpec(true).opening(ms)).isEqualTo(row)
            assertThat(contentFoldSpec(true).opening(ms)).isEqualTo(row)
        }
    }

    @Test fun `everything is folded away within 300 ms`() {
        for (spec in listOf(foldSpec(false), panelFoldSpec(false), contentFoldSpec(false))) {
            assertThat(spec.closing(300)).isEqualTo(0f)
        }
    }
}
