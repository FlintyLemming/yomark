package com.yomark.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 直接画出扫光与落码的某一帧，按像素核对：识别中藏、落码自上而下、手动框始终在、
 * 识别中压暗、落码时亮度跟着揭开、白底上看得见光、光不出图像。
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
    private val plan = MaskPlan(listOf(top, manual, bottom))

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
        (ScanEffect.PASS_MS..2 * ScanEffect.PASS_MS).first { ScanEffect.loopCenter(ScanEffect.loopPhase(it, null)!!, 1000f, lead, trail) >= y }

    /** 结果在识别开始后 10 秒到达；循环光提速走完那一趟之后，落码那一遍过了 [since] 毫秒。 */
    private fun revealing(since: Long): ScanTime {
        val afterResult = ScanEffect.finishMs(10_000L) + since
        return ScanTime(10_000L + afterResult, afterResult)
    }

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
        val frame = render(revealing(since))
        // 光早已走过上面那块：盖实了，光尾也离开了，就是原本的天蓝
        assertThat(frame.at(top)).isEqualTo(MaskOptions.SKY_BLUE)
        // 光走过的地方暗幕已经揭开，回到原本的亮度
        assertThat(frame.getPixel(115, 115)).isEqualTo(Color.WHITE)
        // 光还没到下面那块：没有块，和旁边的空白一样还压着暗幕
        val y = bottom.quad.bounds().centerY().toInt()
        assertThat(frame.at(bottom)).isEqualTo(frame.getPixel(115, y))
        assertThat(Color.red(frame.at(bottom))).isLessThan(128)
        // 手动框一直在，并且浮在光上面
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `a block the light is crossing is only partly laid down`() {
        // 渐显区正中：块已经出现，但还没盖实
        val y = bottom.quad.bounds().centerY()
        val since = revealReaching(y + feather / 2)
        val pixel = render(revealing(since)).at(bottom)
        assertThat(pixel).isNotEqualTo(Color.WHITE)
        assertThat(pixel).isNotEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `results wait under the scrim while the light in flight finishes its pass`() {
        // 结果到达时光正走到一趟的一半：它接着往下走，块还没开始落
        val scanAt = 10_000L + ScanEffect.PASS_MS / 2
        val since = ScanEffect.finishMs(scanAt) / 2
        val frame = render(ScanTime(scanAt + since, since))
        val dimmed = frame.getPixel(115, 990)
        assertThat(Color.red(dimmed)).isLessThan(128)
        assertThat(frame.at(top)).isEqualTo(frame.getPixel(115, top.quad.bounds().centerY().toInt()))
        assertThat(frame.at(bottom)).isNotEqualTo(MaskOptions.SKY_BLUE)
        // 光还在图上
        val center = ScanEffect.loopCenter(ScanEffect.loopPhase(scanAt + since, since)!!, 1000f, lead, trail)
        assertThat(center).isGreaterThan(0f)
        assertThat(center).isLessThan(1000f)
        assertThat(frame.getPixel(115, center.toInt())).isNotEqualTo(dimmed)
    }

    @Test
    fun `once settled every block is drawn as usual`() {
        val frame = render(null)
        listOf(top, manual, bottom).forEach { assertThat(frame.at(it)).isEqualTo(MaskOptions.SKY_BLUE) }
    }

    @Test
    fun `the page is dimmed while recognition runs`() {
        val frame = render(ScanTime(ScanEffect.FADE_IN_MS, null))
        // 光此刻还藏在图像上方，整张图是一片均匀的暗
        val dimmed = frame.getPixel(115, 500)
        assertThat(Color.red(dimmed)).isLessThan(128)
        assertThat(frame.getPixel(115, 900)).isEqualTo(dimmed)
        // 识别出的块不画，只剩暗下来的纸
        assertThat(frame.at(top)).isEqualTo(dimmed)
        // 手动框浮在暗幕上面，颜色不变
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `the scrim fades in with the light instead of snapping on`() {
        assertThat(render(ScanTime.START).getPixel(115, 500)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `the light shows up on a white page`() {
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
        // 纸压暗之后，光在上面看得出来：比光外的纸亮得多，也明显偏蓝
        val unlit = frame.getPixel(90, 700)
        assertThat(Color.blue(paper) - Color.blue(unlit)).isGreaterThan(60)
        assertThat(Color.blue(paper)).isGreaterThan(Color.red(paper))
        // 光够不着的地方只是压暗，不染色
        assertThat(frame.getPixel(30, 700)).isEqualTo(Color.BLACK)
        assertThat(Color.red(unlit)).isEqualTo(Color.blue(unlit))
        assertThat(Color.red(unlit)).isLessThan(128)
    }

    @Test
    fun `the page brightness comes back once the reveal is over`() {
        val frame = render(revealing(ScanEffect.REVEAL_MS))
        assertThat(frame.getPixel(115, 990)).isEqualTo(Color.WHITE)
        assertThat(frame.at(bottom)).isEqualTo(MaskOptions.SKY_BLUE)
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
        assertThat(out.getPixel(20, 700)).isEqualTo(Color.GRAY)          // 暗幕也不出图像
        assertThat(out.getPixel(100, y)).isNotEqualTo(Color.WHITE)       // 图像上确实有光
    }
}
