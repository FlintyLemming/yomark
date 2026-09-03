package com.dama.app.ui.canvas

import android.graphics.PointF
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
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
    fun `hitHandle returns null in the middle of the box`() {
        assertThat(SelectionHandles.hitHandle(box, PointF(200f, 150f), 20f)).isNull()
    }

    @Test
    fun `the delete button sits above the top-right corner`() {
        val r = SelectionHandles.deleteButtonRect(box, sizePx = 20f)
        assertThat(r.centerY()).isLessThan(100f)
        assertThat(r.centerX()).isGreaterThan(280f)
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
