package moe.flinty.yomark.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 样式参数的范围是安全底线（MaskOptions.normalized）：色块永远不透明，
 * 马克笔永远透得过去，马赛克不比出厂更细，模糊不比出厂更弱。
 */
class MaskOptionsTest {

    @Test fun `factory values are already in range`() {
        assertThat(MaskOptions().normalized()).isEqualTo(MaskOptions())
    }

    @Test fun `a translucent solid color is made opaque`() {
        val o = MaskOptions(solidColor = 0x3387CEEB).normalized()
        assertThat(o.solidColor).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test fun `the marker can neither turn opaque nor vanish`() {
        val opaque = MaskOptions(markerColor = 0xFFFFEB3B.toInt()).normalized()
        assertThat(opaque.markerAlpha).isWithin(0.01f).of(MaskOptions.MARKER_ALPHA_RANGE.endInclusive)
        assertThat(opaque.markerColor and 0xFFFFFF).isEqualTo(0xFFEB3B)        // 颜色不变，只收透明度

        val clear = MaskOptions(markerColor = 0x01FFEB3B).normalized()
        assertThat(clear.markerAlpha).isWithin(0.01f).of(MaskOptions.MARKER_ALPHA_RANGE.start)
    }

    @Test fun `mosaic never gets finer than the finest step`() {
        assertThat(MaskOptions(pixelBlockDivisor = 40).normalized().pixelBlockDivisor)
            .isEqualTo(MaskOptions.FINEST_PIXEL_DIVISOR)
        assertThat(MaskOptions(pixelBlockDivisor = 1).normalized().pixelBlockDivisor)
            .isEqualTo(MaskOptions.PIXEL_DIVISOR_RANGE.first)
    }

    @Test fun `blur never gets weaker than the weakest step`() {
        assertThat(MaskOptions(blurRadiusRatio = 0.01f).normalized().blurRadiusRatio)
            .isEqualTo(MaskOptions.WEAKEST_BLUR_RATIO)
        assertThat(MaskOptions(blurRadiusRatio = Float.NaN).normalized().blurRadiusRatio)
            .isEqualTo(MaskOptions.DEFAULT_BLUR_RATIO)
    }

    @Test fun `reset puts back only the params of that style`() {
        val tuned = MaskOptions(
            solidColor = 0xFF000000.toInt(),
            pixelBlockDivisor = 4,
            blurRadiusRatio = 0.3f,
        )
        val reset = tuned.resetFor(MaskStyle.BLUR)
        assertThat(reset.blurRadiusRatio).isEqualTo(MaskOptions.DEFAULT_BLUR_RATIO)
        assertThat(reset.solidColor).isEqualTo(0xFF000000.toInt())    // 别的样式调好的不动
        assertThat(reset.pixelBlockDivisor).isEqualTo(4)
        assertThat(tuned.resetFor(MaskStyle.SOLID).solidColor).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(tuned.resetFor(MaskStyle.SOLID).blurRadiusRatio).isEqualTo(0.3f)
    }

    /** 出厂两种都是中间那档，不是最轻的。 */
    @Test fun `mosaic and blur default to the middle step`() {
        val factory = MaskOptions()
        assertThat(factory.pixelBlockDivisor).isLessThan(MaskOptions.FINEST_PIXEL_DIVISOR)
        assertThat(factory.pixelBlockDivisor).isGreaterThan(MaskOptions.PIXEL_DIVISOR_RANGE.first)
        assertThat(factory.blurRadiusRatio).isGreaterThan(MaskOptions.BLUR_RATIO_RANGE.start)
        assertThat(factory.blurRadiusRatio).isLessThan(MaskOptions.BLUR_RATIO_RANGE.endInclusive)
        assertThat(factory.normalized()).isEqualTo(factory)
    }

    /** 近似色是色块的一项：色块的「恢复默认」把它关掉，固定的颜色也回到天蓝。 */
    @Test fun `near colour belongs to the solid style`() {
        val near = MaskOptions(solidNear = true)
        assertThat(near.isDefaultFor(MaskStyle.SOLID)).isFalse()
        assertThat(near.isDefaultFor(MaskStyle.BLUR)).isTrue()
        assertThat(near.resetFor(MaskStyle.SOLID)).isEqualTo(MaskOptions())
        assertThat(near.resetFor(MaskStyle.MARKER).solidNear).isTrue()
    }

    @Test fun `withAlpha keeps the color and replaces only the alpha`() {
        val c = MaskOptions.withAlpha(0x12345678, 0.5f)
        assertThat(c ushr 24).isEqualTo(128)
        assertThat(c and 0xFFFFFF).isEqualTo(0x345678)
    }
}
