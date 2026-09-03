package com.dama.app.core.model

/**
 * 候选 → 三态编辑项（spec §4.1、§7.2）。
 *
 * enabledByDefault 在这里从「规则表的一列」变成「进编辑器时的初始状态」：
 * true → MASKED（已打码），false → OUTLINED（圈出未打码）。
 * 未被任何候选覆盖的区域根本不进 plan，所以 MaskState 只有两个值。
 */
object MaskPlanFactory {
    fun itemsFrom(candidates: List<Candidate>): List<MaskItem> = candidates.map { c ->
        MaskItem(
            candidateId = c.id,
            quad = c.quad,
            kind = c.kind,
            source = c.source,
            state = if (c.enabledByDefault) MaskState.MASKED else MaskState.OUTLINED,
        )
    }
}
