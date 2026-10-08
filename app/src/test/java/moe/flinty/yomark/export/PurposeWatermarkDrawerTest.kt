package moe.flinty.yomark.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.collect.Range
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

    private fun drawn(style: PurposeWatermarkStyle, w: Int = 600, h: Int = 800): Bitmap = white(w, h).also {
        PurposeWatermarkDrawer().draw(Canvas(it), it.width, it.height, "仅供办理签证使用", style)
    }

    @Test
    fun `the default style keeps the original translucent black look`() {
        val bmp = drawn(PurposeWatermarkStyle())
        var darkest = 255
        for (x in 0 until bmp.width step 2) for (y in 0 until bmp.height step 2) {
            val c = bmp.getPixel(x, y)
            assertThat(Color.red(c)).isEqualTo(Color.green(c))     // 灰阶：只有黑，没有色相
            darkest = minOf(darkest, Color.red(c))
        }
        // 18% 黑压在白上 ≈ 209；抗锯齿边缘只会更浅
        assertThat(darkest).isIn(Range.closed(200, 215))
    }

    @Test
    fun `the chosen color is what lands on the image`() {
        val bmp = drawn(PurposeWatermarkStyle(color = PurposeWatermarkStyle.PALETTE[3], opacity = 0.6f))
        var reddish = 0
        for (x in 0 until bmp.width step 2) for (y in 0 until bmp.height step 2) {
            val c = bmp.getPixel(x, y)
            if (Color.red(c) > Color.blue(c) + 40) reddish++
        }
        assertThat(reddish).isGreaterThan(100)
    }

    @Test
    fun `higher opacity draws a stronger mark`() {
        fun darkest(opacity: Float): Int {
            val bmp = drawn(PurposeWatermarkStyle(opacity = opacity))
            var d = 255
            for (x in 0 until bmp.width step 2) for (y in 0 until bmp.height step 2) d = minOf(d, Color.red(bmp.getPixel(x, y)))
            return d
        }
        assertThat(darkest(0.5f)).isLessThan(darkest(0.1f))
    }

    @Test
    fun `even the highest opacity stays translucent`() {
        val bmp = drawn(PurposeWatermarkStyle(opacity = 5f))           // 越界值也收回上限
        for (x in 0 until bmp.width step 2) for (y in 0 until bmp.height step 2) {
            assertThat(bmp.getPixel(x, y)).isNotEqualTo(Color.BLACK)
        }
    }

    @Test
    fun `higher density covers more of the image`() {
        val sparse = changedFraction(drawn(PurposeWatermarkStyle(density = 0.5f)))
        val dense = changedFraction(drawn(PurposeWatermarkStyle(density = 2f)))
        assertThat(dense).isGreaterThan(sparse * 2)
    }

    @Test
    fun `a level watermark leaves clean horizontal lanes between rows`() {
        // 0° 时字是一行一行水平的，行与行之间有整条没字的横带；斜着铺就找不到这样一条
        fun cleanRows(bmp: Bitmap) = (0 until bmp.height).count { y ->
            (0 until bmp.width).all { x -> bmp.getPixel(x, y) == Color.WHITE }
        }
        val level = cleanRows(drawn(PurposeWatermarkStyle(angle = 0f)))
        val tilted = cleanRows(drawn(PurposeWatermarkStyle(angle = -30f)))
        assertThat(level).isGreaterThan(200)
        assertThat(level).isGreaterThan(tilted * 3)
    }

    @Test
    fun `every angle still covers all four quadrants`() {
        listOf(-90f, -45f, 0f, 45f, 90f).forEach { angle ->
            val bmp = drawn(PurposeWatermarkStyle(angle = angle))
            fun marked(x0: Int, y0: Int, x1: Int, y1: Int) =
                (x0 until x1 step 3).any { x -> (y0 until y1 step 3).any { y -> bmp.getPixel(x, y) != Color.WHITE } }
            assertThat(marked(0, 0, 300, 400)).isTrue()
            assertThat(marked(300, 0, 600, 400)).isTrue()
            assertThat(marked(0, 400, 300, 800)).isTrue()
            assertThat(marked(300, 400, 600, 800)).isTrue()
        }
    }

    @Test
    fun `a tiny image does not crash`() {
        val bmp = white(20, 20)
        PurposeWatermarkDrawer().draw(Canvas(bmp), 20, 20, "仅供办理签证使用")
    }
}
