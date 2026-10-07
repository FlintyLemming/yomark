package com.yomark.app.core.model

import android.graphics.RectF
import android.os.Parcel
import com.yomark.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MaskPlan 要能穿过 onSaveInstanceState 的 Bundle（spec §15 第 2 条）。
 * 「不保留活动」下 Activity 被销毁时 ViewModel 一并清空，
 * 状态只能靠 SavedStateHandle 活下来，所以整棵 plan 必须是 Parcelable。
 */
@RunWith(RobolectricTestRunner::class)
class MaskPlanParcelTest {

    private fun <T> roundTrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(value as android.os.Parcelable, 0)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            return parcel.readParcelable<android.os.Parcelable>(javaClass.classLoader) as T
        } finally {
            parcel.recycle()
        }
    }

    private fun plan() = MaskPlan(
        items = listOf(
            MaskItem("rule-card-0-0-0", Quad.fromRect(RectF(1f, 2f, 3f, 4f)),
                SensitiveKind.PAYMENT_CARD, DetectorSource.RULE, MaskState.MASKED),
            MaskItem("manual-abc", Quad.fromRect(RectF(5f, 6f, 7f, 8f)),
                SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.OUTLINED),
        ),
        style = MaskStyle.PIXELATE,
        options = MaskOptions(solidColor = 0x11223344, pixelBlockDivisor = 6, emoji = "🙈"),
    )

    @Test
    fun `a plan survives a parcel round trip unchanged`() {
        val restored = roundTrip(plan())
        assertThat(restored).isEqualTo(plan())
    }

    @Test
    fun `tri-state and kinds survive`() {
        val restored = roundTrip(plan())
        assertThat(restored.items.map { it.state })
            .containsExactly(MaskState.MASKED, MaskState.OUTLINED).inOrder()
        assertThat(restored.items.map { it.kind })
            .containsExactly(SensitiveKind.PAYMENT_CARD, SensitiveKind.MANUAL).inOrder()
        assertThat(restored.pendingCount).isEqualTo(1)
    }

    @Test
    fun `geometry survives to the float`() {
        val restored = roundTrip(plan())
        assertThat(restored.items.first().quad.bounds()).isEqualTo(RectF(1f, 2f, 3f, 4f))
    }

    @Test
    fun `style and options survive`() {
        val restored = roundTrip(plan())
        assertThat(restored.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(restored.options.pixelBlockDivisor).isEqualTo(6)
        assertThat(restored.options.emoji).isEqualTo("🙈")
    }
}
