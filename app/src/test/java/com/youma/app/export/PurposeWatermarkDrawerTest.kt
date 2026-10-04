package com.youma.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PurposeWatermarkDrawerTest {

    private fun white(w: Int = 600, h: Int = 800) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    private fun changedFraction(bmp: Bitmap): Double {
        var changed = 0
        var total = 0
        for (x in 0 until bmp.width step 5) for (y in 0 until bmp.height step 5) {
            total++
            if (bmp.getPixel(x, y) != Color.WHITE) changed++
        }
        return changed.toDouble() / total
    }

    @Test
    fun `the watermark is tiled across the whole image`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "仅供办理签证使用")
        // 平铺：全图各处都有痕迹，不是只在一个角
        assertThat(changedFraction(bmp)).isGreaterThan(0.05)
    }

    @Test
    fun `every quadrant carries some of the watermark`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "仅供办理签证使用")
        fun quadrantMarked(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
            for (x in x0 until x1 step 4) for (y in y0 until y1 step 4) {
                if (bmp.getPixel(x, y) != Color.WHITE) return true
            }
            return false
        }
        assertThat(quadrantMarked(0, 0, 300, 400)).isTrue()
        assertThat(quadrantMarked(300, 0, 600, 400)).isTrue()
        assertThat(quadrantMarked(0, 400, 300, 800)).isTrue()
        assertThat(quadrantMarked(300, 400, 600, 800)).isTrue()
    }

    @Test
    fun `the watermark is translucent so the photo underneath stays readable`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "仅供办理签证使用")
        var opaqueBlack = 0
        for (x in 0 until bmp.width step 3) for (y in 0 until bmp.height step 3) {
            if (bmp.getPixel(x, y) == Color.BLACK) opaqueBlack++
        }
        assertThat(opaqueBlack).isEqualTo(0)
    }

    @Test
    fun `an empty text draws nothing`() {
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), bmp.width, bmp.height, "")
        assertThat(changedFraction(bmp)).isEqualTo(0.0)
    }

    @Test
    fun `nothing is drawn outside the image bounds`() {
        // 编辑器预览的画布比图大：图外的留白不该被铺上字
        val bmp = white()
        PurposeWatermarkDrawer().draw(Canvas(bmp), 300, 400, "仅供办理签证使用")
        for (x in 0 until bmp.width step 4) for (y in 0 until bmp.height step 4) {
            if (x >= 300 || y >= 400) assertThat(bmp.getPixel(x, y)).isEqualTo(Color.WHITE)
        }
    }

    @Test
    fun `a tiny image does not crash`() {
        val bmp = white(20, 20)
        PurposeWatermarkDrawer().draw(Canvas(bmp), 20, 20, "仅供办理签证使用")
    }
}
