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
import moe.flinty.yomark.core.model.MaskState
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
        id, Quad.fromRect(rect), SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED, look,
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
    fun `nothing is composited when no blur or mosaic lies over another mask`() {
        val composite = MaskComposite()
        val source = image()
        // 色块在模糊上面：模糊读的是原图，照常一块块地画
        assertThat(composite.of(source, listOf(blur, bar), registry)).isNull()
        assertThat(composite.of(source, listOf(bar, blur), registry)).isNotNull()
    }
}
