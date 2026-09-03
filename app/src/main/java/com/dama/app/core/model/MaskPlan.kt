package com.dama.app.core.model

import android.graphics.Color
import android.os.Parcelable
import com.dama.app.core.geometry.Quad
import kotlinx.parcelize.Parcelize

/** 未圈出的区域根本不在 plan 里，因此只有两个枚举值。 */
enum class MaskState { MASKED, OUTLINED }

enum class MaskStyle { SOLID, PIXELATE, BLUR, MARKER, EMOJI, ERASE }

@Parcelize
data class MaskOptions(
    val solidColor: Int = Color.BLACK,
    /** 像素块边长 = 区域短边 / divisor，再受 12px 下限约束（spec §8）。 */
    val pixelBlockDivisor: Int = 8,
    val blurRadiusRatio: Float = 0.08f,
    val markerColor: Int = 0x99FFEB3B.toInt(),
    val emoji: String = "🙂",
) : Parcelable

@Parcelize
data class MaskItem(
    val candidateId: String,
    val quad: Quad,                   // 降采样坐标系；导出时按 1/scale 反算
    val kind: SensitiveKind,
    val source: DetectorSource,
    val state: MaskState,
) : Parcelable

@Parcelize
data class MaskPlan(
    val items: List<MaskItem>,
    val style: MaskStyle,             // 全局，非逐项（spec §7.5）
    val options: MaskOptions,
) : Parcelable {
    /** 已识别但尚未打码的数量。导出拦截（spec §7.4）的触发条件。 */
    val pendingCount: Int get() = items.count { it.state == MaskState.OUTLINED }

    fun toggle(id: String): MaskPlan = copy(
        items = items.map {
            if (it.candidateId != id) it
            else it.copy(state = if (it.state == MaskState.MASKED) MaskState.OUTLINED else MaskState.MASKED)
        }
    )

    /**
     * 只有手动框可以被彻底删除（spec §4.2 的不对称规则）。
     * 规则/人脸/条码命中的候选永不从画面消失——候选一旦消失用户就再也找不回来。
     */
    fun remove(id: String): MaskPlan {
        val target = items.firstOrNull { it.candidateId == id } ?: return this
        if (target.source != DetectorSource.MANUAL) return this
        return copy(items = items.filterNot { it.candidateId == id })
    }

    fun add(item: MaskItem): MaskPlan = copy(items = items + item)

    fun replace(item: MaskItem): MaskPlan = copy(
        items = items.map { if (it.candidateId == item.candidateId) item else it }
    )

    fun maskAll(): MaskPlan = copy(items = items.map { it.copy(state = MaskState.MASKED) })

    /** 拦截对话框的「2 个网址、1 个 IP 地址」就是这个 map 渲染出来的。 */
    fun pendingByKind(): Map<SensitiveKind, Int> =
        items.filter { it.state == MaskState.OUTLINED }
            .groupingBy { it.kind }
            .eachCount()

    fun find(id: String): MaskItem? = items.firstOrNull { it.candidateId == id }

    companion object {
        fun empty(style: MaskStyle = MaskStyle.SOLID) = MaskPlan(emptyList(), style, MaskOptions())
    }
}
