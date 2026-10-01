package com.youma.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.SensitiveKind
import com.youma.app.render.SolidRenderer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class MaskedLabelDrawTest {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, 500, false)
        textAlign = Paint.Align.CENTER
    }

    private fun render(block: RectF, kind: SensitiveKind = SensitiveKind.IBAN): Bitmap {
        val bmp = Bitmap.createBitmap(300, 120, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val canvas = Canvas(bmp)
        val item = MaskItem("x", Quad.fromRect(block), kind, DetectorSource.RULE, MaskState.MASKED)
        SolidRenderer().render(canvas, bmp, item.quad, MaskOptions())
        drawMaskedLabel(canvas, item, 1f, paint, MaskedLabel.inkFor(MaskOptions.SKY_BLUE), minPx = 10f, maxPx = 40f)
        return bmp
    }

    private fun Bitmap.count(region: RectF, inside: Boolean, test: (Int) -> Boolean): Int {
        var n = 0
        for (x in 0 until width) for (y in 0 until height) {
            if (region.contains(x + 0.5f, y + 0.5f) == inside && test(getPixel(x, y))) n++
        }
        return n
    }

    private fun isInk(c: Int) = Color.blue(c) < 160 && Color.red(c) < 100   // 深蓝字，与天蓝底和白底都分得开

    @Test
    fun `the kind is written inside the block and nothing leaks outside it`() {
        val block = RectF(20f, 30f, 280f, 90f)
        val bmp = render(block)
        assertThat(bmp.count(block, inside = true, test = ::isInk)).isGreaterThan(30)
        assertThat(bmp.count(block, inside = false) { it != Color.WHITE }).isEqualTo(0)
    }

    @Test
    fun `a block too small to read in stays a plain block`() {
        val block = RectF(20f, 30f, 280f, 40f)                // 10px 高：放不下 10px 以上的字
        val bmp = render(block)
        assertThat(bmp.count(block, inside = true, test = ::isInk)).isEqualTo(0)
    }
}
