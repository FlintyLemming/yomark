package moe.flinty.yomark.render

import moe.flinty.yomark.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// 最后一条会构造全部五个渲染器，它们在构造期就碰 android.graphics.Paint —— 纯 JUnit 下 not mocked。
// 不读像素，所以不需要 NATIVE 图形模式。
@RunWith(RobolectricTestRunner::class)
class MaskStyleInfoTest {

    @Test fun `every style has a label`() {
        MaskStyle.entries.forEach { assertThat(MaskStyleInfo.label(it)).isNotEqualTo(0) }
    }

    @Test fun `solid is irreversible`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.SOLID)).isEqualTo(MaskSafety.IRREVERSIBLE)
    }

    @Test fun `blur is cosmetic`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.BLUR)).isEqualTo(MaskSafety.COSMETIC)
    }

    /**
     * 像素化实测会泄漏内容（spec §15.6）：固定块网格下只改一位数字，
     * 40–96px 字号上 10 个数字两两全部可区分，知道字体的攻击者可逐位模板还原。
     * 不存在既不泄漏、又还看得出是马赛克的块尺寸，所以只能如实标注。
     */
    @Test fun `pixelate is cosmetic too`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.PIXELATE)).isEqualTo(MaskSafety.COSMETIC)
    }

    @Test fun `marker is annotation only`() {
        assertThat(MaskStyleInfo.safety(MaskStyle.MARKER)).isEqualTo(MaskSafety.ANNOTATION_ONLY)
    }

    /** 只做标记的（马克笔、画笔、文本）不拿来打码：识别出来的、点了打码的虚线框都不会用它们。 */
    @Test fun `every style but the annotation ones redacts`() {
        MaskStyle.entries.forEach {
            assertThat(it.redacts).isEqualTo(MaskStyleInfo.safety(it) != MaskSafety.ANNOTATION_ONLY)
        }
        assertThat(MaskStyle.entries.filterNot { it.redacts }).containsExactly(MaskStyle.MARKER, MaskStyle.PEN, MaskStyle.TEXT)
    }

    @Test fun `the style bar offers every style once, the irreversible one first`() {
        assertThat(MaskStyleInfo.ORDER).containsExactlyElementsIn(MaskStyle.entries)
        assertThat(MaskStyleInfo.safety(MaskStyleInfo.ORDER.first())).isEqualTo(MaskSafety.IRREVERSIBLE)
    }

    @Test fun `the registry implements every style`() {
        assertThat(RendererRegistry.default().implemented()).containsExactlyElementsIn(MaskStyle.entries)
    }
}
