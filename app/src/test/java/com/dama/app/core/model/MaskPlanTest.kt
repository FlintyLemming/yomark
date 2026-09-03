package com.dama.app.core.model

import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MaskPlanTest {

    private fun item(
        id: String,
        state: MaskState,
        source: DetectorSource = DetectorSource.RULE,
        kind: SensitiveKind = SensitiveKind.URL,
    ) = MaskItem(id, Quad.fromRect(RectF(0f, 0f, 10f, 10f)), kind, source, state)

    private fun plan(vararg items: MaskItem) =
        MaskPlan(items.toList(), MaskStyle.SOLID, MaskOptions())

    @Test
    fun `pendingCount counts only outlined items`() {
        val p = plan(
            item("a", MaskState.MASKED),
            item("b", MaskState.OUTLINED),
            item("c", MaskState.OUTLINED),
        )
        assertThat(p.pendingCount).isEqualTo(2)
    }

    @Test
    fun `toggle flips masked to outlined and back`() {
        val p = plan(item("a", MaskState.MASKED))
        val once = p.toggle("a")
        assertThat(once.items.single().state).isEqualTo(MaskState.OUTLINED)
        assertThat(once.toggle("a").items.single().state).isEqualTo(MaskState.MASKED)
    }

    @Test
    fun `toggle of an unknown id changes nothing`() {
        val p = plan(item("a", MaskState.MASKED))
        assertThat(p.toggle("zzz")).isEqualTo(p)
    }

    @Test
    fun `remove deletes a manual item`() {
        val p = plan(item("m", MaskState.MASKED, DetectorSource.MANUAL, SensitiveKind.MANUAL))
        assertThat(p.remove("m").items).isEmpty()
    }

    @Test
    fun `remove refuses to delete a rule-detected candidate`() {
        // spec 4.2 的不对称规则：规则命中的候选永不从画面消失
        val p = plan(item("r", MaskState.OUTLINED, DetectorSource.RULE))
        assertThat(p.remove("r").items).hasSize(1)
    }

    @Test
    fun `remove refuses to delete face and barcode candidates`() {
        val p = plan(
            item("f", MaskState.MASKED, DetectorSource.FACE, SensitiveKind.FACE),
            item("b", MaskState.MASKED, DetectorSource.BARCODE, SensitiveKind.BARCODE),
        )
        assertThat(p.remove("f").items).hasSize(2)
        assertThat(p.remove("b").items).hasSize(2)
    }

    @Test
    fun `maskAll turns every outlined item masked`() {
        val p = plan(item("a", MaskState.OUTLINED), item("b", MaskState.MASKED))
        val all = p.maskAll()
        assertThat(all.pendingCount).isEqualTo(0)
        assertThat(all.items).hasSize(2)
    }

    @Test
    fun `pendingByKind groups outlined items for the interception dialog`() {
        val p = plan(
            item("a", MaskState.OUTLINED, kind = SensitiveKind.URL),
            item("b", MaskState.OUTLINED, kind = SensitiveKind.URL),
            item("c", MaskState.OUTLINED, kind = SensitiveKind.IP_ADDR),
            item("d", MaskState.MASKED, kind = SensitiveKind.EMAIL),
        )
        assertThat(p.pendingByKind()).containsExactly(
            SensitiveKind.URL, 2,
            SensitiveKind.IP_ADDR, 1,
        )
    }

    @Test
    fun `add appends an item without touching the others`() {
        val p = plan(item("a", MaskState.MASKED))
        val added = p.add(item("b", MaskState.MASKED, DetectorSource.MANUAL, SensitiveKind.MANUAL))
        assertThat(added.items.map { it.candidateId }).containsExactly("a", "b").inOrder()
    }
}
