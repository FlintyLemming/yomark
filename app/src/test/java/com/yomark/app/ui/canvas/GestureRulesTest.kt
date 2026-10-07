package com.yomark.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GestureRulesTest {

    private fun item(id: String, l: Float, t: Float, r: Float, b: Float) = MaskItem(
        id, Quad.fromRect(RectF(l, t, r, b)),
        SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED,
    )

    @Test
    fun `movement below touch slop is a tap`() {
        assertThat(GestureRules.isDrag(3f, 4f, touchSlopPx = 8f)).isFalse()
    }

    @Test
    fun `movement beyond touch slop is a drag`() {
        assertThat(GestureRules.isDrag(9f, 0f, touchSlopPx = 8f)).isTrue()
    }

    @Test
    fun `a box thinner than the minimum short edge is discarded`() {
        val thin = Quad.fromRect(RectF(0f, 0f, 100f, 10f))
        assertThat(GestureRules.acceptsBox(thin, minShortEdgePx = 16f)).isFalse()
    }

    @Test
    fun `a box at the minimum short edge is kept`() {
        val ok = Quad.fromRect(RectF(0f, 0f, 100f, 16f))
        assertThat(GestureRules.acceptsBox(ok, minShortEdgePx = 16f)).isTrue()
    }

    @Test
    fun `quadFromDrag normalizes a drag made right-to-left and bottom-to-top`() {
        val q = GestureRules.quadFromDrag(PointF(100f, 80f), PointF(20f, 10f))
        assertThat(q.bounds()).isEqualTo(RectF(20f, 10f, 100f, 80f))
    }

    @Test
    fun `hitTest returns null when nothing is under the point`() {
        assertThat(GestureRules.hitTest(listOf(item("a", 0f, 0f, 10f, 10f)), PointF(50f, 50f))).isNull()
    }

    @Test
    fun `hitTest prefers the smallest item when boxes overlap`() {
        val big = item("big", 0f, 0f, 100f, 100f)
        val small = item("small", 40f, 40f, 60f, 60f)
        val hit = GestureRules.hitTest(listOf(big, small), PointF(50f, 50f))
        assertThat(hit?.candidateId).isEqualTo("small")
    }

    @Test
    fun `label threshold matches the spec`() {
        assertThat(GestureRules.LABEL_MIN_SCALE).isEqualTo(0.5f)
    }
}
