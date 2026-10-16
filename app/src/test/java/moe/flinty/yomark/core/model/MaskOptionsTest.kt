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

    /** 画笔从选中的框那儿接改动：只接那一种样式的几项，框里别的样式的老参数不能顶掉画笔里调好的。 */
    @Test fun `taking one style's params from other options leaves the rest alone`() {
        val brush = MaskOptions(solidColor = 0xFF000000.toInt(), pixelBlockDivisor = 4, penColor = 0xFF1E88E5.toInt())
        val box = MaskOptions(solidColor = 0xFFE53935.toInt(), solidNear = true, pixelBlockDivisor = 7)
        val took = brush.withParamsOf(MaskStyle.SOLID, box)
        assertThat(took.solidColor).isEqualTo(0xFFE53935.toInt())
        assertThat(took.solidNear).isTrue()
        assertThat(took.pixelBlockDivisor).isEqualTo(4)
        assertThat(took.penColor).isEqualTo(0xFF1E88E5.toInt())
        assertThat(brush.withParamsOf(MaskStyle.PEN, box).penColor).isEqualTo(MaskOptions.DEFAULT_PEN_COLOR)
        MaskStyle.entries.forEach { assertThat(brush.withParamsOf(it, brush)).isEqualTo(brush) }
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

    /** 文本：颜色不透明，字号收回范围内；只接文本那几项，恢复默认也只动它们。 */
    @Test fun `text options are kept in range and stay their own`() {
        val wild = MaskOptions(textColor = 0x401E88E5, textSize = 9f).normalized()
        assertThat(wild.textColor).isEqualTo(0xFF1E88E5.toInt())
        assertThat(wild.textSize).isEqualTo(MaskOptions.TEXT_SIZE_RANGE.endInclusive)
        assertThat(MaskOptions(textSize = Float.NaN).normalized().textSize).isEqualTo(MaskOptions.DEFAULT_TEXT_SIZE)

        val brush = MaskOptions(penColor = 0xFF000000.toInt())
        val text = MaskOptions(textColor = 0xFF43A047.toInt(), textSize = 0.1f, textBackground = true, penColor = 0xFFFFFFFF.toInt())
        val took = brush.withParamsOf(MaskStyle.TEXT, text)
        assertThat(took.textColor).isEqualTo(0xFF43A047.toInt())
        assertThat(took.textSize).isEqualTo(0.1f)
        assertThat(took.textBackground).isTrue()
        assertThat(took.penColor).isEqualTo(0xFF000000.toInt())
        assertThat(took.resetFor(MaskStyle.TEXT)).isEqualTo(brush)
    }

    /** 马克笔没有箭头：读回来、别处传进来的箭头一律当拉框。画笔的箭头照留。 */
    @Test fun `only the pen keeps an arrow`() {
        val o = MaskOptions(markerShape = StrokeShape.ARROW, penShape = StrokeShape.ARROW).normalized()
        assertThat(o.markerShape).isEqualTo(StrokeShape.BOX)
        assertThat(o.penShape).isEqualTo(StrokeShape.ARROW)
        assertThat(o.shapeFor(MaskStyle.PEN)).isEqualTo(StrokeShape.ARROW)
    }
}
