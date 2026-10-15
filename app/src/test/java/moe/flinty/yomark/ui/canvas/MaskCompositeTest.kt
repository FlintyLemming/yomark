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
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 色块上再拉一个模糊框：预览里模糊盖在色块上面，糊的是色块而不是色块挡住的字，与导出一块接一块地画出来的一样。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class MaskCompositeTest {

    private val registry = RendererRegistry.default()

    /** 白底上一道黑条，当作要遮住的字。 */
    private fun image() = SourceImage(
        Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
            Canvas(it).drawRect(40f, 95f, 160f, 105f, Paint().apply { color = Color.BLACK })
        },
        1f, 200, 200, "image/png",
    )

    private fun manual(id: String, rect: RectF, look: MaskLook = MaskLook(MaskStyle.SOLID)) = MaskItem(
        id, Quad.fromRect(rect), SensitiveKind.MANUAL, DetectorSource.MANUAL, look,
    )

    private val bar = manual("bar", RectF(30f, 90f, 170f, 110f))
    // 最弱一档：糊得越狠，细细一条色块越淡，看不出糊开的是谁
    private val blur = manual(
        "blur", RectF(20f, 20f, 180f, 180f),
        MaskLook(MaskStyle.BLUR, MaskOptions(blurRadiusRatio = MaskOptions.WEAKEST_BLUR_RATIO)),
    )

    private fun preview(plan: MaskPlan, source: SourceImage = image()): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        drawScene(Canvas(out), source, plan, registry, 1f, 1f, null, ScanPaints())
        return out
    }

    /** 导出的画法：同一张位图上一块接一块地画。 */
    private fun exported(plan: MaskPlan, source: SourceImage = image()): Bitmap {
        val out = source.bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        MaskOrder.forDrawing(plan.items).forEach {
            val options = MaskOrder.pinNearColor(it.look.options, source.bitmap, it.quad)
            registry[it.look.style].render(canvas, out, it.quad, options)
        }
        return out
    }

    @Test
    fun `a blur over a solid block blurs the block, not the text under it`() {
        val c = preview(MaskPlan(listOf(bar, blur))).getPixel(100, 100)
        // 糊开的是天蓝色块：偏蓝；读原图的话糊开的是黑条，是灰的
        assertThat(Color.blue(c) - Color.red(c)).isGreaterThan(30)
    }

    @Test
    fun `preview matches the export inside the masks`() {
        val plan = MaskPlan(listOf(bar, blur))
        val shown = preview(plan)
        val expected = exported(plan)
        for (x in 25 until 175 step 5) for (y in 25 until 175 step 5) {
            assertThat(shown.getPixel(x, y)).isEqualTo(expected.getPixel(x, y))
        }
    }

    @Test
    fun `while results land, blur masks wait for the composite made in the background`() {
        val found = MaskItem(
            "found", Quad.fromRect(RectF(30f, 90f, 170f, 110f)), SensitiveKind.PHONE, DetectorSource.RULE,
            MaskLook(MaskStyle.BLUR, MaskOptions(blurRadiusRatio = MaskOptions.WEAKEST_BLUR_RATIO)),
        )
        val plan = MaskPlan(listOf(found))
        val source = image()
        // 落码那一遍的最后一帧：光和暗幕都已经走出图外，块已经盖实
        val since = ScanEffect.settleMs(3_000L) - 1L
        fun frame(masks: ComposedMasks?): Bitmap {
            val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            drawScene(
                Canvas(out), source, plan, registry, 1f, 1f, ScanTime(3_000L + since, since), ScanPaints(),
                animationMasks = masks,
            )
            return out
        }
        // 还没合成好：这一块先不落，底下的黑条还在
        assertThat(frame(null).getPixel(100, 100)).isEqualTo(Color.BLACK)
        val sorted = MaskOrder.forDrawing(plan.items)
        val shown = frame(ComposedMasks.compose(source, sorted, RendererRegistry.default()))
        assertThat(shown.getPixel(100, 100)).isNotEqualTo(Color.BLACK)
        assertThat(shown.getPixel(100, 100)).isEqualTo(exported(plan, source).getPixel(100, 100))
    }

    private fun differing(a: Bitmap, b: Bitmap, top: Int = 0): Int {
        var n = 0
        for (x in 0 until a.width) for (y in top until a.height) if (a.getPixel(x, y) != b.getPixel(x, y)) n++
        return n
    }

    @Test
    fun `stacked masks of every kind match the export pixel for pixel`() {
        val plan = MaskPlan(listOf(
            bar,
            manual("mosaic", RectF(60f, 60f, 150f, 130f), MaskLook(MaskStyle.PIXELATE)),
            manual("marker", RectF(10f, 120f, 120f, 190f), MaskLook(MaskStyle.MARKER)),
            blur,
            manual("pen", RectF(100f, 10f, 190f, 80f), MaskLook(MaskStyle.PEN)),
            manual("top", RectF(90f, 85f, 195f, 150f), MaskLook(MaskStyle.BLUR)),
        ))
        assertThat(differing(preview(plan), exported(plan))).isEqualTo(0)
    }

    @Test
    fun `a blur reads a marker whose wobbly edge pokes past its box`() {
        // 马克笔的框底边在 120，边抖出去近 3 像素；模糊的框从 122 起，框与框不挨着，读的那几行里已经有马克笔
        val marker = manual("marker", RectF(20f, 20f, 180f, 120f), MaskLook(MaskStyle.MARKER))
        val below = manual(
            "below", RectF(20f, 122f, 180f, 180f),
            MaskLook(MaskStyle.BLUR, MaskOptions(blurRadiusRatio = MaskOptions.WEAKEST_BLUR_RATIO)),
        )
        val plan = MaskPlan(listOf(marker, below))
        val expected = exported(plan)
        // 真读到了：马克笔够不着的那几行里，照原图糊出来的也不一样
        assertThat(differing(exported(MaskPlan(listOf(below))), expected, top = 126)).isGreaterThan(0)
        assertThat(differing(preview(plan), expected)).isEqualTo(0)
    }
}
