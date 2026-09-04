package com.dama.app.render

import com.dama.app.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 最后一条会构造全部六个渲染器，它们在构造期就碰 android.graphics.Paint —— 纯 JUnit 下 not mocked。
// 不读像素，所以不需要 NATIVE 图形模式。
@RunWith(RobolectricTestRunner::class)
class MaskStyleInfoTest {

    @Test fun `every style has a label`() {
        MaskStyle.entries.forEach { assertThat(MaskStyleInfo.label(it)).isNotEmpty() }
    }

    @Test fun `solid pixelate emoji and erase are irreversible`() {
        listOf(MaskStyle.SOLID, MaskStyle.PIXELATE, MaskStyle.EMOJI, MaskStyle.ERASE).forEach {
            assertThat(MaskStyleInfo.safety(it)).isEqualTo(MaskSafety.IRREVERSIBLE)
        }
    }

    @Test fun `blur is cosmetic and says so`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.BLUR)).isEqualTo(MaskSafety.COSMETIC)
        assertThat(MaskStyleInfo.note(MaskStyle.BLUR)).contains("非安全")
    }

    @Test fun `marker is annotation only and says so`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.MARKER)).isEqualTo(MaskSafety.ANNOTATION_ONLY)
        assertThat(MaskStyleInfo.note(MaskStyle.MARKER)).contains("不遮蔽")
    }

    @Test fun `irreversible styles carry no warning note`() {
        assertThat(MaskStyleInfo.note(MaskStyle.SOLID)).isNull()
    }

    @Test fun `the registry implements every style`() {
        assertThat(RendererRegistry.default().implemented()).containsExactlyElementsIn(MaskStyle.entries)
    }
}
