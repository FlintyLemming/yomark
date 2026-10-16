package moe.flinty.yomark.ui

import android.graphics.PointF
import android.net.Uri
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.batch.BatchSession
import moe.flinty.yomark.ui.bigbang.BigBangPage
import androidx.annotation.StringRes

sealed interface EditorMessage {
    data class Exported(val uri: Uri, val downscaled: Boolean, val width: Int, val height: Int) : EditorMessage
    data class BatchExported(val count: Int) : EditorMessage
    /** 一批里有几张导出失败：成功几张、失败几张。 */
    data class BatchPartial(val ok: Int, val failed: Int) : EditorMessage
    /** 别的应用来要图，打好码的图导出好了，交回给它。编辑器随之关掉，不弹提示。 */
    data class HandedOver(val uris: List<Uri>) : EditorMessage
    data class Error(@StringRes val text: Int) : EditorMessage
}

/**
 * 「AI 增强识别」（Gemini Nano 第二遍）在当前这张图上的状态。
 *
 * 它不再自动跑：PP-OCR 加规则的结果已经够用，自动再跑一遍要多等好几秒，
 * 还会在画面上冒出一批码。现在是用户点了才跑，找到的直接打码。
 * 宽屏上跑的结果就写在 AI 那一块方块上，不另弹提示。手机底栏的圆钮上写不下字，跑完那一下在画布顶上说一声
 * （见 [EditorUiState.aiReviewsFinished]）。
 */
sealed interface AiReview {
    /** 不出现：机型不支持（也没配外部模型）、文字各类全关了、这张图还没识别完，或识别结果是从重建里恢复的（没有 OCR 文本可送）。 */
    data object Hidden : AiReview
    data object Ready : AiReview
    data object Running : AiReview

    /** 这张图已经增强识别过，新打上了 [found] 处码（0 就是没有新发现）。再跑一遍结果一样（温度 0），按钮就不再可点。 */
    data class Done(val found: Int) : AiReview

    /** 没跑成。和「跑了没发现」分开：不能让用户以为这一页是干净的。按钮还能点，再试一次。 */
    data object Failed : AiReview
}

/**
 * 拿着文本点了图上一处，字还没打（见 EditorViewModel.setText）：打下第一个字时，这块文本以 [center]（图像坐标）为中心落下，
 * id 是 [id]。[look] 是它的样子，null 用画笔：改字时把字删光了，那一块先拿掉、留下它原来的位置、样子和它在 plan 里的第几个
 * （[index]，-1 是排到最后），接着打还在原处、还在原来那一层。
 */
data class TextPlacement(val id: String, val center: PointF, val look: MaskLook? = null, val index: Int = -1)

/**
 * 大爆炸开着（2026-10-16，见 ui/bigbang）：长按图上的字打开，这张图识别出的字一个个摆在面板上。
 * [selected] 是选中的字（Glyph.id），画布上照它们连成的块预览；[focusLine] 是长按的那一行，面板打开时滚到它。
 * [holding]：长按的那根手指还没抬，接着划是接着选。面板等它抬起来再出来：面板一出来画布就变矮、图跟着挪，
 * 手指底下就不是刚才那几个字了。
 */
