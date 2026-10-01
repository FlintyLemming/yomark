package com.youma.app.ui.canvas

import android.graphics.Color
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskedLabelTest {

    private fun item(
        kind: SensitiveKind = SensitiveKind.PHONE,
        source: DetectorSource = DetectorSource.RULE,
        state: MaskState = MaskState.MASKED,
    ) = MaskItem("x", Quad.fromRect(RectF(0f, 0f, 100f, 20f)), kind, source, state)

    // 「电话」两个字：1px 字号下约 2px 宽
    private val twoChars = 2f

    @Test
    fun `a roomy block gets the largest size`() {
        assertThat(MaskedLabel.fitTextSize(400f, 80f, twoChars, minPx = 20f, maxPx = 36f)).isEqualTo(36f)
    }

    @Test
    fun `the label never takes more than most of the block height`() {
        val size = MaskedLabel.fitTextSize(400f, 30f, twoChars, minPx = 20f, maxPx = 36f)!!
        assertThat(size).isWithin(0.01f).of(30f * MaskedLabel.HEIGHT_RATIO)
    }

    @Test
    fun `a narrow block shrinks the label so text plus margins still fit`() {
        val size = MaskedLabel.fitTextSize(60f, 80f, twoChars, minPx = 10f, maxPx = 36f)!!
        assertThat(size * twoChars + 2 * size * MaskedLabel.PAD_RATIO).isAtMost(60.01f)
    }

    @Test
    fun `a block too small to read the label in gets no label`() {
        // 适配缩放下的小块：写不下就不写，放大了自然出现
        assertThat(MaskedLabel.fitTextSize(400f, 18f, twoChars, minPx = 20f, maxPx = 36f)).isNull()
        assertThat(MaskedLabel.fitTextSize(30f, 80f, twoChars, minPx = 20f, maxPx = 36f)).isNull()
        assertThat(MaskedLabel.fitTextSize(400f, 80f, 0f, minPx = 20f, maxPx = 36f)).isNull()
    }

    @Test
    fun `only detected items on solid blocks are labelled`() {
        assertThat(MaskedLabel.applies(item(), MaskStyle.SOLID)).isTrue()
        // 圈出项已经有自己的琥珀色标签
        assertThat(MaskedLabel.applies(item(state = MaskState.OUTLINED), MaskStyle.SOLID)).isFalse()
        // 手动框是用户自己画的
        assertThat(MaskedLabel.applies(item(SensitiveKind.MANUAL, DetectorSource.MANUAL), MaskStyle.SOLID)).isFalse()
        // 其余样式的预览就是用户要看的效果，不往上写字
        MaskStyle.entries.filter { it != MaskStyle.SOLID }.forEach {
            assertThat(MaskedLabel.applies(item(), it)).isFalse()
        }
    }

    @Test
    fun `the label text uses the same wording as the outline tags`() {
        assertThat(MaskedLabel.text(item(SensitiveKind.PERSON_NAME))).isEqualTo("人名")
        assertThat(MaskedLabel.text(item(SensitiveKind.PERSON_NAME, DetectorSource.LLM))).isEqualTo("人名 · AI")
    }

    @Test
    fun `ink contrasts with the block it sits on`() {
        assertThat(MaskedLabel.inkFor(MaskOptions.SKY_BLUE)).isEqualTo(MaskedLabel.DARK_INK)
        assertThat(MaskedLabel.inkFor(Color.BLACK)).isEqualTo(MaskedLabel.LIGHT_INK)
        assertThat(MaskedLabel.inkFor(Color.WHITE)).isEqualTo(MaskedLabel.DARK_INK)
    }
}
