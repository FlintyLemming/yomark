package moe.flinty.yomark.core.model

import android.graphics.RectF
import android.os.Parcel
import moe.flinty.yomark.core.geometry.Quad
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
                SensitiveKind.PAYMENT_CARD, DetectorSource.RULE,
                MaskLook(MaskStyle.PIXELATE, MaskOptions(solidColor = 0x11223344, pixelBlockDivisor = 6))),
            MaskItem("manual-abc", Quad.fromRect(RectF(5f, 6f, 7f, 8f)),
                SensitiveKind.MANUAL, DetectorSource.MANUAL,
                MaskLook(MaskStyle.MARKER, MaskOptions(markerColor = MaskOptions.withAlpha(0xFF448AFF.toInt(), 0.4f)))),
        ),
    )

    @Test
    fun `a plan survives a parcel round trip unchanged`() {
        val restored = roundTrip(plan())
        assertThat(restored).isEqualTo(plan())
    }

    @Test
    fun `kinds and sources survive`() {
        val restored = roundTrip(plan())
        assertThat(restored.items.map { it.kind })
            .containsExactly(SensitiveKind.PAYMENT_CARD, SensitiveKind.MANUAL).inOrder()
        assertThat(restored.items.map { it.source })
            .containsExactly(DetectorSource.RULE, DetectorSource.MANUAL).inOrder()
    }

    @Test
    fun `geometry survives to the float`() {
        val restored = roundTrip(plan())
        assertThat(restored.items.first().quad.bounds()).isEqualTo(RectF(1f, 2f, 3f, 4f))
    }

    @Test
    fun `each item keeps its own style and options`() {
        val restored = roundTrip(plan())
        val (card, manual) = restored.items
        assertThat(card.look.style).isEqualTo(MaskStyle.PIXELATE)
        assertThat(card.look.options.pixelBlockDivisor).isEqualTo(6)
        assertThat(manual.look.style).isEqualTo(MaskStyle.MARKER)
        assertThat(manual.look.options.markerColor).isEqualTo(MaskOptions.withAlpha(0xFF448AFF.toInt(), 0.4f))
    }

    /** 顶上要裁掉的那一条也跟着 plan 存下来，点掉了（留着状态栏）的也是。 */
    @Test
    fun `the crop survives, on or off`() {
        listOf(TopCrop(61.5f), TopCrop(61.5f, on = false)).forEach { crop ->
            assertThat(roundTrip(plan().copy(crop = crop))).isEqualTo(plan().copy(crop = crop))
        }
    }
}
