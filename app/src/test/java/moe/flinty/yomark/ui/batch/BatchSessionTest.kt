package moe.flinty.yomark.ui.batch

import android.graphics.RectF
import android.net.Uri
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class BatchSessionTest {

    private fun uri(n: Int) = Uri.parse("content://test/$n")

    private fun session(count: Int) = BatchSession(items = (1..count).map { BatchItem(uri(it)) }, index = 0)

    private fun planWithPending(n: Int) = MaskPlan(
        items = (1..n).map {
            MaskItem("p$it", Quad.fromRect(RectF(0f, 0f, 10f, 10f)),
                SensitiveKind.URL, DetectorSource.RULE, MaskState.OUTLINED)
        },
    )

    private fun BatchSession.open(i: Int) = withLoaded(i, File("src-$i.jpg"), "image/jpeg", 1f)

    @Test fun `total is the number of items`() {
        assertThat(session(3).total).isEqualTo(3)
    }

    @Test fun `any image can be selected, in any order`() {
        val s = session(4).select(3).select(1)
        assertThat(s.index).isEqualTo(1)
        assertThat(s.current.uri).isEqualTo(uri(2))
    }

    @Test fun `selecting outside the batch stays inside it`() {
        assertThat(session(3).select(7).index).isEqualTo(2)
        assertThat(session(3).select(-1).index).isEqualTo(0)
    }

    @Test fun `an image counts as opened once it has loaded`() {
        val s = session(3).open(0).open(2)
        assertThat(s.items.map { it.opened }).containsExactly(true, false, true).inOrder()
        assertThat(s.opened).isEqualTo(2)
        assertThat(s.unopened).isEqualTo(1)
        assertThat(s.firstUnopened).isEqualTo(1)
    }

    @Test fun `loading keeps the edits an image already has`() {
        val s = session(2).withPlanAt(1, planWithPending(2)).open(1)
        assertThat(s.items[1].plan!!.pendingCount).isEqualTo(2)
    }

    @Test fun `a copied but never decoded image is not opened yet`() {
        val s = session(2).withFile(1, File("src-1.jpg"), "image/png")
        assertThat(s.items[1].opened).isFalse()
        assertThat(s.items[1].mimeType).isEqualTo("image/png")
    }

    @Test fun `an image that failed to open is not asked about again`() {
        val s = session(3).open(0).withFailed(1)
        assertThat(s.unopened).isEqualTo(1)
        assertThat(s.firstUnopened).isEqualTo(2)
    }

    @Test fun `opening again after a failure clears it`() {
        val s = session(2).open(0).withFailed(1).open(1)
        assertThat(s.items[1].failed).isFalse()
        assertThat(s.unopened).isEqualTo(0)
    }

    @Test fun `firstUnopened is null once every image has been opened`() {
        assertThat(session(2).open(0).open(1).firstUnopened).isNull()
    }

    @Test fun `withPlan stores the plan on the current item only`() {
        val s = session(2).select(1).withPlan(planWithPending(1))
        assertThat(s.items[0].plan).isNull()
        assertThat(s.items[1].plan).isNotNull()
    }

    @Test fun `withPlanAt can store a plan on an image that is not on screen`() {
        val s = session(3).withPlanAt(2, planWithPending(1))
        assertThat(s.index).isEqualTo(0)
        assertThat(s.items[2].plan!!.pendingCount).isEqualTo(1)
    }

    @Test fun `totalPending sums pending across every image`() {
        val s = session(3)
            .withPlanAt(0, planWithPending(2))
            .withPlanAt(1, planWithPending(1))
            .withPlanAt(2, planWithPending(0))
        assertThat(s.totalPending).isEqualTo(3)
    }

    @Test fun `an item with no plan yet contributes nothing to totalPending`() {
        assertThat(session(3).withPlan(planWithPending(2)).totalPending).isEqualTo(2)
    }

    @Test fun `maskAllEverywhere clears pending on every stored plan`() {
        val s = session(2).withPlanAt(0, planWithPending(2)).withPlanAt(1, planWithPending(3))
        assertThat(s.maskAllEverywhere(MaskLook()).totalPending).isEqualTo(0)
    }

    @Test fun `maskAllEverywhere masks with the given look on every image`() {
        val look = MaskLook(MaskStyle.MARKER, MaskOptions(markerColor = 0x66FF4081))
        val s = session(2).withPlanAt(0, planWithPending(1)).withPlanAt(1, planWithPending(2))
        val looks = s.maskAllEverywhere(look).items.flatMap { it.plan!!.items }.map { it.look }
        assertThat(looks).containsExactly(look, look, look)
    }

    @Test fun `maskAllEverywhere leaves unopened images unopened`() {
        val s = session(2).withPlanAt(0, planWithPending(1)).maskAllEverywhere(MaskLook())
        assertThat(s.items[1].plan).isNull()
    }
}
