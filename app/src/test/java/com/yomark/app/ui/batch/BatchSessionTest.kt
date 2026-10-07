package com.yomark.app.ui.batch

import android.graphics.RectF
import android.net.Uri
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskLook
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BatchSessionTest {

    private fun uri(n: Int) = Uri.parse("content://test/$n")

    private fun session(count: Int) =
        BatchSession(items = (1..count).map { BatchItem(uri(it), null, "image/jpeg", null, 1f) }, index = 0)

    private fun planWithPending(n: Int) = MaskPlan(
        items = (1..n).map {
            MaskItem("p$it", Quad.fromRect(RectF(0f, 0f, 10f, 10f)),
                SensitiveKind.URL, DetectorSource.RULE, MaskState.OUTLINED)
        },
    )

    @Test fun `total is the number of items`() {
        assertThat(session(3).total).isEqualTo(3)
    }

    @Test fun `current points at the indexed item`() {
        val s = session(3).advance()
        assertThat(s.current.uri).isEqualTo(uri(2))
    }

    @Test fun `isLast is true only on the final item`() {
        var s = session(2)
        assertThat(s.isLast).isFalse()
        s = s.advance()
        assertThat(s.isLast).isTrue()
    }

    @Test fun `advancing past the end is a no-op`() {
        val s = session(1).advance().advance()
        assertThat(s.index).isEqualTo(0)
    }

    @Test fun `withPlan stores the plan on the current item only`() {
        val s = session(2).withPlan(planWithPending(1))
        assertThat(s.items[0].plan).isNotNull()
        assertThat(s.items[1].plan).isNull()
    }

    @Test fun `totalPending sums pending across every image`() {
        val s = session(3)
            .withPlan(planWithPending(2)).advance()
            .withPlan(planWithPending(1)).advance()
            .withPlan(planWithPending(0))
        assertThat(s.totalPending).isEqualTo(3)
    }

    @Test fun `an item with no plan yet contributes nothing to totalPending`() {
        assertThat(session(3).withPlan(planWithPending(2)).totalPending).isEqualTo(2)
    }

    @Test fun `maskAllEverywhere clears pending on every stored plan`() {
        val s = session(2).withPlan(planWithPending(2)).advance().withPlan(planWithPending(3))
        assertThat(s.maskAllEverywhere(MaskLook()).totalPending).isEqualTo(0)
    }

    @Test fun `maskAllEverywhere masks with the given look on every image`() {
        val look = MaskLook(MaskStyle.EMOJI, MaskOptions(emoji = "🐱"))
        val s = session(2).withPlan(planWithPending(1)).advance().withPlan(planWithPending(2))
        val looks = s.maskAllEverywhere(look).items.flatMap { it.plan!!.items }.map { it.look }
        assertThat(looks).containsExactly(look, look, look)
    }
}
