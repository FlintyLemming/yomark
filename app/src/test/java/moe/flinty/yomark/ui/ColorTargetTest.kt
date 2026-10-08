package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Color
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.ui.components.Hsv
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0
class ColorTargetTest {

    @Test fun `solid and emoji background stay opaque whatever comes in`() {
        val o = MaskOptions()
        assertThat(ColorTarget.SOLID.withColor(o, 0x40FF0000).solidColor).isEqualTo(Color.RED)
        assertThat(ColorTarget.EMOJI_BACKGROUND.withColor(o, 0x0000FF00).emojiBackground).isEqualTo(Color.GREEN)
    }

    @Test fun `the marker keeps its opacity when its color changes`() {
        val o = MaskOptions(markerColor = MaskOptions.withAlpha(Color.YELLOW, 0.3f))
        val next = ColorTarget.MARKER.withColor(o, Color.BLUE)
        assertThat(next.markerColor and 0xFFFFFF).isEqualTo(Color.BLUE and 0xFFFFFF)
        assertThat(next.markerAlpha).isWithin(0.01f).of(0.3f)
        assertThat(ColorTarget.MARKER.colorOf(next)).isEqualTo(Color.BLUE)        // 色板上比的是不透明的颜色
    }

    /** 吸管取 3×3 的中位数：正好点在一个噪点、一道字的边上，取回来的仍是底色。 */
    @Test fun `the eyedropper takes the median around the tap`() {
        val bmp = Bitmap.createBitmap(5, 5, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        bmp.setPixel(2, 2, Color.RED)
        bmp.setPixel(1, 2, Color.RED)
        bmp.setPixel(3, 1, Color.WHITE)
        assertThat(pickColor(bmp, 2, 2)).isEqualTo(Color.BLUE)
    }

    @Test fun `the eyedropper works on the edge of the image`() {
        val bmp = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        assertThat(pickColor(bmp, 0, 0)).isEqualTo(Color.GREEN)
        assertThat(pickColor(bmp, 3, 3)).isEqualTo(Color.GREEN)
    }

    @Test fun `hsv round trips the palette colors exactly`() {
        listOf(
            Color.BLACK, Color.WHITE, Color.RED, Color.GREEN, Color.BLUE,
            MaskOptions.SKY_BLUE, MaskOptions.EMOJI_BACKGROUND, 0xFF123456.toInt(), 0xFFFB8C00.toInt(),
        ).forEach { assertThat(Hsv.of(it).toArgb()).isEqualTo(it) }
    }

    @Test fun `hsv keeps its hue when saturation is zero, the color does not`() {
        val gray = Hsv(h = 200f, s = 0f, v = 0.5f)
        assertThat(Color.red(gray.toArgb())).isEqualTo(Color.blue(gray.toArgb()))
        assertThat(gray.copy(s = 1f).toArgb()).isNotEqualTo(gray.copy(h = 0f, s = 1f).toArgb())
    }
}
