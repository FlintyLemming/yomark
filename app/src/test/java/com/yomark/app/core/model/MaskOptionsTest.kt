package com.yomark.app.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 样式参数的范围是安全底线（MaskOptions.normalized）：色块与表情底色永远不透明，
 * 马克笔永远透得过去，马赛克不比出厂更细，模糊不比出厂更弱。
 */
class MaskOptionsTest {

    @Test fun `factory values are already in range`() {
        assertThat(MaskOptions().normalized()).isEqualTo(MaskOptions())
    }

    @Test fun `a translucent solid color is made opaque`() {
        val o = MaskOptions(solidColor = 0x3387CEEB, emojiBackground = 0x00112233).normalized()
        assertThat(o.solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(o.emojiBackground).isEqualTo(0xFF112233.toInt())
    }

    @Test fun `the marker can neither turn opaque nor vanish`() {
        val opaque = MaskOptions(markerColor = 0xFFFFEB3B.toInt()).normalized()
        assertThat(opaque.markerAlpha).isWithin(0.01f).of(MaskOptions.MARKER_ALPHA_RANGE.endInclusive)
        assertThat(opaque.markerColor and 0xFFFFFF).isEqualTo(0xFFEB3B)        // 颜色不变，只收透明度

        val clear = MaskOptions(markerColor = 0x01FFEB3B).normalized()
        assertThat(clear.markerAlpha).isWithin(0.01f).of(MaskOptions.MARKER_ALPHA_RANGE.start)
    }

    @Test fun `mosaic never gets finer than the factory setting`() {
        assertThat(MaskOptions(pixelBlockDivisor = 40).normalized().pixelBlockDivisor)
            .isEqualTo(MaskOptions.FINEST_PIXEL_DIVISOR)
        assertThat(MaskOptions(pixelBlockDivisor = 1).normalized().pixelBlockDivisor)
            .isEqualTo(MaskOptions.PIXEL_DIVISOR_RANGE.first)
    }

    @Test fun `blur never gets weaker than the factory setting`() {
        assertThat(MaskOptions(blurRadiusRatio = 0.01f).normalized().blurRadiusRatio)
            .isEqualTo(MaskOptions.DEFAULT_BLUR_RATIO)
        assertThat(MaskOptions(blurRadiusRatio = Float.NaN).normalized().blurRadiusRatio)
            .isEqualTo(MaskOptions.DEFAULT_BLUR_RATIO)
    }

    @Test fun `a blank emoji falls back to the default one`() {
        assertThat(MaskOptions(emoji = " ").normalized().emoji).isEqualTo(MaskOptions.DEFAULT_EMOJI)
    }

    @Test fun `reset puts back only the params of that style`() {
        val tuned = MaskOptions(
            solidColor = 0xFF000000.toInt(),
            pixelBlockDivisor = 4,
            blurRadiusRatio = 0.2f,
            emoji = "🐱",
            emojiTiled = true,
        )
        val reset = tuned.resetFor(MaskStyle.EMOJI)
        assertThat(reset.emoji).isEqualTo(MaskOptions.DEFAULT_EMOJI)
        assertThat(reset.emojiTiled).isFalse()
        assertThat(reset.solidColor).isEqualTo(0xFF000000.toInt())    // 别的样式调好的不动
        assertThat(reset.pixelBlockDivisor).isEqualTo(4)
        assertThat(tuned.resetFor(MaskStyle.SOLID).solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(tuned.resetFor(MaskStyle.SOLID).emoji).isEqualTo("🐱")
    }

    @Test fun `erase shares the solid color, the color it falls back to`() {
        val tuned = MaskOptions(solidColor = 0xFF000000.toInt())
        assertThat(tuned.isDefaultFor(MaskStyle.ERASE)).isFalse()
        assertThat(tuned.resetFor(MaskStyle.ERASE).solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(tuned.isDefaultFor(MaskStyle.BLUR)).isTrue()
    }

    @Test fun `withAlpha keeps the color and replaces only the alpha`() {
        val c = MaskOptions.withAlpha(0x12345678, 0.5f)
        assertThat(c ushr 24).isEqualTo(128)
        assertThat(c and 0xFFFFFF).isEqualTo(0x345678)
    }
}
