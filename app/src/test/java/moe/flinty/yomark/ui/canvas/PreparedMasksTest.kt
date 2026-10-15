package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.render.BlurRenderer
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.PixelMaskRenderer
import moe.flinty.yomark.render.PixelateRenderer
import moe.flinty.yomark.render.PreparedMask
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.render.SolidRenderer
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 缩放、平移、点选时，没压着别的码的模糊、马赛克贴留着的那张，不再逐块重算；贴出来和逐块现算、和导出一模一样。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class PreparedMasksTest {

    /** 每算一次记一笔：看重画时有没有重算。 */
    private class Counting(private val inner: PixelMaskRenderer) : PixelMaskRenderer {
        var prepared = 0
        override val style get() = inner.style
        override fun prepare(source: Bitmap, quad: Quad, options: MaskOptions): PreparedMask? {
            prepared++
            return inner.prepare(source, quad, options)
        }
    }

    private val blur = Counting(BlurRenderer())
    private val mosaic = Counting(PixelateRenderer())
    private val registry = RendererRegistry(listOf(SolidRenderer(), blur, mosaic))

    /** 黑白竖条加一道红线：糊得不一样、颗粒不一样都看得出来。分析图是原图的一半，渲染器的常数要折算。 */
    private val image = SourceImage(
        Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888).also { bmp ->
            bmp.eraseColor(Color.WHITE)
            val canvas = Canvas(bmp)
            val black = Paint().apply { color = Color.BLACK }
            for (x in 0 until 300 step 14) canvas.drawRect(x.toFloat(), 0f, x + 5f, 300f, black)
            canvas.drawRect(0f, 140f, 300f, 150f, Paint().apply { color = Color.RED })
        },
        0.5f, 600, 600, "image/png",
    )

    private fun item(id: String, rect: RectF, style: MaskStyle) = MaskItem(
        id, Quad.fromRect(rect), SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskLook(style),
    )

    // 互不相压：都读原图，不走合成图
    private val a = item("a", RectF(20f, 20f, 280f, 90f), MaskStyle.BLUR)
    private val b = item("b", RectF(20f, 120f, 280f, 170f), MaskStyle.PIXELATE)
    private val c = item("c", RectF(20.5f, 200.3f, 150f, 260f), MaskStyle.BLUR)   // 不落在整像素上
    private val plan = MaskPlan(listOf(a, b, c))

    private fun preview(
        plan: MaskPlan,
        prepared: PreparedMasks,
        scale: Float = 1f,
        toggle: ToggleFrame? = null,
    ): Bitmap {
        val out = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
        drawScene(Canvas(out), image, plan, registry, scale, 1f, null, ScanPaints(), toggle = toggle, prepared = prepared)
        return out
    }

    /** 导出的画法：同一张位图上一块接一块地画。 */
    private fun exported(plan: MaskPlan): Bitmap {
        val out = image.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val renderers = RendererRegistry.default()
        MaskOrder.forDrawing(plan.items).forEach {
            renderers[it.look.style].render(canvas, out, it.quad, it.look.options.copy(renderScale = image.scale))
        }
        return out
    }

    private fun differing(a: Bitmap, b: Bitmap): Int {
        var n = 0
        for (x in 0 until a.width) for (y in 0 until a.height) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    @Test
    fun `masks drawn from the cache match the export pixel for pixel`() {
        val prepared = PreparedMasks()
        val expected = exported(plan)
        assertThat(MaskComposite().of(image, MaskOrder.forDrawing(plan.items), registry)).isNull()
        assertThat(differing(preview(plan, prepared), expected)).isEqualTo(0)
        // 第二遍全从留着的那几张上贴
        assertThat(differing(preview(plan, prepared, scale = 2f), expected)).isEqualTo(0)
    }

    @Test
    fun `zooming and panning redraw without recomputing`() {
        val prepared = PreparedMasks()
        listOf(1f, 1.5f, 2f, 3f, 1f).forEach { preview(plan, prepared, it) }
        assertThat(blur.prepared).isEqualTo(2)
        assertThat(mosaic.prepared).isEqualTo(1)
    }

    @Test
    fun `dragging a box recomputes only that box, and it shows where it is now`() {
        val prepared = PreparedMasks()
        preview(plan, prepared)
        val moved = MaskPlan(listOf(a, b, c.copy(quad = Quad.fromRect(RectF(60f, 210f, 200f, 280f)))))
        val shown = preview(moved, prepared)
        assertThat(blur.prepared).isEqualTo(3)
        assertThat(mosaic.prepared).isEqualTo(1)
        assertThat(differing(shown, exported(moved))).isEqualTo(0)
    }

    @Test
    fun `masks taken off are let go`() {
        val prepared = PreparedMasks()
        preview(plan, prepared)
        preview(MaskPlan(listOf(a, b)), prepared)
        preview(plan, prepared)
        // c 拿掉时扔了，再回来重算一次
        assertThat(blur.prepared).isEqualTo(3)
    }

    @Test
    fun `a mask peeling off keeps its prepared look until it is gone`() {
        val prepared = PreparedMasks()
        preview(plan, prepared)
        val rest = MaskPlan(listOf(a, b))
        listOf(0.2f, 0.5f, 0.8f).forEach { preview(rest, prepared, toggle = ToggleFrame(emptyMap(), listOf(c to it))) }
        assertThat(blur.prepared).isEqualTo(2)
    }
}
