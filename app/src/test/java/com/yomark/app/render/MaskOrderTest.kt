package com.yomark.app.render

import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskLook
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 预览与导出共用的绘制顺序：读像素的样式先画，直接上色的后画；同一组里大的先画。
 * 导出在同一张位图上一块接一块地画，顺序反了，读像素的样式就会把隔壁先画上去的色块读进去。
 */
@RunWith(RobolectricTestRunner::class)
class MaskOrderTest {

    private fun item(id: String, style: MaskStyle, size: Float) = MaskItem(
        id, Quad.fromRect(RectF(0f, 0f, size, size)), SensitiveKind.MANUAL,
        DetectorSource.MANUAL, MaskState.MASKED, MaskLook(style),
    )

    @Test fun `pixel-reading styles go first, then the rest, big before small within each`() {
        val items = listOf(
            item("solid-small", MaskStyle.SOLID, 10f),
            item("erase", MaskStyle.ERASE, 20f),
            item("solid-big", MaskStyle.SOLID, 50f),
            item("blur-big", MaskStyle.BLUR, 40f),
            item("emoji", MaskStyle.EMOJI, 30f),
            item("pixelate-small", MaskStyle.PIXELATE, 5f),
        )
        assertThat(MaskOrder.forDrawing(items).map { it.candidateId }).containsExactly(
            "blur-big", "erase", "pixelate-small", "solid-big", "emoji", "solid-small",
        ).inOrder()
    }

    @Test fun `only erase, mosaic and blur read pixels`() {
        assertThat(MaskStyle.entries.filter { MaskOrder.readsPixels(it) })
            .containsExactly(MaskStyle.ERASE, MaskStyle.PIXELATE, MaskStyle.BLUR)
    }
}