data class BigBangState(
    val page: BigBangPage,
    val selected: Set<Int> = emptySet(),
    val focusLine: Int = 0,
    val holding: Boolean = false,
)

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
    /** 端侧大模型的第二遍。只在用户点了之后跑，找到的打上码追加进 plan。 */
    val aiReview: AiReview = AiReview.Hidden,
    /**
     * AI 增强识别跑完了几次，成没成都算。手机上画布顶上那一声结果看它变没变（见 AiReviewResultPill），
     * 不看 [aiReview] 从什么变过来：批量里换回跑过的图时 [aiReview] 也直接变成 Done，那不是刚跑完；
     * 一下子就失败时界面也可能来不及看到 Running。
     */
    val aiReviewsFinished: Int = 0,
    /**
     * 这台设备上 AI 增强识别用得了：最近一次探测说能用（见 EditorViewModel.offerAiReview）。不像 [aiReview] 那样每换一张图、
     * 每识别一次就先回到 Hidden：宽屏给 AI 留着那一块方块，这张图还没识别完时那一块灰着，方块的行数不跟着一会儿多一会儿少（2026-10-16）。
     */
    val aiOffered: Boolean = false,
    val exporting: Boolean = false,
    /** 选中的手动框（刚画完，或点了它）：出现四角手柄与「删除」。只有手动框进得了选中态。 */
    val selectedManualId: String? = null,
    /**
     * 画笔：样式栏上选着的样式和各样式调好的参数（spec §7.5 修订）。新画的框、手画的线用它，已经打好的码不跟着变。
     * 偏好项，参数记在 SettingsStore 里，也活过每一次 EditorUiState 重建。
     */
    val brush: MaskLook = MaskLook(),
    /**
     * 「打码」里上次选的样式（色块、马赛克、模糊之一）。画笔换成马克笔、画笔时它不动，见 [maskLook]。
     * SettingsStore 只记它，下次打开编辑器时画笔就是它。
     */
    val maskStyle: MaskStyle = MaskStyle.SOLID,
    /** 样式栏下面的调节面板开着。点样式栏上的一项就弹出来，再点一下当前那项收起。 */
    val stylePanelOpen: Boolean = false,
    /** 要写的文本还没有字：落在哪儿（见 [TextPlacement]）。只在文本的面板开着时有。 */
    val textPlacement: TextPlacement? = null,
    /** 文本面板上的输入框该拿焦点、弹出输入法了：点了图上一处要写字，或者要改选中的文本。输入框拿到就放下（见 EditorViewModel.textFocusTaken）。 */
    val textFocus: Boolean = false,
    /** 吸管在等用户点图：点到哪儿就取哪儿的颜色，填进这一项。这期间点画布不切换打码。 */
    val colorPick: ColorTarget? = null,
    /** 大爆炸开着：面板顶替底栏，画布上点字、划字是选字（见 [BigBangState]）。不开着是 null。 */
    val bigBang: BigBangState? = null,
    /** 批量导出时还有没打开过的图：先问是去看看，还是只导出看过的。 */
    val unopenedDialogVisible: Boolean = false,
    /** 用途水印面板里的文字（「仅供办理 XX 使用」）。打开面板只填上它，开关打开才画到图上（见 [purposeText]）。 */
    val purposeDraft: String = "",
    /** 用途水印面板上的「应用」开关。 */
    val purposeOn: Boolean = false,
    /** 用途水印的外观。偏好项，活过每一次 EditorUiState 重建（换图不该把用户调好的样子丢掉）。 */
    val purposeStyle: PurposeWatermarkStyle = PurposeWatermarkStyle(),
    /** 用途水印调节面板开着：和样式的调节面板在同一个地方（手机上是底栏长出来的那一块），一次只开一个，图上实时预览。 */
    val purposeSheetVisible: Boolean = false,
    /**
     * 批量会话：相册一次分享多张（ACTION_SEND_MULTIPLE），或首页 Picker 里选了多张。单张时为 null，底栏也就没有胶片条。
     * 画布上这张图（[image] 不为 null 时）就是 batch.current，它的编辑在 [plan] 里，切走时才写回 batch。
     */
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

    /** 选中的文本；选中的不是文本时为 null。 */
    val selectedText: MaskItem? get() = selectedItem?.takeIf { it.look.style == MaskStyle.TEXT }

    /**
     * 样式栏上显示、面板里调的那一份：选中了手动框时是那个框的样子，否则是画笔。
     * 改字时把字删光了的那一块文本先拿掉了（见 [TextPlacement]），面板上还是它的样子。
     * 在面板上改，选中的框跟着变，画笔也换成改后的样子（见 EditorViewModel.editLook）；
     * 只是选中、什么也没改，画笔不动——取消选中后样式栏回到画笔。
     */
    val barLook: MaskLook get() = selectedItem?.look ?: textPlacement?.look ?: brush

    /** 图上（和导出时）的用途水印；null 是不加：开关关着，或者文字是空的。安全功能，与付费去水印无关。 */
    val purposeText: String? get() = purposeDraft.takeIf { purposeOn && it.isNotBlank() }

    /**
     * 打码用的样子：识别出来直接打的码、AI 增强识别找到的、点一下打上的码都用它。
     * 样式是「打码」里上次选的那一种，参数是画笔里调好的；马克笔、画笔只做标记，不拿来打码（2026-10-14）。
     */
    val maskLook: MaskLook get() = MaskLook(maskStyle, brush.options)

    /**
     * 连同画布上这张的编辑在内的批量会话。切图的那一下画布是空的（[image] 为 null，下一张还在载入），
     * 这时 [plan] 只是个占位，不能写回去，否则会把那一张已经存下的编辑冲掉。
     */
    val liveBatch: BatchSession? get() = batch?.let { if (image != null) it.withPlan(plan) else it }
}
