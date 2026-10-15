package moe.flinty.yomark.core.model

/**
 * 候选 → 码（spec §4.1）。要打码的（enabledByDefault）进 plan，其余的认出来了也不打码，不进 plan：
 * 2026-10-14 起没有「圈出」这一档，识别结果要么直接打码，要么不打码。
 *
 * [look] 是结果到达那一刻编辑器「打码」里的样式：识别期间在「打码」里换了样式，打上的码就是新样式。
 */
object MaskPlanFactory {
    fun itemsFrom(candidates: List<Candidate>, look: MaskLook = MaskLook()): List<MaskItem> =
        candidates.filter { it.enabledByDefault }.map { c ->
            MaskItem(
                candidateId = c.id,
                quad = c.quad,
                kind = c.kind,
                source = c.source,
                look = look,
            )
        }
}
