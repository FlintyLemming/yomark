package com.youma.app.render

import com.youma.app.core.model.MaskStyle
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

    @Test fun `solid emoji and erase are irreversible`() {
        listOf(MaskStyle.SOLID, MaskStyle.EMOJI, MaskStyle.ERASE).forEach {
            assertThat(MaskStyleInfo.safety(it)).isEqualTo(MaskSafety.IRREVERSIBLE)
        }
    }

    @Test fun `blur is cosmetic and says so`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.BLUR)).isEqualTo(MaskSafety.COSMETIC)
        assertThat(MaskStyleInfo.note(MaskStyle.BLUR)).contains("不适合遮挡")
    }

    /**
     * 像素化实测会泄漏内容（spec §15.6）：固定块网格下只改一位数字，
     * 40–96px 字号上 10 个数字两两全部可区分，知道字体的攻击者可逐位模板还原。
     * 不存在既不泄漏、又还看得出是马赛克的块尺寸，所以只能如实标注。
     */
    @Test fun `pixelate is cosmetic too and says why`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.PIXELATE)).isEqualTo(MaskSafety.COSMETIC)
        assertThat(MaskStyleInfo.note(MaskStyle.PIXELATE)).contains("不适合遮挡")
    }

    @Test fun `the two cosmetic styles do not share one warning`() {
        assertThat(MaskStyleInfo.note(MaskStyle.PIXELATE))
            .isNotEqualTo(MaskStyleInfo.note(MaskStyle.BLUR))
    }

    @Test fun `marker is annotation only and says so`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.MARKER)).isEqualTo(MaskSafety.ANNOTATION_ONLY)
        assertThat(MaskStyleInfo.note(MaskStyle.MARKER)).contains("盖不住")
    }

    @Test fun `irreversible styles carry no warning note`() {
        assertThat(MaskStyleInfo.note(MaskStyle.SOLID)).isNull()
    }

    @Test fun `the registry implements every style`() {
        assertThat(RendererRegistry.default().implemented()).containsExactlyElementsIn(MaskStyle.entries)
    }
}
