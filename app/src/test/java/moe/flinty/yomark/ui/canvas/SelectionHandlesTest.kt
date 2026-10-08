package moe.flinty.yomark.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SelectionHandlesTest {

    private val box = Quad.fromRect(RectF(100f, 100f, 300f, 200f))

    @Test
    fun `there are four handles, one per corner`() {
        assertThat(SelectionHandles.handleRects(box, sizePx = 20f).keys)
            .containsExactlyElementsIn(HandleCorner.entries)
    }

    @Test
    fun `each handle is centred on its corner`() {
        val rects = SelectionHandles.handleRects(box, sizePx = 20f)
        assertThat(rects[HandleCorner.TOP_LEFT]!!.centerX()).isWithin(0.1f).of(100f)
        assertThat(rects[HandleCorner.TOP_LEFT]!!.centerY()).isWithin(0.1f).of(100f)
        assertThat(rects[HandleCorner.BOTTOM_RIGHT]!!.centerX()).isWithin(0.1f).of(300f)
        assertThat(rects[HandleCorner.BOTTOM_RIGHT]!!.centerY()).isWithin(0.1f).of(200f)
    }

    @Test
    fun `hitHandle finds the corner under the finger`() {
        assertThat(SelectionHandles.hitHandle(box, PointF(102f, 98f), 20f)).isEqualTo(HandleCorner.TOP_LEFT)
        assertThat(SelectionHandles.hitHandle(box, PointF(298f, 202f), 20f)).isEqualTo(HandleCorner.BOTTOM_RIGHT)
    }

    @Test
    fun `on a small box with overlapping hit areas, hitHandle picks the nearest corner`() {
        val small = Quad.fromRect(RectF(100f, 100f, 120f, 120f))
        assertThat(SelectionHandles.hitHandle(small, PointF(118f, 119f), 40f)).isEqualTo(HandleCorner.BOTTOM_RIGHT)
        assertThat(SelectionHandles.hitHandle(small, PointF(119f, 101f), 40f)).isEqualTo(HandleCorner.TOP_RIGHT)
    }

    @Test
    fun `hitHandle returns null in the middle of the box`() {
        assertThat(SelectionHandles.hitHandle(box, PointF(200f, 150f), 20f)).isNull()
    }

    // 工具条 100×40，画布 1000×800，与框隔 10、离画布边至少 8
    private fun toolbar(l: Float, t: Float, r: Float, b: Float) =
        SelectionHandles.toolbarPosition(RectF(l, t, r, b), 100, 40, 1000, 800, gap = 10f, margin = 8f)

    @Test
    fun `the toolbar sits above the box, centred on it`() {
        val p = toolbar(300f, 300f, 500f, 400f)!!
        assertThat(p.x).isEqualTo(350)          // 框中心 400 - 工具条半宽 50
        assertThat(p.y).isEqualTo(250)          // 框顶 300 - 间距 10 - 工具条高 40
    }

    @Test
    fun `with no room above, the toolbar goes below the box`() {
        val p = toolbar(300f, 20f, 500f, 400f)!!
        assertThat(p.y).isEqualTo(410)
    }

    @Test
    fun `when the box fills the canvas, the toolbar hugs the top edge`() {
        val p = toolbar(-50f, 10f, 1050f, 790f)!!
        assertThat(p.y).isEqualTo(8)
    }

    @Test
    fun `the toolbar never leaves the canvas sideways`() {
        assertThat(toolbar(-80f, 300f, 20f, 400f)!!.x).isEqualTo(8)
        assertThat(toolbar(980f, 300f, 1100f, 400f)!!.x).isEqualTo(1000 - 8 - 100)
    }

    @Test
    fun `no toolbar for a box panned out of view`() {
        assertThat(toolbar(1100f, 300f, 1200f, 400f)).isNull()
        assertThat(toolbar(300f, -200f, 500f, -10f)).isNull()
    }

    @Test
    fun `resizing the bottom-right corner moves only that corner`() {
        val out = SelectionHandles.resize(box, HandleCorner.BOTTOM_RIGHT, PointF(400f, 260f), minShortEdge = 16f)
        assertThat(out.bounds()).isEqualTo(RectF(100f, 100f, 400f, 260f))
    }

    @Test
    fun `resizing the top-left corner moves only that corner`() {
        val out = SelectionHandles.resize(box, HandleCorner.TOP_LEFT, PointF(60f, 40f), minShortEdge = 16f)
        assertThat(out.bounds()).isEqualTo(RectF(60f, 40f, 300f, 200f))
    }

    @Test
    fun `resizing cannot shrink the box below the minimum short edge`() {
        val out = SelectionHandles.resize(box, HandleCorner.BOTTOM_RIGHT, PointF(105f, 105f), minShortEdge = 16f)
        assertThat(out.shortEdge()).isAtLeast(16f)
    }

    @Test
    fun `dragging past the opposite corner does not invert the box`() {
        val out = SelectionHandles.resize(box, HandleCorner.BOTTOM_RIGHT, PointF(10f, 10f), minShortEdge = 16f)
        assertThat(out.bounds().right).isGreaterThan(out.bounds().left)
        assertThat(out.bounds().bottom).isGreaterThan(out.bounds().top)
    }

    @Test
    fun `move shifts every corner by the same delta`() {
        assertThat(SelectionHandles.move(box, 25f, -10f).bounds())
            .isEqualTo(RectF(125f, 90f, 325f, 190f))
    }
}
