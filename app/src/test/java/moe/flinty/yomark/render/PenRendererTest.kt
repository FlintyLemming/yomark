package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** 画笔：手画的一笔照着手指走过的地方画，拉的框只描一圈线；马克笔手画是一道半透明的粗线。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class PenRendererTest {

    private fun white() = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    /** 笔宽按图的短边算：200 × 0.02 = 4 像素宽，半宽 2。 */
    private val pen = MaskOptions(penWidth = 0.02f)

    @Test fun `a freehand pen stroke follows the finger and leaves the rest alone`() {
        val bmp = white()
        val half = pen.halfWidth(MaskStyle.PEN, 200f)
        val (quad, ink) = Ink.fit(listOf(PointF(20f, 100f), PointF(100f, 100f), PointF(180f, 100f)), half)
        PenRenderer().render(Canvas(bmp), bmp, quad, pen, ink)
        assertThat(bmp.getPixel(60, 100)).isEqualTo(MaskOptions.DEFAULT_PEN_COLOR)
        assertThat(bmp.getPixel(140, 100)).isEqualTo(MaskOptions.DEFAULT_PEN_COLOR)
        assertThat(bmp.getPixel(60, 110)).isEqualTo(Color.WHITE)
    }

    @Test fun `a pen box is an outline inside the box, not a fill`() {
        val bmp = white()
        PenRenderer().render(Canvas(bmp), bmp, Quad.fromRect(RectF(40f, 40f, 160f, 160f)), pen)
        assertThat(bmp.getPixel(42, 100)).isEqualTo(MaskOptions.DEFAULT_PEN_COLOR)
        assertThat(bmp.getPixel(100, 100)).isEqualTo(Color.WHITE)
        assertThat(bmp.getPixel(37, 100)).isEqualTo(Color.WHITE)
    }

    /** 半透明的一笔自己和自己交叉，交叉处不更深：整条线一次画完。 */
    @Test fun `a freehand marker stroke is translucent and even where it crosses itself`() {
        val bmp = white()
        val options = MaskOptions(markerWidth = 0.05f)
        val half = options.halfWidth(MaskStyle.MARKER, 200f)
        val points = listOf(PointF(20f, 20f), PointF(180f, 180f), PointF(180f, 20f), PointF(20f, 180f))
        val (quad, ink) = Ink.fit(points, half)
        MarkerRenderer().render(Canvas(bmp), bmp, quad, options, ink)
        val crossing = bmp.getPixel(100, 100)
        val plain = bmp.getPixel(60, 60)
        assertThat(plain).isNotEqualTo(Color.WHITE)
        assertThat(Color.red(plain)).isGreaterThan(200)          // 透得过去：白底上还是很亮
        assertThat(crossing).isEqualTo(plain)
    }

    /** 拖大了框，这一笔跟着拉伸：还从左边画到右边。 */
    @Test fun `stretching the box stretches the stroke`() {
        val half = pen.halfWidth(MaskStyle.PEN, 200f)
        val (quad, ink) = Ink.fit(listOf(PointF(50f, 50f), PointF(100f, 50f)), half)
        val wider = Quad.fromRect(RectF(quad.bounds().left, quad.bounds().top, quad.bounds().right + 50f, quad.bounds().bottom))
        val points = Ink.toImage(ink, wider, half)
        assertThat(points.first().x).isWithin(0.01f).of(50f)
        assertThat(points.last().x).isWithin(0.01f).of(150f)
        assertThat(points.first().y).isWithin(0.01f).of(50f)
    }
}
