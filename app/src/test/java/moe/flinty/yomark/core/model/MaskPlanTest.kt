package moe.flinty.yomark.core.model

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
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

    private fun plan(vararg items: MaskItem) = MaskPlan(items.toList())

    private val emoji = MaskLook(MaskStyle.EMOJI, MaskOptions(emoji = "🐱"))

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
        val once = p.toggle("a", MaskLook())
        assertThat(once.items.single().state).isEqualTo(MaskState.OUTLINED)
        assertThat(once.toggle("a", MaskLook()).items.single().state).isEqualTo(MaskState.MASKED)
    }

    /** 换了样式再点虚线框，打上的是新样式，不是它上一次打码时的样子（spec §7.5 修订）。 */
    @Test
    fun `masking an outlined item by toggle takes the given look`() {
        val p = plan(item("a", MaskState.MASKED)).toggle("a", emoji)
        assertThat(p.items.single().look).isEqualTo(MaskLook())     // 退回圈出时不动
        val again = p.toggle("a", emoji)
        assertThat(again.items.single().state).isEqualTo(MaskState.MASKED)
        assertThat(again.items.single().look).isEqualTo(emoji)
    }

    @Test
    fun `toggle of an unknown id changes nothing`() {
        val p = plan(item("a", MaskState.MASKED))
        assertThat(p.toggle("zzz", emoji)).isEqualTo(p)
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
        val all = p.maskAll(MaskLook())
        assertThat(all.pendingCount).isEqualTo(0)
        assertThat(all.items).hasSize(2)
    }

    @Test
    fun `maskAll gives the given look only to the items it masks`() {
        val p = plan(item("a", MaskState.OUTLINED), item("b", MaskState.MASKED))
        val all = p.maskAll(emoji)
        assertThat(all.find("a")!!.look).isEqualTo(emoji)
        assertThat(all.find("b")!!.look).isEqualTo(MaskLook())       // 已经打好的码保持原样
    }

    @Test
    fun `restyle changes one item and restyleMasked every masked one`() {
        val p = plan(item("a", MaskState.MASKED), item("b", MaskState.MASKED), item("c", MaskState.OUTLINED))
        assertThat(p.restyle("a", emoji).items.map { it.look }).containsExactly(emoji, MaskLook(), MaskLook()).inOrder()
        assertThat(p.restyleMasked(emoji).items.map { it.look }).containsExactly(emoji, emoji, MaskLook()).inOrder()
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
