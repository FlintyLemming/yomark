package com.youma.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskPlan
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
import com.youma.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 直接画出扫光与落码的某一帧，按像素核对：识别中藏、落码自上而下、手动框始终在、
 * 光只染字不染纸、光不出图像。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class ScanSceneDrawTest {

    // 120 × 1000 的图，缩放 1、密度 0.1：光尾 15、前沿 3.6、渐显区 11（图像像素）
    private val scale = 1f
    private val density = 0.1f
    private val dp = density / scale
    private val trail = ScanEffect.TRAIL_DP * dp
    private val lead = ScanEffect.LEAD_DP * dp
    private val feather = ScanEffect.FEATHER_DP * dp

    private val top = block("top", 100f, DetectorSource.RULE)
    private val manual = block("manual", 450f, DetectorSource.MANUAL)
    private val bottom = block("bottom", 800f, DetectorSource.RULE)
    private val plan = MaskPlan(listOf(top, manual, bottom), MaskStyle.SOLID, MaskOptions())

    private fun block(id: String, y: Float, source: DetectorSource) = MaskItem(
        id, Quad.fromRect(RectF(10f, y, 110f, y + 30f)),
        if (source == DetectorSource.MANUAL) SensitiveKind.MANUAL else SensitiveKind.PHONE,
        source, MaskState.MASKED,
    )

    private fun image(paint: (Bitmap) -> Unit = { it.eraseColor(Color.WHITE) }) =
        SourceImage(Bitmap.createBitmap(120, 1000, Bitmap.Config.ARGB_8888).also(paint), 1f, 120, 1000, "image/png")

    private fun render(time: ScanTime?, source: SourceImage = image()): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        drawScene(Canvas(out), source, plan, RendererRegistry.default(), scale, density, time, ScanPaints())
        return out
    }

    private fun Bitmap.at(item: MaskItem) = getPixel(60, item.quad.bounds().centerY().toInt())

    /** 落码那一遍里，光的中心第一次走到 [y] 的时刻。 */
    private fun revealReaching(y: Float): Long =
        (0L..ScanEffect.REVEAL_MS).first { ScanEffect.revealFront(it, 1000f, lead, trail, feather) >= y }

    /** 识别中，循环光的中心第一次走到 [y] 的时刻（已经淡入完）。 */
    private fun loopReaching(y: Float): Long =
        (ScanEffect.PASS_MS..2 * ScanEffect.PASS_MS).first { ScanEffect.loopCenter(it, 1000f, lead, trail) >= y }

    @Test
    fun `while recognition runs only the manual boxes are drawn`() {
        val frame = render(ScanTime.START)
        assertThat(frame.at(top)).isEqualTo(Color.WHITE)
        assertThat(frame.at(bottom)).isEqualTo(Color.WHITE)
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `the reveal lays blocks down behind the light, top first`() {
        val since = revealReaching(500f)
        val frame = render(ScanTime(10_000L + since, since))
        // 光早已走过上面那块：盖实了，光尾也离开了，就是原本的天蓝
        assertThat(frame.at(top)).isEqualTo(MaskOptions.SKY_BLUE)
        // 光还没到下面那块：原样
        assertThat(frame.at(bottom)).isEqualTo(Color.WHITE)
        // 手动框一直在，并且浮在光上面
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `a block the light is crossing is only partly laid down`() {
        // 渐显区正中：块已经出现，但还没盖实
        val y = bottom.quad.bounds().centerY()
        val since = revealReaching(y + feather / 2)
        val pixel = render(ScanTime(10_000L + since, since)).at(bottom)
        assertThat(pixel).isNotEqualTo(Color.WHITE)
        assertThat(pixel).isNotEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `once settled every block is drawn as usual`() {
        val frame = render(null)
        listOf(top, manual, bottom).forEach { assertThat(frame.at(it)).isEqualTo(MaskOptions.SKY_BLUE) }
    }

    @Test
    fun `the light colours the ink and leaves the paper light`() {
        // 左半黑（字）右半白（纸）
        val source = image { bmp ->
            bmp.eraseColor(Color.WHITE)
            Canvas(bmp).drawRect(0f, 0f, 60f, 1000f, android.graphics.Paint().apply { color = Color.BLACK })
        }
        val y = 300
        val frame = render(ScanTime(loopReaching(y + 1f), null), source)
        val ink = frame.getPixel(30, y)
        val paper = frame.getPixel(90, y)
        // 字被染成明显的蓝紫色
        assertThat(Color.blue(ink)).isGreaterThan(150)
        assertThat(Color.blue(ink)).isGreaterThan(Color.red(ink))
        // 纸只是一层淡淡的光晕
        listOf(Color.red(paper), Color.green(paper), Color.blue(paper)).forEach { assertThat(it).isGreaterThan(200) }
        assertThat(paper).isNotEqualTo(Color.WHITE)
        // 光够不着的地方原样
        assertThat(frame.getPixel(30, 700)).isEqualTo(Color.BLACK)
        assertThat(frame.getPixel(90, 700)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `the light stays on the image and never touches the canvas around it`() {
        val source = image()
        val out = Bitmap.createBitmap(200, 1000, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val canvas = Canvas(out)
        canvas.translate(40f, 0f)
        val y = 300
        drawScene(canvas, source, plan, RendererRegistry.default(), scale, density, ScanTime(loopReaching(y + 1f), null), ScanPaints())
        assertThat(out.getPixel(20, y)).isEqualTo(Color.GRAY)
        assertThat(out.getPixel(180, y)).isEqualTo(Color.GRAY)
        assertThat(out.getPixel(100, y)).isNotEqualTo(Color.WHITE)       // 图像上确实有光
    }
}
