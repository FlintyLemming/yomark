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
        source: DetectorSource = DetectorSource.RULE,
        kind: SensitiveKind = SensitiveKind.URL,
    ) = MaskItem(id, Quad.fromRect(RectF(0f, 0f, 10f, 10f)), kind, source)

    private fun plan(vararg items: MaskItem) = MaskPlan(items.toList())

    private val marker = MaskLook(MaskStyle.MARKER, MaskOptions(markerColor = 0x66FF4081))

    @Test
    fun `remove takes off a manual box`() {
        val p = plan(item("m", DetectorSource.MANUAL, SensitiveKind.MANUAL))
        assertThat(p.remove("m").items).isEmpty()
    }

    /** 点一下识别出的码就是拿掉它（2026-10-14 起没有「圈出」），人脸、条码也一样。 */
    @Test
    fun `remove takes off a detected mask and leaves the others`() {
        val p = plan(
            item("r"),
            item("f", DetectorSource.FACE, SensitiveKind.FACE),
            item("b", DetectorSource.BARCODE, SensitiveKind.BARCODE),
            item("line", DetectorSource.LINE, SensitiveKind.MANUAL),
        )
        assertThat(p.remove("f").items.map { it.candidateId }).containsExactly("r", "b", "line").inOrder()
        assertThat(p.remove("line").items.map { it.candidateId }).containsExactly("r", "f", "b").inOrder()
    }

    @Test
    fun `remove of an unknown id changes nothing`() {
        val p = plan(item("a"))
        assertThat(p.remove("zzz")).isEqualTo(p)
    }

    @Test
    fun `restyle changes one item`() {
        val p = plan(item("a"), item("b"), item("c"))
        assertThat(p.restyle("a", marker).items.map { it.look }).containsExactly(marker, MaskLook(), MaskLook()).inOrder()
    }

    @Test
    fun `restyleRedactions changes the redactions and leaves strokes`() {
        val blur = MaskLook(MaskStyle.BLUR)
        val p = plan(
            item("a"),
            item("b").copy(look = MaskLook(MaskStyle.PIXELATE)),
            item("m").copy(look = marker),
        )
        assertThat(p.restyleRedactions(blur).items.map { it.look })
            .containsExactly(blur, blur, marker).inOrder()
    }

    @Test
    fun `add appends an item without touching the others`() {
        val p = plan(item("a"))
        val added = p.add(item("b", DetectorSource.MANUAL, SensitiveKind.MANUAL))
        assertThat(added.items.map { it.candidateId }).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `tapped lines and manual boxes are added by the user, detected masks are not`() {
        assertThat(item("m", DetectorSource.MANUAL, SensitiveKind.MANUAL).byUser).isTrue()
        assertThat(item("line", DetectorSource.LINE, SensitiveKind.MANUAL).byUser).isTrue()
        assertThat(item("r").byUser).isFalse()
        assertThat(item("ai", DetectorSource.LLM).byUser).isFalse()
    }
}
