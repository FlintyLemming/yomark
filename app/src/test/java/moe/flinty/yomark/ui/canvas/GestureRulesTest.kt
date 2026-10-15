package moe.flinty.yomark.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GestureRulesTest {

    private fun item(id: String, l: Float, t: Float, r: Float, b: Float) = MaskItem(
        id, Quad.fromRect(RectF(l, t, r, b)),
        SensitiveKind.MANUAL, DetectorSource.MANUAL,
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
    fun `hitTest picks the box drawn last when manual boxes overlap`() {
        val big = item("big", 0f, 0f, 100f, 100f)
        val small = item("small", 40f, 40f, 60f, 60f)
        assertThat(GestureRules.hitTest(listOf(big, small), PointF(50f, 50f))?.candidateId).isEqualTo("small")
        // 后拉的大框盖住了先前的小框：点到的是看得见的那个大框
        assertThat(GestureRules.hitTest(listOf(small, big), PointF(50f, 50f))?.candidateId).isEqualTo("big")
    }

    @Test
    fun `hitTest prefers the smallest detected box, and a manual box over detected ones`() {
        fun detected(id: String, l: Float, t: Float, r: Float, b: Float) =
            item(id, l, t, r, b).copy(kind = SensitiveKind.PHONE, source = DetectorSource.RULE)
        val small = detected("small", 40f, 40f, 60f, 60f)
        val big = detected("big", 0f, 0f, 100f, 100f)
        assertThat(GestureRules.hitTest(listOf(small, big), PointF(50f, 50f))?.candidateId).isEqualTo("small")
        val manual = item("manual", 30f, 30f, 90f, 90f)
        assertThat(GestureRules.hitTest(listOf(manual, small, big), PointF(50f, 50f))?.candidateId).isEqualTo("manual")
    }

    // ---------- 点一下点中了什么（spec §7.3，2026-10-14 修订） ----------

    private fun detected(id: String, l: Float, t: Float, r: Float, b: Float) =
        item(id, l, t, r, b).copy(kind = SensitiveKind.PHONE, source = DetectorSource.RULE)

    private fun line(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    @Test
    fun `a tap on a mask hits the mask, the rest of its line is still a line`() {
        val phone = detected("phone", 60f, 10f, 120f, 30f)
        val row = line(10f, 8f, 300f, 32f)
        assertThat(GestureRules.tapTarget(listOf(phone), listOf(row), PointF(80f, 20f)))
            .isEqualTo(GestureRules.TapTarget.Item(phone))
        assertThat(GestureRules.tapTarget(listOf(phone), listOf(row), PointF(200f, 20f)))
            .isEqualTo(GestureRules.TapTarget.Line(row))
    }

    /** 没打码的字上点：这一行；在一块码上点：那块码，哪怕底下也是一行字。 */
    @Test
    fun `a tap on unmasked text takes its whole line`() {
        val row = line(10f, 8f, 300f, 32f)
        assertThat(GestureRules.tapTarget(emptyList(), listOf(row), PointF(80f, 20f)))
            .isEqualTo(GestureRules.TapTarget.Line(row))
    }

    @Test
    fun `blank space far from any line is nothing`() {
        assertThat(GestureRules.tapTarget(emptyList(), listOf(line(10f, 10f, 100f, 30f)), PointF(50f, 200f), reach = 8f))
            .isNull()
    }

    @Test
    fun `a tap between two lines takes the nearer one within reach`() {
        val upper = line(10f, 10f, 300f, 30f)
        val lower = line(10f, 40f, 300f, 60f)
        assertThat(GestureRules.lineAt(listOf(upper, lower), PointF(100f, 37f), reach = 6f)).isEqualTo(lower)
        assertThat(GestureRules.lineAt(listOf(upper, lower), PointF(100f, 33f), reach = 6f)).isEqualTo(upper)
        assertThat(GestureRules.lineAt(listOf(upper, lower), PointF(100f, 37f), reach = 2f)).isNull()
    }

    @Test
    fun `of two lines under the finger the smaller one wins`() {
        // 斜着压在字上的水印也读成了一行，框比字那一行大得多
        val watermark = line(0f, 0f, 400f, 200f)
        val text = line(10f, 90f, 300f, 110f)
        assertThat(GestureRules.lineAt(listOf(watermark, text), PointF(100f, 100f), reach = 0f)).isEqualTo(text)
    }

    @Test
    fun `a tapped line sits over the detected masks in it`() {
        val phone = detected("phone", 60f, 10f, 120f, 30f)
        val tapped = item("line", 10f, 8f, 300f, 32f).copy(source = DetectorSource.LINE)
        assertThat(GestureRules.hitTest(listOf(tapped, phone), PointF(80f, 20f))?.candidateId).isEqualTo("line")
    }
}
