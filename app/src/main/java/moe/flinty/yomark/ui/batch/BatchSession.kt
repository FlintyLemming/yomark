package moe.flinty.yomark.ui.batch

import android.net.Uri
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskPlan
import java.io.File

data class BatchItem(
    val uri: Uri,
    /** 私有副本；还没打开过的项为 null。 */
    val file: File? = null,
    val mimeType: String = "image/jpeg",
    /** 这张图上的 plan。打开过（载入成功、开始识别）就不是 null，没打开过的为 null。 */
    val plan: MaskPlan? = null,
    val analysisScale: Float = 1f,
    /** 上一次打开时没打开成。不算「没看过」：导出前不为它拦一次，再点到它时还会重试。 */
    val failed: Boolean = false,
) {
    val opened: Boolean get() = plan != null
}

/**
 * 批量会话（spec §7.6）。
 *
 * **不做无人值守批量。** 每一张都在同一个编辑器里过一遍，底栏的胶片条上点哪张、滑到哪张就看哪张，
 * 不必按顺序；一张图第一次被打开时才识别。看完统一导出，还有没打开过的图时先问一句。
 *
 * 理由：三态模型的全部价值在于人眼过一遍那些仅圈出的候选。无人值守批量只能二选一——
 * 要么把 OUTLINED 全部打码（等于取消分层，回到过度遮挡），要么全部不打码（静默漏检）。
 * 两个都不可接受，所以这个功能形态本身不成立。
 */
data class BatchSession(val items: List<BatchItem>, val index: Int) {

    val total: Int get() = items.size
    val current: BatchItem get() = items[index]

    val opened: Int get() = items.count { it.opened }

    /** 还没打开过的张数：没识别过，也没人看过。打不开的不算。 */
    val unopened: Int get() = items.count { !it.opened && !it.failed }

    /** 第一张还没打开过的，没有时为 null。 */
    val firstUnopened: Int? get() = items.indexOfFirst { !it.opened && !it.failed }.takeIf { it >= 0 }

    /** 导出拦截在批量下看**全部**图片的待打码数之和，不是只看当前这张。 */
    val totalPending: Int get() = items.sumOf { it.plan?.pendingCount ?: 0 }

    fun select(i: Int): BatchSession = copy(index = i.coerceIn(items.indices))

    fun withPlan(plan: MaskPlan): BatchSession = withPlanAt(index, plan)

    /** 不在眼前的那一张也要能写：离开一张图时它的识别还没跑完，结果出来时落回它自己的 plan。 */
    fun withPlanAt(i: Int, plan: MaskPlan): BatchSession = update(i) { it.copy(plan = plan) }

    /** 复制出了私有副本。先记下来：载入被下一次切换打断时，再回来不用重新复制一遍。 */
    fun withFile(i: Int, file: File, mimeType: String): BatchSession =
        update(i) { it.copy(file = file, mimeType = mimeType) }

    /** 打开成功：记下副本和识别图的缩放，plan 从这一刻起不再是 null。 */
    fun withLoaded(i: Int, file: File, mimeType: String, scale: Float): BatchSession = update(i) {
        it.copy(
            file = file,
            mimeType = mimeType,
            analysisScale = scale,
            plan = it.plan ?: MaskPlan.empty(),
            failed = false,
        )
    }

    fun withFailed(i: Int): BatchSession = update(i) { it.copy(failed = true) }

    /** 拦截对话框的「全部打码」：每一张的圈出项都用 [look]（编辑器当下的画笔）打上码。 */
    fun maskAllEverywhere(look: MaskLook): BatchSession =
        copy(items = items.map { it.copy(plan = it.plan?.maskAll(look)) })

    private inline fun update(i: Int, transform: (BatchItem) -> BatchItem): BatchSession =
        copy(items = items.mapIndexed { j, item -> if (j == i) transform(item) else item })
}
