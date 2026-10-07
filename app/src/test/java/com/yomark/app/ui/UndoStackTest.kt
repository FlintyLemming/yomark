package com.yomark.app.ui

import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.DetectorSource
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskLook
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.core.model.MaskState
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UndoStackTest {

    /** 一块用 [style] 打的码：不同样式的 plan 彼此不相等，正好拿来区分快照。 */
    private fun planOf(style: MaskStyle) = MaskPlan(
        listOf(
            MaskItem(
                "m", Quad.fromRect(RectF(0f, 0f, 10f, 10f)), SensitiveKind.MANUAL,
                DetectorSource.MANUAL, MaskState.MASKED, MaskLook(style),
            )
        )
    )

    @Test
    fun `fresh stack can neither undo nor redo`() {
        val s = UndoStack()
        assertThat(s.canUndo).isFalse()
        assertThat(s.canRedo).isFalse()
    }

    @Test
    fun `undo returns the pushed snapshot`() {
        val s = UndoStack()
        val a = planOf(MaskStyle.SOLID)
        val b = planOf(MaskStyle.BLUR)
        s.push(a)
        assertThat(s.undo(b)).isEqualTo(a)
    }

    @Test
    fun `redo returns the state that was undone`() {
        val s = UndoStack()
        val a = planOf(MaskStyle.SOLID)
        val b = planOf(MaskStyle.BLUR)
        s.push(a)
        s.undo(b)
        assertThat(s.redo(a)).isEqualTo(b)
    }

    @Test
    fun `a new push clears the redo stack`() {
        val s = UndoStack()
        s.push(planOf(MaskStyle.SOLID))
        s.undo(planOf(MaskStyle.BLUR))
        assertThat(s.canRedo).isTrue()
        s.push(planOf(MaskStyle.MARKER))
        assertThat(s.canRedo).isFalse()
    }

    @Test
    fun `undo on an empty stack returns null`() {
        assertThat(UndoStack().undo(planOf(MaskStyle.SOLID))).isNull()
    }

    @Test
    fun `stack drops the oldest snapshot beyond the limit`() {
        val s = UndoStack(limit = 3)
        val plans = List(5) { planOf(MaskStyle.entries[it % MaskStyle.entries.size]) }
        plans.forEach { s.push(it) }
        // 只保留最近 3 个，连续 undo 3 次后就没得撤了
        var cur = planOf(MaskStyle.SOLID)
        repeat(3) { cur = s.undo(cur)!! }
        assertThat(s.canUndo).isFalse()
    }

    @Test
    fun `clear empties both stacks`() {
        val s = UndoStack()
        s.push(planOf(MaskStyle.SOLID))
        s.undo(planOf(MaskStyle.BLUR))
        s.clear()
        assertThat(s.canUndo).isFalse()
        assertThat(s.canRedo).isFalse()
    }
}
