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
class MarkerAndEmojiRendererTest {

    private fun white(size: Int = 200) =
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    // ---------- 马克笔 ----------
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

    // ---------- Emoji ----------
    @Test fun `emoji style is EMOJI`() {
        assertThat(EmojiRenderer().style).isEqualTo(MaskStyle.EMOJI)
    }

    @Test fun `emoji fills the region opaquely first so nothing shows through`() {
        val bmp = white()
        EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        // 角落必须被底色盖住 —— Emoji 是圆的，光靠字形盖不满矩形
        assertThat(bmp.getPixel(45, 45)).isNotEqualTo(Color.WHITE)
    }

    @Test fun `emoji draws something in the middle of the region`() {
        val bmp = white()
        EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        var varied = false
        val base = bmp.getPixel(45, 45)
        for (x in 80..120 step 4) for (y in 80..120 step 4) {
            if (bmp.getPixel(x, y) != base) varied = true
        }
        assertThat(varied).isTrue()
    }

    @Test fun `emoji leaves the outside untouched`() {
        val bmp = white()
        EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), MaskOptions())
        assertThat(bmp.getPixel(10, 10)).isEqualTo(Color.WHITE)
    }

    @Test fun `emoji paints the chosen background, always opaque`() {
        val bmp = white()
        EmojiRenderer().render(
            Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)),
            MaskOptions(emojiBackground = 0x00FF0000),            // 透明度不认：底色是盖住内容的那一层
        )
        assertThat(bmp.getPixel(45, 45)).isEqualTo(Color.RED)
    }

    /** 长条形的框上，单个表情只在正中；排满时两头也有。 */
    @Test fun `tiled emoji fills the long edge instead of one glyph in the middle`() {
        fun inkNearLeftEnd(tiled: Boolean): Boolean {
            val bmp = white(240)
            val opts = MaskOptions(emojiBackground = Color.WHITE, emojiTiled = tiled)
            EmojiRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 100f, 230f, 140f)), opts)
            for (x in 12..50 step 2) for (y in 102..138 step 2) {
                if (bmp.getPixel(x, y) != Color.WHITE) return true
            }
            return false
        }
        assertThat(inkNearLeftEnd(tiled = false)).isFalse()
        assertThat(inkNearLeftEnd(tiled = true)).isTrue()
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

    @Test fun `a tiny region does not crash either renderer`() {
        val bmp = white()
        val tiny = Quad.fromRect(RectF(0f, 0f, 2f, 2f))
        MarkerRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
        EmojiRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
    }
}
