package com.youma.app.core.model

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 人脸与条码进编辑器之后的行为（计划 05 Task 33 Step 6 的前四条）。
 *
 * 这几条本来写成实机人工走查。`MaskPlanFactory` 与 `MaskPlan.remove` 都是
 * 与 kind 无关的纯逻辑，把它们钉在单测里，实机那一趟就只剩「肉眼看一下」，
 * 不再是唯一的验证手段。
 */
@RunWith(RobolectricTestRunner::class)
class RegionMaskItemRulesTest {

    private fun region(id: String, kind: SensitiveKind, source: DetectorSource) =
        Candidate(id, Quad.fromRect(RectF(0f, 0f, 100f, 100f)), kind, source, 0.95f, enabledByDefault = true)

    private val face = region("face-0-0", SensitiveKind.FACE, DetectorSource.FACE)
    private val barcode = region("barcode-0", SensitiveKind.BARCODE, DetectorSource.BARCODE)

    private fun planOf(vararg c: Candidate) =
        MaskPlan(MaskPlanFactory.itemsFrom(c.toList()), MaskStyle.SOLID, MaskOptions())

    @Test
    fun `faces and barcodes enter the editor already masked`() {
        val plan = planOf(face, barcode)
        assertThat(plan.items.map { it.state })
            .containsExactly(MaskState.MASKED, MaskState.MASKED)
        assertThat(plan.pendingCount).isEqualTo(0)
    }

    @Test
    fun `tapping a face toggles between masked and outlined, both ways`() {
        val plan = planOf(face)
        val once = plan.toggle(face.id)
        assertThat(once.find(face.id)!!.state).isEqualTo(MaskState.OUTLINED)
        assertThat(once.toggle(face.id).find(face.id)!!.state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `tapping a barcode toggles between masked and outlined, both ways`() {
        val plan = planOf(barcode)
        val once = plan.toggle(barcode.id)
        assertThat(once.find(barcode.id)!!.state).isEqualTo(MaskState.OUTLINED)
        assertThat(once.toggle(barcode.id).find(barcode.id)!!.state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `neither a face nor a barcode can be deleted`() {
        val plan = planOf(face, barcode)
        assertThat(plan.remove(face.id).items).hasSize(2)
        assertThat(plan.remove(barcode.id).items).hasSize(2)
    }

    @Test
    fun `an outlined face still cannot be deleted and still blocks export`() {
        val plan = planOf(face).toggle(face.id)
        assertThat(plan.pendingCount).isEqualTo(1)
        assertThat(plan.remove(face.id).items).hasSize(1)
    }

    @Test
    fun `only the manual box next to them is removable`() {
        val manual = region("m-1", SensitiveKind.MANUAL, DetectorSource.MANUAL)
        val plan = planOf(face, barcode, manual)
        assertThat(plan.remove(manual.id).items.map { it.candidateId })
            .containsExactly(face.id, barcode.id).inOrder()
    }
}
