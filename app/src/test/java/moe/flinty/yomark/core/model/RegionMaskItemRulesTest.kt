package moe.flinty.yomark.core.model

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 人脸与条码进编辑器之后的行为（计划 05 Task 33 Step 6 的前四条）。
 *
 * 这几条本来写成实机人工走查。`MaskPlanFactory` 与 `MaskPlan.remove` 都是
 * 与 kind 无关的纯逻辑，把它们钉在单测里，实机那一趟就只剩「肉眼看一下」，
 * 不再是唯一的验证手段。2026-10-14 起没有「圈出」：点一下就是拿掉，撤销能回来。
 */
@RunWith(RobolectricTestRunner::class)
class RegionMaskItemRulesTest {

    private fun region(id: String, kind: SensitiveKind, source: DetectorSource) =
        Candidate(id, Quad.fromRect(RectF(0f, 0f, 100f, 100f)), kind, source, 0.95f, enabledByDefault = true)

    private val face = region("face-0-0", SensitiveKind.FACE, DetectorSource.FACE)
    private val barcode = region("barcode-0", SensitiveKind.BARCODE, DetectorSource.BARCODE)

    private fun planOf(vararg c: Candidate) = MaskPlan(MaskPlanFactory.itemsFrom(c.toList()))

    @Test
    fun `faces and barcodes enter the editor masked`() {
        assertThat(planOf(face, barcode).items.map { it.candidateId }).containsExactly(face.id, barcode.id).inOrder()
    }

    @Test
    fun `a face or a barcode taken off leaves the rest`() {
        val manual = region("m-1", SensitiveKind.MANUAL, DetectorSource.MANUAL)
        val plan = planOf(face, barcode, manual)
        assertThat(plan.remove(face.id).items.map { it.candidateId }).containsExactly(barcode.id, manual.id).inOrder()
        assertThat(plan.remove(barcode.id).items.map { it.candidateId }).containsExactly(face.id, manual.id).inOrder()
    }
}
