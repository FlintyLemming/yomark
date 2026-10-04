package com.youma.app.ui.canvas

import android.graphics.Bitmap
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LabelLayoutTest {

    private val area = RectF(0f, 0f, 1000f, 1000f)
    private val gap = 2f

    private fun place(boxes: List<RectF>, vararg owners: Int, w: Float = 80f, h: Float = 30f) =
        LabelLayout.place(boxes, owners.map { LabelLayout.Request(it, w, h) }, area, gap)

    private fun RectF.overlaps(other: RectF) = RectF.intersects(this, other)

    @Test
    fun `with room to spare the label sits above the top-left corner`() {
        val box = RectF(100f, 200f, 400f, 240f)
        val label = place(listOf(box), 0).single()
        assertThat(label).isEqualTo(RectF(100f, 168f, 180f, 198f))
    }

    @Test
    fun `a label does not sit on the box in the row above`() {
        // 两行上下紧挨的框：下面那行的标签放左上角就压在上面那行的框上
        val upper = RectF(100f, 160f, 300f, 196f)
        val lower = RectF(100f, 200f, 400f, 240f)
        val boxes = listOf(upper, lower)
        val labels = place(boxes, 0, 1)
        labels.forEach { label -> boxes.forEach { assertThat(label.overlaps(it)).isFalse() } }
    }

    @Test
    fun `labels avoid each other`() {
        // 同一行左右挨着的两个框，标签比框宽：都摆左上角的话两张标签叠在一起
        val a = RectF(100f, 200f, 200f, 240f)
        val b = RectF(210f, 200f, 310f, 240f)
        val labels = place(listOf(a, b), 0, 1, w = 200f)
        assertThat(labels[0].overlaps(labels[1])).isFalse()
    }

    @Test
    fun `a label may go to any side of its box`() {
        // 上下都被别的框占满，只剩右边
        val box = RectF(100f, 200f, 200f, 240f)
        val above = RectF(0f, 100f, 1000f, 198f)
        val below = RectF(0f, 242f, 1000f, 400f)
        val left = RectF(0f, 198f, 98f, 242f)
        val boxes = listOf(box, above, below, left)
        val label = place(boxes, 0).single()
        boxes.forEach { assertThat(label.overlaps(it)).isFalse() }
        assertThat(label.left).isAtLeast(box.right)
    }

    @Test
    fun `a label stays inside the image`() {
        val box = RectF(950f, 0f, 1000f, 20f)
        val label = place(listOf(box), 0).single()
        assertThat(area.contains(label)).isTrue()
        assertThat(label.overlaps(box)).isFalse()
    }

    @Test
    fun `when every spot is taken the label picks the least covered one`() {
        val box = RectF(100f, 100f, 200f, 140f)
        val wall = RectF(0f, 0f, 1000f, 1000f)
        val label = place(listOf(box, wall), 0).single()
        // 处处都压着大框，那就至少别压自己的框
        assertThat(label.overlaps(box)).isFalse()
    }

    @Test
    fun `stacked date rows on a scanned form get labels that cover no box`() {
        // 实机截图的情形：手术日期、记录日期两行日期框上下紧挨，上面还有一个打了码的姓名
        val image = SourceImage(Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888), 1f, 600, 400, "image/png")
        fun item(id: String, rect: RectF, state: MaskState) =
            MaskItem(id, Quad.fromRect(rect), SensitiveKind.DATETIME, DetectorSource.RULE, state)
        val items = listOf(
            item("name", RectF(40f, 100f, 70f, 120f), MaskState.MASKED),
            item("surgery", RectF(70f, 124f, 380f, 146f), MaskState.OUTLINED),
            item("record", RectF(70f, 150f, 180f, 172f), MaskState.OUTLINED),
        )
        val labels = layoutKindLabels(items, image, scale = 1f)
        assertThat(labels.keys).containsExactly("surgery", "record")
        labels.values.forEach { label -> items.forEach { assertThat(label.overlaps(it.quad.bounds())).isFalse() } }
        assertThat(labels["surgery"]!!.overlaps(labels["record"]!!)).isFalse()
    }

    @Test
    fun `no labels when zoomed out below the label threshold`() {
        val image = SourceImage(Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888), 1f, 100, 100, "image/png")
        val item = MaskItem("a", Quad.fromRect(RectF(10f, 40f, 90f, 60f)), SensitiveKind.PHONE, DetectorSource.RULE, MaskState.OUTLINED)
        assertThat(layoutKindLabels(listOf(item), image, scale = 0.4f)).isEmpty()
    }
}
