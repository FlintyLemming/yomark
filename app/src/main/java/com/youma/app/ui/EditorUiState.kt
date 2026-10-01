package com.youma.app.ui

import android.net.Uri
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.MaskPlan
import com.youma.app.engine.RecognitionConfig
import com.youma.app.ui.batch.BatchSession

sealed interface EditorMessage {
    data class Exported(val uri: Uri, val downscaled: Boolean, val width: Int, val height: Int) : EditorMessage
    data class BatchExported(val count: Int) : EditorMessage
    data class Error(val text: String) : EditorMessage
    /** 不是出错，只是告诉用户一件事的结果（如「AI 复查没有新发现」）。 */
    data class Notice(val text: String) : EditorMessage
}

/**
 * 「AI 复查」（Gemini Nano 第二遍）在当前这张图上的状态。
 *
 * 它不再自动跑：PP-OCR 加规则的结果已经够用，自动再跑一遍要多等好几秒，
 * 还会在画面上冒出一批不打码的框。现在是用户点了才跑。
 */
enum class AiReview {
    /** 不出现：设置里关了、机型不支持、这张图还没识别完，或识别结果是从重建里恢复的（没有 OCR 文本可送）。 */
    HIDDEN,
    READY,
    RUNNING,
    /** 这张图已经复查过。再跑一遍结果一样（温度 0），按钮就不再可点。 */
    DONE,
}

data class EditorUiState(
    val image: SourceImage? = null,
    val plan: MaskPlan = MaskPlan.empty(),
    /**
     * Activity 重建后的恢复尚未落地。为 true 时不要拉 Photo Picker——
     * 图马上就回来了；为 false 而 image 仍是 null 才说明确实没有可恢复的会话。
     */
    val restoring: Boolean = false,
    val loading: Boolean = false,
    /** 识别进行中。画布此时仍可交互——用户可以先手动画框。 */
    val analyzing: Boolean = false,
    /** 端侧大模型的第二遍。只在用户点了之后跑，结果追加进 plan，只圈出。 */
    val aiReview: AiReview = AiReview.HIDDEN,
    val exporting: Boolean = false,
    /** 长按手动框进入的选中态：出现四角手柄与删除按钮。 */
    val selectedManualId: String? = null,
    val pendingDialogVisible: Boolean = false,
    /** 当前样式在这张图上会降级时的说明。抹除遇到复杂背景时非空。 */
    val degradeNote: String? = null,
    /** 用途水印文案（「仅供办理 XX 使用」）。安全功能，与付费去水印无关。 */
    val purposeText: String? = null,
    val purposeSheetVisible: Boolean = false,
    /** ACTION_SEND_MULTIPLE 进来的批量会话；单张时为 null，底栏也就没有「下一张」。 */
    val batch: BatchSession? = null,
    /**
     * 已买断去水印（spec §10）。免费版一项隐私能力都不缺，付费买到的纯粹是外观。
     * 由 BillingRepository 的缓存优先购买态推过来，飞行模式下沿用缓存。
     */
    val isPro: Boolean = false,
    val paywallVisible: Boolean = false,
    /** 当前识别方案（2026-09-04 增补设计 §1）。设置页读它画选中态。 */
    val recognitionConfig: RecognitionConfig = RecognitionConfig(),
    val settingsVisible: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: EditorMessage? = null,
)
