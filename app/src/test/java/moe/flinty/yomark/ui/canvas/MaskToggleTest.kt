package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** 点一下打码、取消打码：哪些码要演、演到哪儿，以及画出来左边先盖上 / 先揭开。 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskToggleTest {

    private fun block(id: String, source: DetectorSource = DetectorSource.LINE, y: Float = 10f) = MaskItem(
        id, Quad.fromRect(RectF(0f, y, 200f, y + 20f)),
        if (source == DetectorSource.MANUAL) SensitiveKind.MANUAL else SensitiveKind.PHONE,
        source,
    )

    private val a = block("a")
    private val b = block("b", y = 50f)

    @Test
    fun `the first plan seen and plans synced without animation are not animated`() {
        val toggles = MaskToggles()
        toggles.sync(listOf(a), animate = true)
        assertThat(toggles.active).isFalse()
        toggles.sync(listOf(a, b), animate = false)
        assertThat(toggles.active).isFalse()
    }

    @Test
    fun `added items lay down, removed ones peel off, both from the first frame`() {
        val toggles = MaskToggles()
        toggles.sync(listOf(a), animate = true)
        toggles.sync(listOf(b), animate = true)
        // 第一帧之前：新码还没露，拿掉的码还在
        val before = toggles.frame(0L)
        assertThat(before.appearing).containsExactly("b", 0f)
        assertThat(before.vanishing).containsExactly(a to 0f)

        toggles.advance(1_000L)
        toggles.advance(1_000L + MaskToggle.OUT_MS / 2)
        val mid = toggles.frame(1_000L + MaskToggle.OUT_MS / 2)
        assertThat(mid.appearing.getValue("b")).isGreaterThan(0f)
        assertThat(mid.appearing.getValue("b")).isLessThan(1f)

        toggles.advance(1_000L + maxOf(MaskToggle.IN_MS, MaskToggle.OUT_MS))
        assertThat(toggles.active).isFalse()
    }

    @Test
    fun `a box drawn by hand appears at once`() {
        val toggles = MaskToggles()
        toggles.sync(emptyList(), animate = true)
        toggles.sync(listOf(block("m", DetectorSource.MANUAL)), animate = true)
        assertThat(toggles.active).isFalse()
    }

    @Test
    fun `undoing mid-animation stops it`() {
        val toggles = MaskToggles()
        toggles.sync(listOf(a), animate = true)
        toggles.sync(emptyList(), animate = true)
        toggles.sync(listOf(a), animate = true)
        // 揭到一半又回来了：不再揭，改成重新抹上
        val frame = toggles.frame(0L)
        assertThat(frame.vanishing).isEmpty()
        assertThat(frame.appearing.keys).containsExactly("a")
    }

    private fun render(plan: MaskPlan, toggle: ToggleFrame): Bitmap {
        val image = SourceImage(
            Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.WHITE) },
            1f, 200, 100, "image/png",
        )
        val out = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        // 密度 0.1：软边 2.8 像素
        drawScene(Canvas(out), image, plan, RendererRegistry.default(), 1f, 0.1f, null, ScanPaints(), toggle = toggle)
        return out
    }

    @Test
    fun `laying down covers the left side first`() {
        val frame = render(MaskPlan(listOf(a)), ToggleFrame(mapOf("a" to 0.5f), emptyList()))
        assertThat(frame.getPixel(20, 20)).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(frame.getPixel(180, 20)).isEqualTo(Color.WHITE)
    }

    @Test
    fun `peeling off uncovers the left side first`() {
        val frame = render(MaskPlan(emptyList()), ToggleFrame(emptyMap(), listOf(a to 0.5f)))
        assertThat(frame.getPixel(20, 20)).isEqualTo(Color.WHITE)
        assertThat(frame.getPixel(180, 20)).isEqualTo(MaskOptions.SKY_BLUE)
    }
}
