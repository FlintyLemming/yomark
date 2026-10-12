package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkerRendererTest {

    private fun white(size: Int = 200) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test fun `marker style is MARKER`() {
        assertThat(MarkerRenderer().style).isEqualTo(MaskStyle.MARKER)
    }

    @Test fun `marker tints the region without hiding it`() {
        val bmp = white()
        MarkerRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        val c = bmp.getPixel(100, 100)
        assertThat(c).isNotEqualTo(Color.WHITE)          // 确实上了色
        assertThat(Color.red(c)).isGreaterThan(120)      // 但底下还看得见：不是不透明黑
        assertThat(Color.green(c)).isGreaterThan(120)
    }

    @Test fun `marker respects the configured colour`() {
        val bmp = white()
        MarkerRenderer().render(
            Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)),
            MaskOptions(markerColor = 0x9900FF00.toInt()),
        )
        val c = bmp.getPixel(100, 100)
        assertThat(Color.green(c)).isGreaterThan(Color.red(c))
    }

    @Test fun `marker leaves the outside untouched`() {
        val bmp = white()
        MarkerRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        assertThat(bmp.getPixel(10, 10)).isEqualTo(Color.WHITE)
    }

    @Test fun `marker draws with the chosen opacity`() {
        fun redAt(alpha: Float): Int {
            val bmp = white()
            val color = MaskOptions.withAlpha(Color.BLUE, alpha)
            MarkerRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions(markerColor = color))
            return Color.red(bmp.getPixel(100, 100))
        }
        // 蓝色越浓，白底上剩下的红越少
        assertThat(redAt(0.7f)).isLessThan(redAt(0.2f))
    }

    @Test fun `a tiny region does not crash the renderer`() {
        val bmp = white()
        val tiny = Quad.fromRect(RectF(0f, 0f, 2f, 2f))
        MarkerRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
    }
}
