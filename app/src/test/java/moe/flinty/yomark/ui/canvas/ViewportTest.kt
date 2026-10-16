package moe.flinty.yomark.ui.canvas

import android.graphics.PointF
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewportTest {

    @Test
    fun `fit scales a wide image to the view width and centers it vertically`() {
        val v = Viewport.fit(imageW = 1000, imageH = 500, viewW = 500, viewH = 500)
        assertThat(v.scale).isWithin(1e-4f).of(0.5f)
        assertThat(v.offsetX).isWithin(1e-3f).of(0f)
        assertThat(v.offsetY).isWithin(1e-3f).of(125f)   // (500 - 250) / 2
    }

    @Test
    fun `fit scales a tall image to the view height and centers it horizontally`() {
        val v = Viewport.fit(imageW = 500, imageH = 1000, viewW = 500, viewH = 500)
        assertThat(v.scale).isWithin(1e-4f).of(0.5f)
        assertThat(v.offsetX).isWithin(1e-3f).of(125f)
        assertThat(v.offsetY).isWithin(1e-3f).of(0f)
    }

    /** 大爆炸的面板出来、画布变矮：放大着看的图上选中的块落到了画布下面，挪上来（spec §7.3，2026-10-16）。 */
    @Test
    fun `revealing pans an area below the view back into it`() {
        val v = Viewport(scale = 2f, offsetX = 0f, offsetY = 0f)
        // 图上 100..120 在屏幕上是 200..240，画布只有 180 高
        val moved = v.revealing(android.graphics.RectF(10f, 100f, 50f, 120f), viewW = 400, viewH = 180, margin = 10f)
        assertThat(moved.offsetY).isWithin(1e-3f).of(-70f)       // 240 - 70 = 170 = 180 - 10
        assertThat(moved.offsetX).isWithin(1e-3f).of(0f)
        assertThat(v.revealing(android.graphics.RectF(10f, 10f, 50f, 20f), 400, 180, 10f)).isEqualTo(v)
    }

    @Test
    fun `revealing an area taller than the view shows its top`() {
        val v = Viewport(scale = 1f, offsetX = 0f, offsetY = 0f)
        val moved = v.revealing(android.graphics.RectF(0f, 300f, 50f, 900f), viewW = 400, viewH = 400, margin = 10f)
        assertThat(moved.offsetY).isWithin(1e-3f).of(-290f)
    }

    @Test
    fun `imageToScreen and screenToImage round trip`() {
        val v = Viewport(scale = 0.5f, offsetX = 30f, offsetY = 40f)
        val p = PointF(200f, 100f)
        val back = v.screenToImage(v.imageToScreen(p))
        assertThat(back.x).isWithin(1e-3f).of(200f)
        assertThat(back.y).isWithin(1e-3f).of(100f)
    }

    @Test
    fun `imageToScreen applies scale then offset`() {
        val v = Viewport(scale = 2f, offsetX = 10f, offsetY = 20f)
        val s = v.imageToScreen(PointF(5f, 5f))
        assertThat(s.x).isWithin(1e-3f).of(20f)
        assertThat(s.y).isWithin(1e-3f).of(30f)
    }

    @Test
    fun `pan shifts the offset`() {
        val v = Viewport(1f, 0f, 0f).pan(15f, -5f)
        assertThat(v.offsetX).isWithin(1e-3f).of(15f)
        assertThat(v.offsetY).isWithin(1e-3f).of(-5f)
    }

    @Test
    fun `zoomAround keeps the pivot point stationary on screen`() {
        val v = Viewport(scale = 1f, offsetX = 0f, offsetY = 0f)
        val pivot = PointF(100f, 80f)          // 屏幕坐标
        val before = v.screenToImage(pivot)
        val z = v.zoomAround(pivot, 2f)
        val after = z.screenToImage(pivot)
        assertThat(after.x).isWithin(1e-2f).of(before.x)
        assertThat(after.y).isWithin(1e-2f).of(before.y)
        assertThat(z.scale).isWithin(1e-4f).of(2f)
    }

    @Test
    fun `zoom is capped at MAX_SCALE_FACTOR times the fit scale`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)          // scale 0.5
        val z = fit.zoomAround(PointF(250f, 250f), 100f)
            .clamped(1000, 1000, 500, 500, fitScale = fit.scale)
        assertThat(z.scale).isAtMost(fit.scale * Viewport.MAX_SCALE_FACTOR + 1e-3f)
    }

    @Test
    fun `clamped never zooms out below the fit scale`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)
        val z = fit.zoomAround(PointF(250f, 250f), 0.1f)
            .clamped(1000, 1000, 500, 500, fitScale = fit.scale)
        assertThat(z.scale).isWithin(1e-3f).of(fit.scale)
    }

    @Test
    fun `clamped keeps a zoomed image from leaving the viewport`() {
        val fit = Viewport.fit(1000, 1000, 500, 500)              // scale 0.5, 铺满
        val panned = fit.copy(scale = 1f).pan(5000f, 5000f)
            .clamped(1000, 1000, 500, 500, fitScale = fit.scale)
        // scale=1 时图像 1000px 宽，视口 500px：offset 必须落在 [-500, 0]
        assertThat(panned.offsetX).isAtMost(0f)
        assertThat(panned.offsetX).isAtLeast(-500f)
    }
}
