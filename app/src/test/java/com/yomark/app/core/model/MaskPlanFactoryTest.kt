package com.yomark.app.core.model

import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskPlanFactoryTest {

    private fun candidate(id: String, enabled: Boolean, kind: SensitiveKind = SensitiveKind.EMAIL) =
        Candidate(id, Quad.fromRect(RectF(0f, 0f, 10f, 10f)), kind, DetectorSource.RULE, 0.9f, enabled)

    @Test
    fun `enabledByDefault true becomes MASKED`() {
        val item = MaskPlanFactory.itemsFrom(listOf(candidate("a", true))).single()
        assertThat(item.state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `enabledByDefault false becomes OUTLINED`() {
        val item = MaskPlanFactory.itemsFrom(listOf(candidate("b", false))).single()
        assertThat(item.state).isEqualTo(MaskState.OUTLINED)
    }

    @Test
    fun `identity fields carry over unchanged`() {
        val c = candidate("c", true, SensitiveKind.PAYMENT_CARD)
        val item = MaskPlanFactory.itemsFrom(listOf(c)).single()
        assertThat(item.candidateId).isEqualTo("c")
        assertThat(item.kind).isEqualTo(SensitiveKind.PAYMENT_CARD)
        assertThat(item.source).isEqualTo(DetectorSource.RULE)
        assertThat(item.quad).isEqualTo(c.quad)
    }

    @Test
    fun `order is preserved so big regions stay first`() {
        val items = MaskPlanFactory.itemsFrom(listOf(candidate("1", true), candidate("2", false)))
        assertThat(items.map { it.candidateId }).containsExactly("1", "2").inOrder()
    }

    @Test
    fun `every item carries the look it was given`() {
        val look = MaskLook(MaskStyle.BLUR, MaskOptions(blurRadiusRatio = 0.2f))
        val items = MaskPlanFactory.itemsFrom(listOf(candidate("1", true), candidate("2", false)), look)
        assertThat(items.map { it.look }).containsExactly(look, look)
    }

    @Test
    fun `empty candidates yield an empty item list`() {
        assertThat(MaskPlanFactory.itemsFrom(emptyList())).isEmpty()
    }
}
