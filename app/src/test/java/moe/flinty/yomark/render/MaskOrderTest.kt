package moe.flinty.yomark.render

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

/**
 * 预览与导出共用的绘制顺序：读像素的先画（马赛克、模糊、开了近似色的色块），直接上色的后画；同一组里大的先画。
 * 导出在同一张位图上一块接一块地画，顺序反了，读像素的就会把隔壁先画上去的色块读进去。
 */
@RunWith(RobolectricTestRunner::class)
class MaskOrderTest {

    private fun item(id: String, style: MaskStyle, size: Float, near: Boolean = false) = MaskItem(
        id, Quad.fromRect(RectF(0f, 0f, size, size)), SensitiveKind.MANUAL,
        DetectorSource.MANUAL, MaskState.MASKED, MaskLook(style, MaskOptions(solidNear = near)),
    )

    @Test fun `pixel-reading styles go first, then the rest, big before small within each`() {
        val items = listOf(
            item("solid-small", MaskStyle.SOLID, 10f),
            item("near", MaskStyle.SOLID, 20f, near = true),
            item("solid-big", MaskStyle.SOLID, 50f),
            item("blur-big", MaskStyle.BLUR, 40f),
            item("emoji", MaskStyle.EMOJI, 30f),
            item("pixelate-small", MaskStyle.PIXELATE, 5f),
        )
        assertThat(MaskOrder.forDrawing(items).map { it.candidateId }).containsExactly(
            "blur-big", "near", "pixelate-small", "solid-big", "emoji", "solid-small",
        ).inOrder()
    }

    @Test fun `mosaic, blur and near colour read pixels`() {
        assertThat(MaskStyle.entries.filter { MaskOrder.readsPixels(MaskLook(it)) })
            .containsExactly(MaskStyle.PIXELATE, MaskStyle.BLUR)
        assertThat(MaskOrder.readsPixels(MaskLook(MaskStyle.SOLID, MaskOptions(solidNear = true)))).isTrue()
    }
}
