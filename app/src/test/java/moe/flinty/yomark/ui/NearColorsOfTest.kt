package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.ui.components.nearColorsOf
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 面板上「近似色」那颗小圆画什么颜色：选中了框就是它四周的颜色，否则是图上各块取到的几种（相近的并成一种），
 * 一块也没有时是整张图的底色。小圆轮着换的就是这几种。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 模式下 getPixel 恒为 0
class NearColorsOfTest {

    private val white = Color.WHITE
    private val gray = Color.rgb(240, 242, 245)
    private val blue = Color.rgb(51, 136, 255)

    /** 浅灰的页面底上两张白卡片、一个蓝色气泡，和聊天截图差不多。 */
    private val image: SourceImage = Bitmap.createBitmap(400, 500, Bitmap.Config.ARGB_8888).let { bmp ->
        Canvas(bmp).apply {
            drawColor(gray)
            drawRect(RectF(20f, 20f, 380f, 120f), Paint().apply { color = white })
            drawRect(RectF(20f, 140f, 380f, 240f), Paint().apply { color = white })
            drawRect(RectF(200f, 260f, 380f, 320f), Paint().apply { color = blue })
        }
        SourceImage(bmp, 1f, 400, 500, "image/png")
    }

    private fun box(id: String, l: Float, t: Float, r: Float, b: Float) = MaskItem(
        id, Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED,
    )

    private val onCard1 = box("card-1", 60f, 50f, 300f, 90f)
    private val onCard2 = box("card-2", 60f, 170f, 300f, 210f)
    private val onPage = box("page", 60f, 400f, 300f, 440f)
    private val inBubble = box("bubble", 230f, 275f, 350f, 305f)

    @Test fun `a selected box shows only its own surroundings`() {
        val plan = MaskPlan(listOf(onCard1, inBubble))
        assertThat(nearColorsOf(image, plan, selected = inBubble)).containsExactly(blue)
    }

    @Test fun `without a selection every box counts, the most common colour first`() {
        val plan = MaskPlan(listOf(onPage, onCard1, inBubble, onCard2))
        assertThat(nearColorsOf(image, plan, selected = null)).containsExactly(white, gray, blue).inOrder()
    }

    @Test fun `nearly the same colour counts as one`() {
        val plan = MaskPlan(listOf(onCard1, onCard2))
        assertThat(nearColorsOf(image, plan, selected = null)).containsExactly(white)
    }

    /** 还没有一块码：整张图占地最多的颜色，这里是浅灰的页面底。 */
    @Test fun `with no boxes yet it is the colour of the page`() {
        assertThat(nearColorsOf(image, MaskPlan.empty(), selected = null)).containsExactly(gray)
    }

    @Test fun `no image, no colours`() {
        assertThat(nearColorsOf(null, MaskPlan(listOf(onCard1)), selected = null)).isEmpty()
    }
}
