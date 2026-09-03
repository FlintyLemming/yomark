package com.dama.app.ui

import com.dama.app.core.model.MaskPlan

/**
 * 快照式撤销/重做（spec §4.2）。
 * MaskPlan 撑死几十个 item，整个存下来也就几 KB，比命令模式省一半代码，
 * 且不可能出现命令不对称的 bug。换图时调用 clear()。
 */
class UndoStack(private val limit: Int = LIMIT) {

    private val undoStack = ArrayDeque<MaskPlan>()
    private val redoStack = ArrayDeque<MaskPlan>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** 在**修改之前**把当前 plan 压栈。 */
    fun push(snapshot: MaskPlan) {
        undoStack.addLast(snapshot)
        while (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    /** 传入当前 plan，返回上一个快照；已无历史时返回 null。 */
    fun undo(current: MaskPlan): MaskPlan? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        return prev
    }

    fun redo(current: MaskPlan): MaskPlan? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    companion object { const val LIMIT = 50 }
}
