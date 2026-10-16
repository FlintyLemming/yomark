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

    // ---------- 箭头 ----------

    /** 箭头：一条线，箭尖那头一个实心的三角，比线宽得多；尾巴那头只有线，箭尖外面什么也没有。 */
    @Test fun `an arrow is a line with a solid head at the tip`() {
        val bmp = white()
        val half = pen.halfWidth(MaskStyle.PEN, 200f)                // 2 像素
        val (quad, ink) = Ink.fit(listOf(PointF(20f, 100f), PointF(180f, 100f)), Ink.margin(half, arrow = true), arrow = true)
        PenRenderer().render(Canvas(bmp), bmp, quad, pen, ink)
        val color = MaskOptions.DEFAULT_PEN_COLOR
        assertThat(bmp.getPixel(60, 100)).isEqualTo(color)                  // 线
        assertThat(bmp.getPixel(176, 100)).isEqualTo(color)                 // 箭尖里面
        assertThat(bmp.getPixel(168, 105)).isEqualTo(color)                 // 头比线宽：离中线 5 像素还在头里
        assertThat(bmp.getPixel(60, 105)).isEqualTo(Color.WHITE)           // 线那一段只有线宽
        assertThat(bmp.getPixel(186, 100)).isEqualTo(Color.WHITE)           // 箭尖外面
        assertThat(bmp.getPixel(14, 100)).isEqualTo(Color.WHITE)            // 尾巴没有头
    }

    /** 斜着的箭头：头的两个角也落在外接框里，选中框、导出都不会切掉它。 */
    @Test fun `a slanted arrow head stays inside its box`() {
        val bmp = white()
        val half = pen.halfWidth(MaskStyle.PEN, 200f)
        val (quad, ink) = Ink.fit(listOf(PointF(40f, 160f), PointF(160f, 40f)), Ink.margin(half, arrow = true), arrow = true)
        PenRenderer().render(Canvas(bmp), bmp, quad, pen, ink)
        val box = quad.bounds()
        for (y in 0 until 200) for (x in 0 until 200) {
            if (bmp.getPixel(x, y) != Color.WHITE) {
                assertThat(x + 0.5f).isAtLeast(box.left - 1f)
                assertThat(x + 0.5f).isAtMost(box.right + 1f)
                assertThat(y + 0.5f).isAtLeast(box.top - 1f)
                assertThat(y + 0.5f).isAtMost(box.bottom + 1f)
            }
        }
    }

    /** 箭头的框让出的是头的半宽：放回图上还是原来的头尾，拖大了框跟着拉长。 */
    @Test fun `an arrow comes back where it was drawn and stretches with its box`() {
        val half = pen.halfWidth(MaskStyle.PEN, 200f)
        val margin = Ink.margin(half, arrow = true)
        assertThat(margin).isGreaterThan(half)
        val (quad, ink) = Ink.fit(listOf(PointF(30f, 40f), PointF(120f, 90f)), margin, arrow = true)
        assertThat(ink.arrow).isTrue()
        val back = Ink.toImage(ink, quad, margin)
        assertThat(back.first().x).isWithin(0.01f).of(30f)
        assertThat(back.last().y).isWithin(0.01f).of(90f)
        val b = quad.bounds()
        val wider = Quad.fromRect(RectF(b.left, b.top, b.right + 90f, b.bottom))
        assertThat(Ink.toImage(ink, wider, margin).last().x).isWithin(0.01f).of(210f)
    }

    /** 很短的箭头：头跟着缩，不比线还长，看着还是一支箭头。 */
    @Test fun `a short arrow keeps a short head`() {
        val bmp = white()
        val thick = MaskOptions(penWidth = MaskOptions.PEN_WIDTH_RANGE.endInclusive)   // 5 像素宽，头本该 40 像素长
        val half = thick.halfWidth(MaskStyle.PEN, 200f)
        val (quad, ink) = Ink.fit(listOf(PointF(80f, 100f), PointF(110f, 100f)), Ink.margin(half, arrow = true), arrow = true)
        PenRenderer().render(Canvas(bmp), bmp, quad, thick, ink)
        assertThat(bmp.getPixel(84, 100)).isEqualTo(MaskOptions.DEFAULT_PEN_COLOR)   // 尾巴那一截还是线
        assertThat(bmp.getPixel(84, 107)).isEqualTo(Color.WHITE)                     // 头没有伸到尾巴上
    }
}
