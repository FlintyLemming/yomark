package com.youma.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
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
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * 直接画出磨砂与散开的某一帧，按像素核对：识别中只剩手动框、整页压暗变糊、闪着光点、不出图像；
 * 散开时从圆心往外揭开，块在洞里落下、洞外还压着；收尾后一切照常。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class FrostSceneDrawTest {

    // 400 × 700 的图，缩放 1、密度 1：一 dp 就是一个图像像素，磨砂按同样的口径准备
    private val scale = 1f
    private val density = 1f
    private val w = 400
    private val h = 700
    private val origin = PointF(200f, 350f)

    private val center = block("center", 150f, 330f, 250f, 370f, DetectorSource.RULE)
    private val corner = block("corner", 300f, 640f, 390f, 690f, DetectorSource.RULE)
    private val manual = block("manual", 20f, 20f, 120f, 60f, DetectorSource.MANUAL)
    private val plan = MaskPlan(listOf(center, corner, manual), MaskStyle.SOLID, MaskOptions())

    private fun block(id: String, l: Float, t: Float, r: Float, b: Float, source: DetectorSource) = MaskItem(
        id, Quad.fromRect(RectF(l, t, r, b)),
        if (source == DetectorSource.MANUAL) SensitiveKind.MANUAL else SensitiveKind.PHONE,
        source, MaskState.MASKED,
    )

    private fun image(paint: (Bitmap) -> Unit = { it.eraseColor(Color.WHITE) }) =
        SourceImage(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also(paint), 1f, w, h, "image/png")

    private fun render(time: ScanTime?, source: SourceImage = image(), prepared: Boolean = true): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val layer = if (prepared) FrostLayer.prepare(source.bitmap, density / scale) else null
        drawScene(
            Canvas(out), source, plan, RendererRegistry.default(), scale, density, time, ScanPaints(),
            ScanLook.Frost(layer, origin),
        )
        return out
    }

    private fun Bitmap.at(item: MaskItem) = item.quad.bounds().let { getPixel(it.centerX().toInt(), it.centerY().toInt()) }

    /** 识别开始以来 [ms] 毫秒，磨砂已经淡入完。 */
    private fun scanning(ms: Long = 3_000L) = ScanTime(ms, null)

    /** 结果在识别开始后 3 秒到达，散开过了 [since] 毫秒。 */
    private fun revealing(since: Long) = ScanTime(3_000L + since, since)

    /** 散开过程中，磨砂完全揭开的那一圈（洞的半径减去边缘）第一次超过 [d] 的时刻。 */
    private fun clearedReaching(d: Float): Long {
        val end = FrostEffect.endRadius(
            origin.x, origin.y, w.toFloat(), h.toFloat(), FrostEffect.EDGE_MAX_DP + FrostEffect.FEATHER_DP,
        )
        val start = FrostEffect.HOLE_START_DP
        return (0L..FrostEffect.REVEAL_MS).first {
            FrostEffect.holeRadius(it, start, end) -
                FrostEffect.edgeWidth(it, start, end, FrostEffect.EDGE_DP, FrostEffect.EDGE_MAX_DP) >= d
        }
    }

    /** 一小块区域的平均红色分量：光点只有零星几个像素，取平均不怕撞上。 */
    private fun Bitmap.meanRed(x: Int, y: Int, r: Int = 6): Double =
        (x - r..x + r).flatMap { px -> (y - r..y + r).map { py -> Color.red(getPixel(px, py)) } }.average()

    @Test
    fun `while recognition runs only the manual boxes are drawn`() {
        val frame = render(scanning())
        assertThat(frame.at(center)).isNotEqualTo(MaskOptions.SKY_BLUE)
        assertThat(frame.at(corner)).isNotEqualTo(MaskOptions.SKY_BLUE)
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `the page is dimmed to a cool blue while recognition runs`() {
        val frame = render(scanning())
        val red = frame.meanRed(60, 500)
        val blue = (54..66).flatMap { x -> (494..506).map { y -> Color.blue(frame.getPixel(x, y)) } }.average()
        assertThat(red).isLessThan(200.0)
        assertThat(blue).isGreaterThan(red)
    }

    @Test
    fun `the frost fades in instead of snapping on`() {
        assertThat(render(ScanTime.START).getPixel(60, 500)).isEqualTo(Color.WHITE)
        val early = render(scanning(FrostEffect.FADE_IN_MS / 6)).meanRed(60, 500)
        val full = render(scanning()).meanRed(60, 500)
        assertThat(early).isLessThan(255.0)
        assertThat(early).isGreaterThan(full)
    }

    @Test
    fun `the page is blurred under the frost`() {
        // 左半黑、右半白：清晰时交界处一边纯黑一边纯白，磨砂之后交界两侧糊成一片过渡
        val source = image { bmp ->
            bmp.eraseColor(Color.WHITE)
            Canvas(bmp).drawRect(0f, 0f, 200f, 700f, Paint().apply { color = Color.BLACK })
        }
        val frame = render(scanning(), source)
        val deepBlack = frame.meanRed(60, 500)
        val nearEdgeBlack = frame.meanRed(193, 500, r = 2)
        val nearEdgeWhite = frame.meanRed(207, 500, r = 2)
        val deepWhite = frame.meanRed(340, 500)
        assertThat(nearEdgeBlack - deepBlack).isGreaterThan(15.0)
        assertThat(deepWhite - nearEdgeWhite).isGreaterThan(15.0)
    }

    @Test
    fun `before the blur is ready the page is only dimmed`() {
        val frame = render(scanning(), prepared = false)
        val dimmed = frame.getPixel(60, 500)
        assertThat(Color.red(dimmed)).isLessThan(200)
        // 没有模糊图、没有光点，整页一样暗
        assertThat(frame.getPixel(300, 100)).isEqualTo(dimmed)
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `sparkles flicker on the frost`() {
        // 手机上一 dp 合两三个像素，光点才盖得满一整个像素；这里也按 3 倍画
        val dense = 3f
        val source = image()
        val layer = FrostLayer.prepare(source.bitmap, dense / scale)
        // 光点随噪声成片漂移：挑一个这一片正好落在图上的时刻
        val t = (0L..60_000L step 100L).first {
            layer.updateNoise(FrostEffect.timeSec(it))
            FrostNoise.sparkleMask(layer.noiseAt(w / 2f, h / 2f)) > 0.8f
        }
        fun lit(ms: Long): Set<Int> {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawScene(
                Canvas(out), source, plan, RendererRegistry.default(), scale, dense, scanning(ms), ScanPaints(),
                ScanLook.Frost(layer, origin),
            )
            val pixels = IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
            val reds = pixels.map { Color.red(it) }
            // 绝大多数像素是压暗的底子；比它亮得多的只能是光点
            val base = reds.sorted()[reds.size / 2]
            return reds.indices.filter { reds[it] > base + 40 }.toSet()
        }
        val a = lit(t)
        val b = lit(t + 300L)
        assertThat(a.size).isGreaterThan(20)
        assertThat(b.size).isGreaterThan(20)
        // 一闪一闪：0.3 秒之后再看，有不少点灭了、又有不少点亮了
        val changed = (a - b).size + (b - a).size
        assertThat(changed).isGreaterThan((a union b).size / 5)
    }

    @Test
    fun `the frost stays on the image and never touches the canvas around it`() {
        val source = image()
        val layer = FrostLayer.prepare(source.bitmap, density / scale)
        val out = Bitmap.createBitmap(w + 80, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        val canvas = Canvas(out)
        canvas.translate(40f, 0f)
        drawScene(canvas, source, plan, RendererRegistry.default(), scale, density, scanning(), ScanPaints(), ScanLook.Frost(layer, origin))
        for (y in 0 until h step 7) {
            assertThat(out.getPixel(20, y)).isEqualTo(Color.GRAY)
            assertThat(out.getPixel(w + 60, y)).isEqualTo(Color.GRAY)
        }
        assertThat(Color.red(out.getPixel(100, 500))).isLessThan(200)
    }

    @Test
    fun `the reveal opens from the centre and lays blocks down inside the hole first`() {
        // 磨砂已经揭过中间那块、渐显区也过去了，还没到角上那块
        val d = hypot(center.quad.bounds().right - origin.x, center.quad.bounds().bottom - origin.y)
        val frame = render(revealing(clearedReaching(d + FrostEffect.FEATHER_DP + 1f)))
        assertThat(frame.at(center)).isEqualTo(MaskOptions.SKY_BLUE)
        // 洞里磨砂已揭开，回到原本的白
        assertThat(frame.getPixel(200, 300)).isEqualTo(Color.WHITE)
        // 角上那块还在磨砂底下：没有块，和旁边一样压着
        assertThat(frame.at(corner)).isNotEqualTo(MaskOptions.SKY_BLUE)
        assertThat(frame.meanRed(345, 665, r = 3)).isLessThan(200.0)
        // 手动框一直在
        assertThat(frame.at(manual)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `blocks still land when the same paints carried over from the first frames of the reveal`() {
        // 画布上的画笔是跨帧复用的。散开刚开始那几帧，磨砂还没有哪里完全揭开，块一律不画；
        // 之后的帧里块照样要落下来，不能被前几帧留在画笔上的状态吞掉
        val source = image()
        val layer = FrostLayer.prepare(source.bitmap, density / scale)
        val paints = ScanPaints()
        fun frame(since: Long): Bitmap {
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            drawScene(
                Canvas(out), source, plan, RendererRegistry.default(), scale, density, revealing(since), paints,
                ScanLook.Frost(layer, origin),
            )
            return out
        }
        frame(0L)
        frame(20L)
        val d = hypot(center.quad.bounds().right - origin.x, center.quad.bounds().bottom - origin.y)
        assertThat(frame(clearedReaching(d + FrostEffect.FEATHER_DP + 1f)).at(center)).isEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `a block the hole has just reached is only partly laid down`() {
        val y = center.quad.bounds().centerY()
        val d = hypot(0f, y - origin.y)
        // 磨砂揭开的那一圈刚过块的中心半个渐显区
        val pixel = render(revealing(clearedReaching(d + FrostEffect.FEATHER_DP / 2f))).getPixel(200, y.toInt())
        assertThat(pixel).isNotEqualTo(Color.WHITE)
        assertThat(pixel).isNotEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `results wait under the frost the moment they arrive`() {
        val frame = render(revealing(0L))
        assertThat(frame.at(corner)).isNotEqualTo(MaskOptions.SKY_BLUE)
        assertThat(frame.meanRed(345, 665, r = 3)).isLessThan(200.0)
        assertThat(Color.red(frame.at(center))).isLessThan(250)
    }

    @Test
    fun `the page is back to normal once the hole has spread past every corner`() {
        val frame = render(revealing(FrostEffect.REVEAL_MS))
        assertThat(frame.getPixel(5, 695)).isEqualTo(Color.WHITE)
        assertThat(frame.getPixel(395, 5)).isEqualTo(Color.WHITE)
        listOf(center, corner, manual).forEach { assertThat(frame.at(it)).isEqualTo(MaskOptions.SKY_BLUE) }
    }

    @Test
    fun `once settled every block is drawn as usual`() {
        val frame = render(null)
        listOf(center, corner, manual).forEach { assertThat(frame.at(it)).isEqualTo(MaskOptions.SKY_BLUE) }
        assertThat(frame.getPixel(200, 500)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `the loader shows in the middle while recognising and is gone after the burst`() {
        fun loaderAt(time: ScanTime): Int {
            val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            drawLoader(Canvas(out), 100f, 100f, density = 2f, time = time, paints = FrostPaints())
            return out.getPixel(100, 100)
        }
        assertThat(Color.alpha(loaderAt(ScanTime.START))).isEqualTo(0)
        assertThat(loaderAt(ScanTime(FrostEffect.LOADER_IN_MS, null))).isEqualTo(FrostPaints.LOADER_COLOR)
        assertThat(Color.alpha(loaderAt(revealing(FrostEffect.BURST_MS)))).isEqualTo(0)
    }

    @Test
    fun `blurring smooths a hard edge and leaves flat areas alone`() {
        val bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.WHITE)
            Canvas(this).drawRect(0f, 0f, 200f, 200f, Paint().apply { color = Color.BLACK })
        }
        val blurred = FrostLayer.blur(bmp, sigma = 20f)
        // 缩小了：模糊只剩两三个像素，画的时候再拉伸回去
        assertThat(blurred.width).isLessThan(bmp.width / 4)
        val row = blurred.height / 2
        val reds = (0 until blurred.width).map { Color.red(blurred.getPixel(it, row)) }
        assertThat(reds.first()).isLessThan(10)
        assertThat(reds.last()).isGreaterThan(245)
        // 中间是一段从黑到白的斜坡，不再是一刀切
        assertThat(reds.count { it in 30..225 }).isAtLeast(2)
        assertThat(reds.zipWithNext().all { (a, b) -> b >= a }).isTrue()
    }

    @Test
    fun `about one cell in fourteen gets a sparkle`() {
        val layer = FrostLayer.prepare(Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888), pxPerDp = 1f)
        val cells = (500 / FrostEffect.SPARKLE_CELL_DP) * (500 / FrostEffect.SPARKLE_CELL_DP)
        assertThat(layer.sparkleCount / cells).isWithin(0.025f).of(0.073f)
    }
}
