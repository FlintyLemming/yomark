package com.youma.app.engine

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import com.youma.app.core.geometry.Quad
import com.youma.app.engine.mlkit.TwoInks
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.hypot

/**
 * TwoInks 取的是**四边形里**的像素：倾斜的码，外接矩形四个角上是旁边的图文，算进来会把真码判成照片。
 * 四边形正中间（logo、头像的位置）不取。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixels 恒为 0，Bitmap 那条会假通过
class TwoInksSamplingTest {

    /** 像素值里编码坐标：x 在第 12 位以上，y 在低 12 位。 */
    private val coordinates: (Int, Int, IntArray) -> Unit = { y, left, row ->
        for (i in row.indices) row[i] = ((left + i) shl 12) or y
    }

    private fun Int.x() = this shr 12
    private fun Int.y() = this and 0xFFF

    private fun perimeter(q: Quad): Float {
        val p = q.points()
        return (0 until 4).sumOf { i -> hypot(p[(i + 1) % 4].x - p[i].x, p[(i + 1) % 4].y - p[i].y).toDouble() }.toFloat()
    }

    /** 四边形以重心为中心缩到 LOGO_ZONE：中间不取的那一块。 */
    private fun logoZone(q: Quad): Quad {
        val cx = q.points().map { it.x }.average().toFloat()
        val cy = q.points().map { it.y }.average().toFloat()
        val z = TwoInks.LOGO_ZONE
        return Quad(
            PointF(cx + (q.p0.x - cx) * z, cy + (q.p0.y - cy) * z), PointF(cx + (q.p1.x - cx) * z, cy + (q.p1.y - cy) * z),
            PointF(cx + (q.p2.x - cx) * z, cy + (q.p2.y - cy) * z), PointF(cx + (q.p3.x - cx) * z, cy + (q.p3.y - cy) * z),
        )
    }

    @Test fun `only pixels inside a tilted quad and outside its logo zone are sampled`() {
        val quad = Quad(PointF(60f, 10f), PointF(110f, 60f), PointF(60f, 110f), PointF(10f, 60f))
        val logo = logoZone(quad)
        val got = TwoInks.sampleInside(120, 120, quad, coordinates)
        got.forEach {
            val centre = PointF(it.x() + 0.5f, it.y() + 0.5f)
            assertThat(quad.contains(centre)).isTrue()
            assertThat(logo.contains(centre) && logo.distanceTo(centre) > 1e-3f).isFalse()
        }
        // 菱形只占外接矩形的一半，再扣掉中间 16%：取到的像素数应当贴着这块面积，而不是外接矩形
        assertThat(got.size.toFloat())
            .isWithin(perimeter(quad) + perimeter(logo))
            .of(quad.area() - logo.area())
    }

    @Test fun `a quad sticking out of the image is clipped to it`() {
        val quad = Quad.fromRect(RectF(-20f, -20f, 30f, 30f))
        val got = TwoInks.sampleInside(100, 100, quad, coordinates)
        // 图内是 30×30；logo 区是 (−5,−5)–(15,15)，图内那部分 15×15 不取
        assertThat(got.size).isEqualTo(30 * 30 - 15 * 15)
        got.forEach {
            assertThat(it.x()).isIn(0 until 30)
            assertThat(it.y()).isIn(0 until 30)
            assertThat(it.x() >= 15 || it.y() >= 15).isTrue()
        }
    }

    @Test fun `a quad outside the image samples nothing`() {
        val quad = Quad.fromRect(RectF(200f, 200f, 260f, 260f))
        assertThat(TwoInks.sampleInside(100, 100, quad, coordinates)).isEmpty()
    }

    /** 判的是比例，大框隔行隔列取就够了；取样上限要真的管住数组大小。 */
    @Test fun `a large quad is subsampled`() {
        val quad = Quad.fromRect(RectF(0f, 0f, 2000f, 1500f))
        val got = TwoInks.sampleInside(2000, 1500, quad) { _, _, row -> row.fill(0) }
        assertThat(got.size).isAtMost(TwoInks.MAX_SAMPLES)
        assertThat(got.size).isAtLeast(TwoInks.MAX_SAMPLES / 4)
    }

    /** 左半张是彩色照片、右半张是码：四边形框在哪半边，读到的就是哪半边。 */
    @Test fun `looksPrinted reads the pixels under the quad from the bitmap`() {
        val photo = coffeePhoto()
        val code = fakeCode(modules = 21, module = 4)
        val h = maxOf(photo.h, code.h)
        val bmp = Bitmap.createBitmap(photo.w + code.w, h, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(android.graphics.Color.WHITE)
        bmp.setPixels(photo.px, 0, photo.w, 0, 0, photo.w, photo.h)
        bmp.setPixels(code.px, 0, code.w, photo.w, 0, code.w, code.h)

        val overPhoto = Quad.fromRect(RectF(0f, 0f, photo.w.toFloat(), photo.h.toFloat()))
        val overCode = Quad.fromRect(RectF(photo.w.toFloat(), 0f, (photo.w + code.w).toFloat(), code.h.toFloat()))
        assertThat(TwoInks.looksPrinted(bmp, overPhoto)).isFalse()
        assertThat(TwoInks.looksPrinted(bmp, overCode)).isTrue()
    }
}
