package com.youma.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskStyle
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

    @Test fun `a tiny region does not crash either renderer`() {
        val bmp = white()
        val tiny = Quad.fromRect(RectF(0f, 0f, 2f, 2f))
        MarkerRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
        EmojiRenderer().render(Canvas(bmp), bmp, tiny, MaskOptions())
    }
}
