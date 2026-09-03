package com.dama.app.engine

import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.Candidate
import com.dama.app.core.model.DetectorSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 候选合并（spec §5.4）。
 *
 * 1. IoU > 0.6 的候选按 source 优先级去重（RULE > ENTITY_MODEL > LLM）；
 * 2. 同一 kind 且水平间距小于行高 0.5 倍的相邻候选合并；
 * 3. 按面积降序输出，让 UI 先渲染大块。
 */
class CandidateMerger(
    private val iouThreshold: Float = 0.6f,
    private val gapRatio: Float = 0.5f,
) {

    fun merge(candidates: List<Candidate>): List<Candidate> {
        if (candidates.isEmpty()) return emptyList()
        val deduped = dedupe(candidates)
        val joined = joinAdjacent(deduped)
        return joined.sortedByDescending { it.quad.area() }
    }

    private fun dedupe(input: List<Candidate>): List<Candidate> {
        val kept = ArrayList<Candidate>()
        // 优先级高的先进，后来的重叠者直接丢
        input.sortedWith(
            compareBy<Candidate> { priority(it.source) }
                .thenByDescending { it.confidence }
                .thenByDescending { it.quad.area() }
        ).forEach { cand ->
            val clash = kept.firstOrNull { comparable(it, cand) && it.quad.iou(cand.quad) > iouThreshold }
            if (clash == null) {
                kept += cand
            } else {
                // 被丢掉的那个如果默认打码，把这一位传给保留者——绝不降级
                if (cand.enabledByDefault && !clash.enabledByDefault) {
                    kept[kept.indexOf(clash)] = clash.copy(enabledByDefault = true)
                }
            }
        }
        return kept
    }

    /**
     * 两个候选之间「去重」才有意义的前提：**同一个 kind**。
     *
     * 计划原本只按 IoU 去重、完全不看 kind，那会让重叠的不同类型候选互相顶掉。
     * 真实场景：条码下方印着同一串快递单号，两者 IoU 轻松超过 0.6，
     * 条码候选于是被静默吃掉。漏检是事故，误报只是麻烦。
     *
     * 去重的本意是「两个检测器对同一处的**同一件事**给出不同判断」——
     * 比如 RULE 与 ENTITY_MODEL 都认为这里是支付卡，那只留优先级高的。
     * 类型不同就不是同一件事，两个都留着，让用户各自看到。
     * 这也和 canJoin 的口径一致：那里同样要求 kind 相同才合并。
     */
    private fun comparable(a: Candidate, b: Candidate): Boolean = a.kind == b.kind

    private fun joinAdjacent(input: List<Candidate>): List<Candidate> {
        val remaining = input.toMutableList()
        val out = ArrayList<Candidate>()
        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var merged = true
            while (merged) {
                merged = false
                val it = remaining.iterator()
                while (it.hasNext()) {
                    val other = it.next()
                    if (canJoin(current, other)) {
                        current = join(current, other)
                        it.remove()
                        merged = true
                    }
                }
            }
            out += current
        }
        return out
    }

    private fun canJoin(a: Candidate, b: Candidate): Boolean {
        if (a.kind != b.kind) return false
        if (a.source in REGION_SOURCES || b.source in REGION_SOURCES) return false  // 人脸/条码不参与合并
        val ra = a.quad.bounds()
        val rb = b.quad.bounds()
        val lineHeight = max(ra.height(), rb.height())
        // 同一行：垂直中心相差不超过行高的一半
        if (abs(ra.centerY() - rb.centerY()) > lineHeight * 0.5f) return false
        val gap = max(0f, max(ra.left, rb.left) - min(ra.right, rb.right))
        return gap < lineHeight * gapRatio
    }

    private fun join(a: Candidate, b: Candidate): Candidate {
        val r = RectF(a.quad.bounds()).apply { union(b.quad.bounds()) }
        return a.copy(
            id = "${a.id}+${b.id}",
            quad = Quad.fromRect(r),
            confidence = max(a.confidence, b.confidence),
            // 取「或」：合并绝不把默认打码的降级成仅圈出
            enabledByDefault = a.enabledByDefault || b.enabledByDefault,
        )
    }

    private fun priority(source: DetectorSource): Int = when (source) {
        DetectorSource.RULE, DetectorSource.FACE, DetectorSource.BARCODE, DetectorSource.MANUAL -> 0
        DetectorSource.ENTITY_MODEL -> 1
        DetectorSource.LLM -> 2
    }

    private companion object {
        val REGION_SOURCES = setOf(DetectorSource.FACE, DetectorSource.BARCODE)
    }
}
