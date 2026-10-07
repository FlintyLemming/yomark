package com.yomark.app.ui

import android.net.Uri
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.MaskItem
import com.yomark.app.core.model.MaskLook
import com.yomark.app.core.model.MaskPlan
import com.yomark.app.engine.RecognitionConfig
import com.yomark.app.export.PurposeWatermarkStyle
import com.yomark.app.ui.batch.BatchSession

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
    /** 选中的手动框（刚画完，或点了它）：出现四角手柄与「删除」。只有手动框进得了选中态。 */
    val selectedManualId: String? = null,
    /**
     * 画笔：下一次打码用的样式和参数（spec §7.5 修订）。新画的框、点了打码的虚线框、识别出来直接打码的都用它，
     * 已经打好的码不跟着变。偏好项，记在 SettingsStore 里，也活过每一次 EditorUiState 重建。
     */
    val brush: MaskLook = MaskLook(),
    /** 样式栏下面的调节面板开着。点样式栏上的一项就弹出来，再点一下当前那项收起。 */
    val stylePanelOpen: Boolean = false,
    /** 吸管在等用户点图：点到哪儿就取哪儿的颜色，填进这一项。这期间点画布不切换打码。 */
    val colorPick: ColorTarget? = null,
    val pendingDialogVisible: Boolean = false,
    /** 用途水印文案（「仅供办理 XX 使用」）。安全功能，与付费去水印无关。 */
    val purposeText: String? = null,
    /** 用途水印的外观。偏好项，活过每一次 EditorUiState 重建（换图不该把用户调好的样子丢掉）。 */
    val purposeStyle: PurposeWatermarkStyle = PurposeWatermarkStyle(),
    /** 用途水印调节面板开着：它顶替底栏，画布留在上面实时预览。 */
    val purposeSheetVisible: Boolean = false,
    /** ACTION_SEND_MULTIPLE 进来的批量会话；单张时为 null，底栏也就没有「下一张」。 */
    val batch: BatchSession? = null,
    /**
     * 已买断去水印（spec §10）。免费版一项隐私能力都不缺，付费买到的纯粹是外观。
     * 由 BillingRepository 的缓存优先购买态推过来，飞行模式下沿用缓存。
     */
    val isPro: Boolean = false,
    val paywallVisible: Boolean = false,
    /** 当前识别方案（2026-09-04 增补设计 §1）。 */
    val recognitionConfig: RecognitionConfig = RecognitionConfig(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val message: EditorMessage? = null,
) {
    /** 选中的手动框；没有选中、或选中的框已经不在了（被撤销掉了）时为 null。 */
    val selectedItem: MaskItem? get() = selectedManualId?.let { plan.find(it) }

    /**
     * 样式栏上显示、面板里调的那一份：选中了手动框时是那个框的样子，否则是画笔。
     * 在面板上改，选中的框跟着变，画笔也换成改后的样子（见 EditorViewModel.editLook）；
     * 只是选中、什么也没改，画笔不动——取消选中后样式栏回到画笔。
     */
    val barLook: MaskLook get() = selectedItem?.look ?: brush
}
