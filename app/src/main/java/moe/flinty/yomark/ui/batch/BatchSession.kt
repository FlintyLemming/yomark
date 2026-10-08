package moe.flinty.yomark.ui.batch

import android.net.Uri
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskPlan
import java.io.File

data class BatchItem(
    val uri: Uri,
    /** 私有副本；尚未载入的项为 null。 */
    val file: File?,
    val mimeType: String,
    /** 用户编辑过的 plan；尚未载入的项为 null。 */
    val plan: MaskPlan?,
    val analysisScale: Float,
)

/**
 * 批量会话（spec §7.6）。
 *
 * **不做无人值守批量。** 逐张进入同一个编辑器，底栏「下一张」，走完最后一张统一导出。
 *
 * 理由：三态模型的全部价值在于人眼过一遍那些仅圈出的候选。无人值守批量只能二选一——
 * 要么把 OUTLINED 全部打码（等于取消分层，回到过度遮挡），要么全部不打码（静默漏检）。
 * 两个都不可接受，所以这个功能形态本身不成立。
 */
data class BatchSession(val items: List<BatchItem>, val index: Int) {

    val total: Int get() = items.size
    val current: BatchItem get() = items[index]
    val isLast: Boolean get() = index == items.lastIndex

    /** 导出拦截在批量下看**全部**图片的待打码数之和，不是只看当前这张。 */
    val totalPending: Int get() = items.sumOf { it.plan?.pendingCount ?: 0 }

    fun withPlan(plan: MaskPlan): BatchSession =
        copy(items = items.mapIndexed { i, item -> if (i == index) item.copy(plan = plan) else item })

    fun withLoaded(file: File, mimeType: String, scale: Float): BatchSession =
        copy(items = items.mapIndexed { i, item ->
            if (i == index) item.copy(file = file, mimeType = mimeType, analysisScale = scale) else item
        })

    fun advance(): BatchSession = if (isLast) this else copy(index = index + 1)

    /** 拦截对话框的「全部打码」：每一张的圈出项都用 [look]（编辑器当下的画笔）打上码。 */
    fun maskAllEverywhere(look: MaskLook): BatchSession =
        copy(items = items.map { it.copy(plan = it.plan?.maskAll(look)) })
}
