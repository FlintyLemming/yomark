package moe.flinty.yomark.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 预览与导出共用的绘制顺序：识别出的在底下、同一批里大的先画，手动框按画的先后盖上去。
 * 在色块上再拉一个模糊框，模糊要在色块上面。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskOrderTest {

    private fun item(
        id: String, style: MaskStyle, size: Float, near: Boolean = false,
        source: DetectorSource = DetectorSource.MANUAL,
    ) = MaskItem(
        id, Quad.fromRect(RectF(0f, 0f, size, size)),
        if (source == DetectorSource.MANUAL) SensitiveKind.MANUAL else SensitiveKind.PHONE,
        source, MaskState.MASKED, MaskLook(style, MaskOptions(solidNear = near)),
    )

    @Test fun `manual boxes stack in the order they were drawn, whatever their style`() {
        val items = listOf(
            item("solid-small", MaskStyle.SOLID, 10f),
            item("blur-big", MaskStyle.BLUR, 40f),
            item("marker", MaskStyle.MARKER, 30f),
            item("pixelate-small", MaskStyle.PIXELATE, 5f),
        )
        assertThat(MaskOrder.forDrawing(items).map { it.candidateId }).containsExactly(
            "solid-small", "blur-big", "marker", "pixelate-small",
        ).inOrder()
    }

    @Test fun `detected boxes lie under the manual ones, big before small`() {
        val items = listOf(
            item("manual", MaskStyle.BLUR, 5f),
            item("rule-small", MaskStyle.SOLID, 10f, source = DetectorSource.RULE),
            item("rule-big", MaskStyle.SOLID, 50f, source = DetectorSource.RULE),
        )
        assertThat(MaskOrder.forDrawing(items).map { it.candidateId }).containsExactly(
            "rule-big", "rule-small", "manual",
        ).inOrder()
    }

    @Test fun `mosaic and blur read pixels, near colour is picked before drawing`() {
        assertThat(MaskStyle.entries.filter { MaskOrder.readsPixels(MaskLook(it)) })
            .containsExactly(MaskStyle.PIXELATE, MaskStyle.BLUR)
        assertThat(MaskOrder.readsPixels(MaskLook(MaskStyle.SOLID, MaskOptions(solidNear = true)))).isFalse()
    }

    @Test fun `pinning near colour reads the original and leaves a fixed colour`() {
        val original = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val quad = Quad.fromRect(RectF(40f, 40f, 60f, 60f))
        val pinned = MaskOrder.pinNearColor(MaskOptions(solidNear = true), original, quad)
        assertThat(pinned.solidNear).isFalse()
        assertThat(pinned.solidColor).isEqualTo(Color.WHITE)
        val fixed = MaskOptions(solidColor = Color.RED)
        assertThat(MaskOrder.pinNearColor(fixed, original, quad)).isSameInstanceAs(fixed)
    }
}
