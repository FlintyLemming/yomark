package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0，断言会假通过
class MorphingLoaderTest {

    private val interval = MorphingLoader.MORPH_INTERVAL_MS

    @Test
    fun `it cycles through the seven Material shapes and wraps around`() {
        assertThat(MorphingLoader.shapes).hasSize(7)
        assertThat(MorphingLoader.morphIndex(0L)).isEqualTo(0)
        assertThat(MorphingLoader.morphIndex(interval * 3 + 10)).isEqualTo(3)
        assertThat(MorphingLoader.morphIndex(interval * 7)).isEqualTo(0)
    }

    @Test
    fun `the spring overshoots once by about nine percent and settles within a morph`() {
        assertThat(MorphingLoader.spring(0f)).isWithin(1e-6f).of(0f)
        val peak = (0..650).maxOf { MorphingLoader.spring(it / 1000f) }
        assertThat(peak).isWithin(0.01f).of(1.095f)
        assertThat(MorphingLoader.spring(0.65f)).isWithin(0.01f).of(1f)
    }

    @Test
    fun `each morph starts from the previous shape and springs into the next`() {
        for (k in 0L until 7L) {
            assertThat(MorphingLoader.morphProgress(k * interval)).isWithin(1e-6f).of(0f)
            assertThat(MorphingLoader.morphProgress(k * interval + interval - 1)).isWithin(0.01f).of(1f)
        }
    }

    @Test
    fun `it keeps turning clockwise without jumps`() {
        var prev = MorphingLoader.rotation(0L)
        // 与 Material 3 一样从 90° 起步
        assertThat(prev).isWithin(1e-3f).of(90f)
        for (ms in 5L..10_000L step 5L) {
            val r = MorphingLoader.rotation(ms)
            assertThat(r).isGreaterThan(prev)
            // 一帧之内转不了多少：换下一段变形的那一刻也接得上
            assertThat(r - prev).isLessThan(5f)
            prev = r
        }
        // 每变一次形转四分之一圈，再加上整体匀速转的那部分
        val oneMorph = MorphingLoader.rotation(interval) - MorphingLoader.rotation(0L)
        assertThat(oneMorph).isWithin(1f).of(90f + 360f * interval / MorphingLoader.GLOBAL_ROTATION_MS)
    }

    private fun drawAt(ms: Long, alpha: Float = 1f, scale: Float = 1f): Bitmap {
        val out = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val paints = FrostPaints()
        MorphingLoader.draw(
            Canvas(out), 100f, 100f, container = 120f, ms = ms, scale = scale, alpha = alpha,
            paint = paints.loader, path = paints.loaderPath, matrix = paints.loaderMatrix, bounds = paints.loaderBounds,
        )
        return out
    }

    @Test
    fun `it draws a filled shape centred on the spot and inside its 48 dp box`() {
        for (ms in 0L..interval * 7 step 130L) {
            val frame = drawAt(ms)
            assertThat(frame.getPixel(100, 100)).isEqualTo(FrostPaints.LOADER_COLOR)
            // 120 像素的位置里画 38 / 48 大小的形状：位置外沿一圈什么都没有
            listOf(38 to 100, 162 to 100, 100 to 38, 100 to 162, 42 to 42, 158 to 158).forEach { (x, y) ->
                assertThat(Color.alpha(frame.getPixel(x, y))).isEqualTo(0)
            }
        }
    }

    @Test
    fun `it draws nothing when faded out`() {
        val frame = drawAt(1_000L, alpha = 0f)
        assertThat(Color.alpha(frame.getPixel(100, 100))).isEqualTo(0)
    }

    @Test
    fun `it changes shape over time`() {
        val a = drawAt(0L)
        val b = drawAt(interval * 2)
        var differing = 0
        for (y in 0 until 200 step 2) for (x in 0 until 200 step 2) {
            if (a.getPixel(x, y) != b.getPixel(x, y)) differing++
        }
        assertThat(differing).isGreaterThan(50)
    }
}
