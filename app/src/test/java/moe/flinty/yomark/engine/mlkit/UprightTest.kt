package moe.flinty.yomark.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 人脸检测转正用的那一套：位图真的按 Upright.matrix 转（Skia，同手机上），转正的图上找到的东西换回原图正好落回原处。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UprightTest {

    /** 60×40 的白图，(10, 5)–(20, 25) 涂红，左上角 (0, 0)–(4, 4) 涂蓝。 */
    private val source = Bitmap.createBitmap(60, 40, Bitmap.Config.ARGB_8888).also { bmp ->
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        canvas.drawRect(RectF(10f, 5f, 20f, 25f), Paint().apply { color = Color.RED })
        canvas.drawRect(RectF(0f, 0f, 4f, 4f), Paint().apply { color = Color.BLUE })
    }

    private fun turned(turns: Int) =
        Bitmap.createBitmap(source, 0, 0, source.width, source.height, Upright.matrix(source.width, source.height, turns), true)

    /** [color] 那些像素的外接框（像素边的口径）。 */
    private fun Bitmap.find(color: Int): RectF {
        var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = -1; var b = -1
        for (y in 0 until height) for (x in 0 until width) if (getPixel(x, y) == color) {
            l = minOf(l, x); t = minOf(t, y); r = maxOf(r, x + 1); b = maxOf(b, y + 1)
        }
        return RectF(l.toFloat(), t.toFloat(), r.toFloat(), b.toFloat())
    }

    @Test fun `a box found on the upright bitmap maps back to where it was`() {
        for (turns in 1..3) {
            val up = turned(turns)
            assertWithMessage("$turns").that(up.width to up.height).isEqualTo(if (turns == 2) 60 to 40 else 40 to 60)
            assertWithMessage("$turns").that(Upright.toSource(up.find(Color.RED), 60, 40, turns)).isEqualTo(RectF(10f, 5f, 20f, 25f))
        }
    }

    /**
     * 转的方向：页面顺时针转了一个直角，转正是逆时针转回去——原图左上角转到左下角。
     * 转反了的话框照样能换回原处（上面那条测不出来），但人脸检测看到的是倒着的脸。
     */
    @Test fun `turning back goes the other way round`() {
        assertThat(turned(1).find(Color.BLUE)).isEqualTo(RectF(0f, 56f, 4f, 60f))
        assertThat(turned(2).find(Color.BLUE)).isEqualTo(RectF(56f, 36f, 60f, 40f))
        assertThat(turned(3).find(Color.BLUE)).isEqualTo(RectF(36f, 0f, 40f, 4f))
    }
}
