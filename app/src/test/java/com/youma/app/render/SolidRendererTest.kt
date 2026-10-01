package com.youma.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
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
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class SolidRendererTest {

    private fun whiteBitmap(w: Int = 100, h: Int = 100) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test
    fun `solid fill covers the quad completely and opaquely`() {
        val bmp = whiteBitmap()
        val canvas = Canvas(bmp)
        SolidRenderer().render(canvas, bmp, Quad.fromRect(RectF(20f, 20f, 60f, 60f)), MaskOptions())

        assertThat(bmp.getPixel(40, 40)).isEqualTo(MaskOptions.SKY_BLUE)   // 出厂是天蓝色块
        assertThat(Color.alpha(bmp.getPixel(40, 40))).isEqualTo(255)
    }

    @Test
    fun `pixels outside the quad are untouched`() {
        val bmp = whiteBitmap()
        SolidRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(20f, 20f, 60f, 60f)), MaskOptions())
        assertThat(bmp.getPixel(5, 5)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(90, 90)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `a rotated quad does not fill its axis-aligned corners`() {
        val bmp = whiteBitmap()
        val diamond = Quad(PointF(50f, 10f), PointF(90f, 50f), PointF(50f, 90f), PointF(10f, 50f))
        SolidRenderer().render(Canvas(bmp), bmp, diamond, MaskOptions())

        assertThat(bmp.getPixel(50, 50)).isEqualTo(MaskOptions.SKY_BLUE)   // 菱形中心
        assertThat(bmp.getPixel(12, 12)).isEqualTo(Color.WHITE)   // 外接框的角，不该被填
    }

    @Test
    fun `custom solid color is honored`() {
        val bmp = whiteBitmap()
        SolidRenderer().render(
            Canvas(bmp), bmp, Quad.fromRect(RectF(10f, 10f, 40f, 40f)),
            MaskOptions(solidColor = Color.RED),
        )
        assertThat(bmp.getPixel(25, 25)).isEqualTo(Color.RED)
    }

    @Test
    fun `registry returns the solid renderer for SOLID`() {
        assertThat(RendererRegistry.default()[MaskStyle.SOLID]).isInstanceOf(SolidRenderer::class.java)
    }

    @Test
    fun `registry falls back to solid for styles not yet implemented`() {
        // M1 只有实色块；后续里程碑补齐后这条会自然变成「返回对应渲染器」
        assertThat(RendererRegistry.default()[MaskStyle.EMOJI]).isNotNull()
    }
}
