package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.util.lerp
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.ui.AiReview
import moe.flinty.yomark.ui.OverwriteOffer
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSettings: () -> Unit,
    onNavigateUp: () -> Unit,
    /**
     * 导出，在最右上角：底栏只放干活时反复用的工具，收尾这一下放在和返回相对的角上，展开着一组工具时也一直在。
     * 宽屏也在这儿（2026-10-16），原先在功能方块那一块最底下，和 AI 增强识别一行。
     */
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    /** 去水印的购买入口（开源版是免费版说明与打赏）。已购用户传 null，按钮就不出现。 */
    onRemoveWatermark: (() -> Unit)? = null,
    /** 调试信息（设置 → 关于，连点版本号打开）。没打开时传 null，按钮就不出现。 */
    onDebug: (() -> Unit)? = null,
    /**
     * 分享（2026-10-17）：图不进相册，写进缓存交给分享面板。和导出一样是收尾，一样一直在，不让出来。
     * [shareInMenu] 时是导出右边小箭头里的一项（设置里的出厂选项，2026-10-27），不然是顶栏上单独一颗，挨在导出左边。
     * 别的应用来要图时（导出是「完成」）传 null，哪儿都没有。
     */
    onShare: (() -> Unit)? = null,
    /** 分享放在导出右边的小箭头里，顶栏上没有单独那一颗（见 SettingsStore.shareInExportMenu）。 */
    shareInMenu: Boolean = false,
    /**
     * 导出按钮上的字：「导出」；导出就是覆盖原图时（设置里默认导出选了覆盖原图）是「覆盖」；
     * 别的应用来要图时是「完成」，导出就是把图交回去。
     */
    @StringRes exportLabel: Int = R.string.export,
    /**
     * 导出右边小箭头里的另一种导出（见 [OtherExport]）。箭头和导出连成一颗，一样不让出来；菜单里还有分享（[shareInMenu] 时）。
     * 别的应用来要图时（导出是「完成」）传 null，菜单里什么都没有，也就没有这个箭头。
     */
    otherExport: OtherExport? = null,
    onOtherExport: () -> Unit = {},
    /** 在导出（分享也是先导出）：导出、分享都点不动。 */
    exporting: Boolean = false,
    /** 这一次导出是为了分享：单独那颗分享转圈，导出那颗不换字；分享在菜单里时导出那颗写「分享中…」。 */
    sharing: Boolean = false,
    /** 这一次导出是覆盖原图：导出那颗转圈，读屏念「覆盖中…」。 */
    overwriting: Boolean = false,
) {
    Layout(
        content = {
            // 向上回到首页，放在 Android 约定的左上角
            IconButton(onClick = onNavigateUp, Modifier.layoutId(TopBarItem.BACK)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
            IconButton(onClick = onUndo, Modifier.layoutId(TopBarItem.UNDO), enabled = canUndo) { Icon(Icons.Filled.Undo, stringResource(R.string.undo)) }
            IconButton(onClick = onRedo, Modifier.layoutId(TopBarItem.REDO), enabled = canRedo) { Icon(Icons.Filled.Redo, stringResource(R.string.redo)) }
            if (onRemoveWatermark != null) {
                IconButton(onClick = onRemoveWatermark, Modifier.layoutId(TopBarItem.REMOVE_WATERMARK)) {
                    Icon(Icons.Filled.WorkspacePremium, stringResource(if (Edition.isFree) R.string.free_edition else R.string.remove_watermark))
                }
            }
            if (onDebug != null) {
                IconButton(onClick = onDebug, Modifier.layoutId(TopBarItem.DEBUG)) { Icon(Icons.Filled.BugReport, stringResource(R.string.debug_info)) }
            }
            IconButton(onClick = onSettings, Modifier.layoutId(TopBarItem.SETTINGS)) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
            val menuShare = onShare?.takeIf { shareInMenu }
            if (onShare != null && !shareInMenu) {
                // 色调底（tonal）的圆钮，和实心的导出挨着成一对；分享中转圈，读屏念「分享中…」
                val shareLabel = stringResource(if (exporting && sharing) R.string.sharing else R.string.share)
                FilledTonalIconButton(
                    onClick = onShare,
                    modifier = Modifier.layoutId(TopBarItem.SHARE).semantics { contentDescription = shareLabel },
                    enabled = !exporting,
                ) {
                    if (exporting && sharing) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Share, contentDescription = null)
                }
            }
            // 在跑的是不是导出这一颗：分享有单独一颗时转圈的是那颗
            val saving = exporting && (!sharing || menuShare != null)
            val label = stringResource(
                when {
                    !saving -> exportLabel
                    sharing -> R.string.sharing
                    overwriting -> R.string.overwriting
                    else -> R.string.exporting
                },
            )
            // 菜单里有东西时导出和右边的小箭头连成一颗（Material 3 的 split button）：挨着的那一边角收小，中间只隔一道缝
            val split = menuShare != null || otherExport != null
            Button(
                onClick = onExport,
                modifier = Modifier.layoutId(TopBarItem.EXPORT),
                enabled = !exporting,
                shape = if (split) SPLIT_START else ButtonDefaults.shape,
                contentPadding = if (split) SPLIT_START_PADDING else ButtonDefaults.ContentPadding,
            ) {
                Text(label, maxLines = 1, softWrap = false)
            }
            // 字放不下时换成它（见 TopBarItem）。导出中转圈，读屏念的还是按钮上的字。
            // 和箭头连着时不往四周留触摸余量，不然两半中间空出一大截；手指按偏一点 Compose 照样算按到
            val touchMargin = if (split) Dp.Unspecified else LocalMinimumInteractiveComponentSize.current
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides touchMargin) {
                FilledIconButton(
                    onClick = onExport,
                    modifier = Modifier.layoutId(TopBarItem.EXPORT_ICON)
                        .then(if (split) Modifier.width(SPLIT_ICON_WIDTH) else Modifier)
                        .semantics { contentDescription = label },
                    enabled = !exporting,
                    shape = if (split) SPLIT_START else IconButtonDefaults.filledShape,
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                    // 别的应用来要图时是「完成」：交回去；覆盖原图是存回原处
                    else Icon(
                        when (exportLabel) {
                            R.string.done -> Icons.Filled.Check
                            R.string.overwrite -> Icons.Filled.Save
                            else -> Icons.Filled.Download
                        },
                        contentDescription = null,
                    )
                }
            }
            if (split) {
                ExportMenu(menuShare, otherExport, onOtherExport, enabled = !exporting, Modifier.layoutId(TopBarItem.EXPORT_MENU))
            }
        },
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val part = { item: TopBarItem -> measurables.firstOrNull { it.layoutId == item } }
        val back = part(TopBarItem.BACK)!!.measure(loose)
        // 分享挨在导出左边，和返回、导出一样不让出来
        val share = part(TopBarItem.SHARE)?.measure(loose)
        val shareWidth = share?.width ?: 0
        // 导出右边的小箭头，连同中间那道缝都算在导出这一颗里
        val menu = part(TopBarItem.EXPORT_MENU)?.measure(loose)
        val menuWidth = menu?.let { it.width + SPLIT_GAP.roundToPx() } ?: 0
        val icons = measurables.filter { it.layoutId !in TopBarItem.ENDS }.map { it.layoutId as TopBarItem to it.measure(loose) }
        val allIcons = icons.sumOf { it.second.width }
        val gap = EXPORT_GAP.roundToPx()
        val label = part(TopBarItem.EXPORT)!!.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else back.width + allIcons + shareWidth + gap + label + menuWidth
        // 别的按钮都摆上，导出的字还整个放得下才写字
        val withLabel = back.width + allIcons + shareWidth + gap + label + menuWidth <= width
        val button = if (withLabel) {
            part(TopBarItem.EXPORT)!!.measure(loose.copy(maxWidth = width - back.width - allIcons - shareWidth - gap - menuWidth))
        } else {
            part(TopBarItem.EXPORT_ICON)!!.measure(loose)
        }
        var room = width - back.width - shareWidth - gap - button.width - menuWidth
        val kept = mutableSetOf<TopBarItem>()
        for ((item, icon) in icons.sortedBy { it.first }) {
            if (icon.width > room) break
            kept += item
            room -= icon.width
        }
        val shown = icons.filter { it.first in kept }.map { it.second }
        val iconsWidth = shown.sumOf { it.width }
        val barHeight = (listOf(back) + shown + listOfNotNull(share, menu) + button).maxOf { it.height }
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, barHeight) {
            val centered = { p: Placeable, x: Int -> p.place(x, (barHeight - p.height) / 2) }
            centered(back, 0)
            // 中间这几个靠在分享（没有分享时是导出）左边，和返回之间空着
            var x = width - menuWidth - button.width - gap - shareWidth - iconsWidth
            shown.forEach { centered(it, x); x += it.width }
            share?.let { centered(it, x) }
            centered(button, width - menuWidth - button.width)
            menu?.let { centered(it, width - it.width) }
        }
    }
}

/**
 * 顶栏上的按钮。一行放不下时（窄屏，或者调大了显示大小、字号）导出先换成只有图标的那个，字不折行；
 * 还放不下（再开着调试信息、有分享）时，中间那几个照要紧的先后从最后一个起不摆出来，返回、分享（单独一颗时）和导出（连着它右边的小箭头）一直在。
 * 去水印、设置在首页也有。用途水印、对比手机上在底栏，宽屏上在功能方块里（2026-10-16），顶栏上都没有。
 * 摆出来的还是照 [EditorTopBar] 里写的顺序从左往右。
 */
private enum class TopBarItem {
    BACK, EXPORT, EXPORT_ICON, EXPORT_MENU, SHARE, UNDO, REDO, SETTINGS, DEBUG, REMOVE_WATERMARK;

    companion object {
        /** 两头的，不参与让出来。 */
        val ENDS = setOf(BACK, EXPORT, EXPORT_ICON, EXPORT_MENU, SHARE)
    }
}

/** 导出和它左边的按钮（分享，没有分享时是中间那几个）之间多空一点。 */
private val EXPORT_GAP = 4.dp

/**
 * 导出和右边的小箭头连成一颗（Material 3 的 split button）：中间 2dp 的缝、外侧整个圆、挨着的那一边 4dp 的角照它的规格，
 * 箭头那一半比规格（48dp）窄，顶栏挤。字放不下时导出那一半是图标，比单独的图标钮宽一点，图标不贴着圆头。
 * 菜单离按钮空一点，不贴着弹出来。
 */
private val SPLIT_GAP = 2.dp
private val SPLIT_OUTER = CornerSize(50)
private val SPLIT_INNER = CornerSize(4.dp)
private val SPLIT_START = RoundedCornerShape(topStart = SPLIT_OUTER, topEnd = SPLIT_INNER, bottomEnd = SPLIT_INNER, bottomStart = SPLIT_OUTER)
private val SPLIT_END = RoundedCornerShape(topStart = SPLIT_INNER, topEnd = SPLIT_OUTER, bottomEnd = SPLIT_OUTER, bottomStart = SPLIT_INNER)
private val SPLIT_START_PADDING = PaddingValues(start = 16.dp, top = 8.dp, end = 12.dp, bottom = 8.dp)
private val SPLIT_ICON_WIDTH = 44.dp
private val SPLIT_ARROW_WIDTH = 32.dp
private val SPLIT_MENU_GAP = 6.dp

/**
 * 顶栏导出右边小箭头里的另一种导出（2026-10-27）：和导出做的相反的那一种。设置里默认导出选了哪一种，导出就是哪一种。
 */
sealed interface OtherExport {
    /** 导出是另存时：覆盖原图，打好码的图写回原图。点不点得动见 [offer]。 */
    data class Overwrite(val offer: OverwriteOffer) : OtherExport

    /** 导出就是覆盖原图时：另存到相册，原图不动。 */
    data object SaveCopy : OtherExport
}

/**
 * 导出右边的小箭头，菜单从它底下弹出来：分享（[share]，放在菜单里时）在上，另一种导出（见 [OtherExport]）在下。
 * 覆盖原图写不回去时那一项灰着，底下的小字说为什么；批量里看过的有几张写不回去，底下说一声它们会另存。
 * 小字不跟着那一项变灰，灰着的时候也看得清。
 */
@Composable
private fun ExportMenu(
    share: (() -> Unit)?,
    other: OtherExport?,
    onOther: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "exportMenuArrow")
    val description = stringResource(R.string.export_options)
    Box(modifier) {
        // 只有箭头这一窄条：不往四周留触摸余量，和导出之间只隔一道缝（见 EditorTopBar 里导出的图标）
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            Button(
                onClick = { open = true },
                modifier = Modifier.width(SPLIT_ARROW_WIDTH).semantics { contentDescription = description },
                enabled = enabled,
                shape = SPLIT_END,
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, Modifier.size(20.dp).rotate(turn))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, SPLIT_MENU_GAP)) {
            if (share != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = {
                        open = false
                        share()
                    },
                    leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null) },
                )
            }
            val choose = {
                open = false
                onOther()
            }
            when (other) {
                null -> Unit
                OtherExport.SaveCopy -> DropdownMenuItem(
                    text = { Text(stringResource(R.string.save_copy)) },
                    onClick = choose,
                    leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null) },
                )
                is OtherExport.Overwrite -> {
                    val offer = other.offer
                    val note = when (offer) {
                        is OverwriteOffer.Ready -> offer.copies.takeIf { it > 0 }?.let { stringResource(R.string.overwrite_copies_in_batch, it) }
                        is OverwriteOffer.Unavailable -> offer.reason?.let { stringResource(it) }
                    }
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.overwrite_original))
                                if (note != null) {
                                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        },
                        onClick = choose,
                        leadingIcon = { Icon(Icons.Outlined.Save, contentDescription = null) },
                        enabled = offer is OverwriteOffer.Ready,
                    )
                }
            }
        }
    }
}

/**
 * 手机的底栏在画布那一块里从哪儿开始（见 EditorScreen 的 CanvasAndPanel）。
 *
 * 底栏占满画布那一块的高：调节面板从工具那一行往上长出来，盖在画布上，图不跟着往上顶。
 * 画布只给这条线以下让地方；没有这条线的（宽屏的那一栏）照它自己的高让。
 */
val PanelTop = HorizontalAlignmentLine(::min)

/**
 * 手机的底栏：一行浮着的工具（见 [EditorToolbar]），它自己留两边的空。导出在顶栏右上角（见 [EditorTopBar]）。
 * 批量时胶片条单独一行，在工具上面。
 *
 * 样式的调节面板（展开一组后再点当前的样式）不另外弹一层：胶囊直接往上长成一块大圆角面板，
 * 面板的名字、参数在上面一点点显出来，工具那一行还在面板最底下，换样式不用先收起。
 * 用途水印的面板（点「编辑」里的水印）也是这样长出来的，和样式面板一次只开一个：开着一个时点另一个，
 * 胶囊不收回去，面板换成另一个的，一点点变高变矮。
 * 面板盖在图上（批量时连胶片条一起盖住），画布不让地方，图不动（见 [PanelTop]）。
 * 收起：点面板右上角的箭头、按返回、或者点返回收起这一组。吸管在等取色时面板先缩回去，取完色再长出来。
 * 点面板外面的哪儿（图上、两边、顶栏，见 [FoldOnTouch]）都和点面板右上角的箭头一样，只收面板（2026-10-16，原先连这一组一起收）：
 * 展开着「编辑」写完一处字点一下图，还在「编辑」里，文本还拿在手里。收了面板底栏回到打开它之前那样（见 EditorScreen）。
 *
 * 大爆炸的面板（2026-10-16）也是这样从胶囊长出来、缩回去，原先是一下顶替整个底栏。用得了 AI 时从「工具」那一行长出来，
 * 那一行还在面板最底下；用不了 AI 时「工具」里只有它，面板直接从它那颗圆钮长出来，占到胶囊最底下，没有工具那一行
 * （[toolGroup] 是 ToolGroup.BIG_BANG）。和别的面板不同的是它让开画布：选字时要看着图上选中的是哪几块，
 * 图跟着胶囊的上沿往上让；点图上是点字，点面板外面不收起它。
 *
 * 胶囊就画在这里，只有这一块：上沿跟着面板长到哪儿，左右跟着工具那一行说的走（见 PillLeft），
 * 面板收着时它就是工具那一行的胶囊。长出来、缩回去的半路上也只有这一圈边、一层投影，看着是整个胶囊在变大变小；
 * 开着面板点返回收起这一组，面板上的东西先淡掉，胶囊一边往下缩一边往回收窄，往下比收窄快一点（见 panelFoldSpec）。
 *
 * @param toolGroup 展开着的那一组，收着时为 null
 * @param bigBangReady 大爆炸点得动（见 [EditorToolbar]）
 * @param bigBang 开着的大爆炸，面板从胶囊长出来；不开着（或者长按的手指还没抬，见 BigBangState.holding）是 null
 * @param compare 「按住对比」的按压状态（见 [EditorToolbar]）
 * @param purpose 用途水印：设了水印时「编辑」里的水印着主题色，面板开着时从胶囊长出来
 * @param pushCanvas 面板不盖在图上，画布让到面板上面为止（见 [PanelTop]）：写文本时字落在点的那一处，
 *   面板盖着的话打的字看不见。用途水印铺满整张图，盖住一截照样看得见，不用让
 */
@Composable
fun EditorBottomBar(
    toolGroup: ToolGroup?,
    onToolGroup: (ToolGroup?) -> Unit,
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    compare: MutableInteractionSource,
    canCompare: Boolean,
    modifier: Modifier = Modifier,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
    bigBangReady: Boolean = false,
    onBigBang: () -> Unit = {},
    bigBang: BigBangPanelModel? = null,
    bigBangActions: BigBangActions = object : BigBangActions {},
    purpose: PurposePanelModel = PurposePanelModel(),
    purposeActions: PurposePanelActions = object : PurposePanelActions {},
    /** 批量时的胶片条。单张时为 null。 */
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
    pushCanvas: Boolean = false,
) {
    // 大爆炸的面板等这一行换到「工具」（或者大爆炸自己占住这一行）再长：从收着的那一行打开时，
    // 胶囊先得从那颗圆钮长出来（见 PillInset）
    val bigBangRow = toolGroup == ToolGroup.TOOLS || toolGroup == ToolGroup.BIG_BANG
    val wanted = (bigBang != null && bigBangRow) ||
        ((styleBar.panelOpen || purpose.open) && styleBar.colorPick == null && toolGroup != null)
    val grow = animateFloatAsState(if (wanted) 1f else 0f, panelFoldSpec(wanted), label = "stylePanel")
    // 面板上的东西淡进淡出走这一条：收回去时先淡没，胶囊再往回缩，不被它的边裁着走（见 contentFoldSpec）
    val contentIn = animateFloatAsState(if (wanted) 1f else 0f, contentFoldSpec(wanted), label = "stylePanelContent")
    val shown by remember { derivedStateOf { grow.value > 0f } }
    val panelShown = wanted || shown
    // 收回去的半路上几样都已经关了，刚才开着的那一个还要接着画完
    val last = remember { LastPanel() }
    when {
        bigBang != null -> {
            last.kind = PanelKind.BIG_BANG
            last.bang = bigBang
        }
        purpose.open -> last.kind = PanelKind.PURPOSE
        styleBar.panelOpen -> last.kind = PanelKind.STYLE
    }
    // 收回去的半路上面板已经关了，画布还照刚才那样让，跟着胶囊的上沿一点点回来，不一下跳回去；
    // 大爆炸占住这一行的，这一行也已经换回去了，面板照样占到胶囊最底下
    if (wanted) {
        last.push = pushCanvas || bigBang != null
        last.bare = toolGroup == ToolGroup.BIG_BANG
    }
    val container = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = EDGE_ALPHA)

    Layout(
        content = {
            // 垫在最底下，铺满底栏这一整块（画布那一块都是它的）：落在胶囊外面的手指都归它。顶栏那一截见 EditorScreen。
            // 大爆炸开着时不垫：点图上是点字
            if (wanted && bigBang == null) {
                FoldOnTouch({ if (purpose.open) purposeActions.onCollapse() else styleActions.onCollapse() }, Modifier.layoutId(BarPart.SCRIM))
            }
            filmstrip?.invoke(Modifier.layoutId(BarPart.FILM))
            // 面板上没有控件的地方也接住手指：落不到底下的画布上去画框。
            // 面板收着时不接：两组只有 40 dp 高，按在它们上下边外面一点也算按到，胶囊接了就按不着了
            Box(
                Modifier
                    .layoutId(BarPart.PILL)
                    .floatingSurface(container, edge, PANEL_RADIUS)
                    .then(if (panelShown) Modifier.pointerInput(Unit) { swallowTouches() } else Modifier),
            )
            val bang = last.bang
            if (panelShown && last.kind == PanelKind.BIG_BANG && bang != null) {
                // 字块自己滚动，名字那一行不跟着滚，不放进 PanelColumn
                BigBangContent(bang.bang, bang.covered, bigBangActions, Modifier.layoutId(BarPart.CONTENT).phoneBigBangHeight())
            } else if (panelShown) {
                // 两种面板换着开时外面这一列不换，跟着里面的东西一点点变高变矮
                PanelColumn(Modifier.layoutId(BarPart.CONTENT)) {
                    if (last.kind == PanelKind.PURPOSE) PurposePanelContent(purpose, purposeActions, compactWhileTyping = true)
                    else StylePanelContent(styleBar, styleActions, compactWhileTyping = true)
                }
            }
            EditorToolbar(
                open = toolGroup,
                onOpen = onToolGroup,
                styleBar = styleBar,
                styleActions = styleActions,
                // 图上真有水印才算开着（同 EditorUiState.purposeText）：开了「应用」、写了字
                purposeOn = purpose.on && purpose.text.isNotBlank(),
                purposeOpen = purpose.open,
                onPurpose = purposeActions::onToggle,
                aiReview = aiReview,
                onAiReview = onAiReview,
                bigBangReady = bigBangReady,
                onBigBang = onBigBang,
                bigBangOpen = bigBang != null,
                compare = compare,
                canCompare = canCompare,
                modifier = Modifier.layoutId(BarPart.ROW),
            )
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val pad = BAR_PAD.roundToPx()
        val margin = PANEL_MARGIN.roundToPx()
        val part = { id: BarPart -> measurables.firstOrNull { it.layoutId == id } }
        val row = part(BarPart.ROW)!!.measure(Constraints.fixedWidth(width))
        val film = part(BarPart.FILM)?.measure(Constraints.fixedWidth((width - FILM_MARGIN.roundToPx() * 2).coerceAtLeast(0)))
        val filmGap = if (film != null) FILM_GAP.roundToPx() else 0
        val resting = pad + (film?.height ?: 0) + filmGap + row.height + pad
        // 有多高就占多高：面板往上长的那一截在画布上面，画布只让到 PanelTop 为止
        val height = if (constraints.hasBoundedHeight) max(constraints.maxHeight, resting) else resting
        val rowY = height - pad - row.height
        val panelWidth = (width - margin * 2).coerceAtLeast(0)
        // 面板的底：平常在工具那一行上面；大爆炸占住这一行时就是胶囊的底（见 ToolGroup.BIG_BANG）
        val contentBottom = if (last.bare) height - pad else rowY
        // 面板最高长到画布那一块的顶上，再高就在面板里面滚动
        val roomAbove = (contentBottom - pad).coerceAtLeast(0)
        val content = part(BarPart.CONTENT)?.measure(Constraints(minWidth = panelWidth, maxWidth = panelWidth, maxHeight = roomAbove))
        val p = grow.value
        val openTop = contentBottom - (content?.height ?: 0)
        val pillTop = lerp(rowY, openTop, p)
        // 左右听工具那一行的：收着、展开、半路上都不一样宽。面板开着时它撑满这一行，和面板一样宽。
        // 上下平常就是这一行，从「工具」那颗矮一点的圆钮长出来时上下各缩进去一点（见 PillInset）
        val pillLeft = row[PillLeft]
        val pillRight = row[PillRight]
        val pillInset = row[PillInset].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val pillBottom = height - pad - pillInset
        val pill = part(BarPart.PILL)!!.measure(
            Constraints.fixed((pillRight - pillLeft).coerceAtLeast(0), (pillBottom - pillTop - pillInset).coerceAtLeast(0)),
        )
        val scrim = part(BarPart.SCRIM)?.measure(Constraints.fixed(width, height))

        // 画布让到哪儿：平常只让工具那一行（和胶片条），面板往上长时盖在图上；要让开面板时跟着胶囊的上沿一点点让
        val canvasEnd = if (last.push) min(pillTop, height - resting) else height - resting
        layout(width, height, mapOf(PanelTop to canvasEnd)) {
            scrim?.place(0, 0)
            film?.place(FILM_MARGIN.roundToPx(), rowY - filmGap - film.height)
            pill.place(pillLeft, pillTop + pillInset)
            // 名字、参数跟着胶囊的上沿往上走，胶囊没盖到的地方不露出来，后半程显出来
            // （圆角和胶囊一样，见 floatingSurface：不超过它窄的那一边的一半）
            val radius = min(PANEL_RADIUS.toPx(), min(pill.width, pill.height) / 2f)
            content?.placeWithLayer(margin, pillTop) {
                alpha = span(contentIn.value, CONTENT_IN_START, 1f)
                clip = true
                shape = AbovePill(
                    left = (pillLeft - margin).toFloat(),
                    top = pillInset.toFloat(),
                    right = (pillRight - margin).toFloat(),
                    bottom = ((if (last.bare) pillBottom else rowY) - pillTop).toFloat(),
                    radius = radius,
                    roundBottom = last.bare,
                )
            }
            row.place(0, rowY)
        }
    }
}

private enum class BarPart { SCRIM, FILM, PILL, CONTENT, ROW }

/**
 * 胶囊上一次长出来的是哪一种面板（大爆炸的连着它那时的样子）、画布让没让开它、它是不是占住了工具那一行（[bare]，见 ToolGroup.BIG_BANG）。
 * 只在组合时写、量的时候读，不触发重组。
 */
private class LastPanel {
    var kind = PanelKind.STYLE
    var bang: BigBangPanelModel? = null
    var push = false
    var bare = false
}

private enum class PanelKind { STYLE, PURPOSE, BIG_BANG }

/**
 * 面板上的东西只露出胶囊盖着的那一截：左右从 [left] 到 [right]，从 [top] 往下到 [bottom]，按它自己的坐标。
 * 平常到工具那一行上面为止，不从面板底下漏到工具那一行里；大爆炸占住这一行时到胶囊的底（见 ToolGroup.BIG_BANG）。
 * 开着面板收起这一组时胶囊往回收窄，也不从两边漏出去。
 * 角和胶囊一样圆（[radius]）：胶囊从一颗圆钮长出来、缩回去的半路上，面板上的东西不从圆角外面露出来；
 * 到工具那一行为止的，底下两个角不圆（[roundBottom] 为 false）。
 */
private class AbovePill(
    private val left: Float,
    private val top: Float,
    private val right: Float,
    private val bottom: Float,
    private val radius: Float,
    private val roundBottom: Boolean,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = CornerRadius(radius)
        val bottomCorner = if (roundBottom) corner else CornerRadius.Zero
        val area = Rect(left, top, right, bottom.coerceAtMost(size.height).coerceAtLeast(top))
        return Outline.Rounded(RoundRect(area, corner, corner, bottomCorner, bottomCorner))
    }
}

/**
 * 调节面板开着时铺在它外面的一层，看不见：手指一落下就等于点了面板右上角的箭头，收起面板（[onFold]）。
 * 这一下只收起，不往下传：不在画布上画框，顶栏的按钮也不响应。
 * 吸管等着取色时面板缩回去了，没有这一层，点的是图（见 EditorBottomBar 的 wanted）。
 */
@Composable
internal fun FoldOnTouch(onFold: () -> Unit, modifier: Modifier = Modifier) {
    val fold by rememberUpdatedState(onFold)
    Box(modifier.pointerInput(Unit) { swallowTouches(onDown = { fold() }) })
}

/** 接住落在这儿的手指，落下时 [onDown]，别的什么也不做。 */
internal suspend fun PointerInputScope.swallowTouches(onDown: () -> Unit = {}) = awaitEachGesture {
    awaitFirstDown().consume()
    onDown()
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** 底栏上下各留的空。原先 8，底栏压薄时一起收一点。 */
private val BAR_PAD = 6.dp
private val FILM_MARGIN = 12.dp

/** 面板离屏幕两边多远：和展开后的胶囊一样宽（见 EditorToolbar 的 MARGIN）。 */
private val PANEL_MARGIN = 12.dp
private val FILM_GAP = 6.dp

/** 胶囊的圆角：收着时就是两头的半圆，长高了四个角还是这么圆，看得出面板是胶囊长出来的。 */
private val PANEL_RADIUS = TOOL_ROW_HEIGHT / 2

/** 面板上的名字、参数从这儿起淡入：面板先长出一截再显出来，不和它一起挤在一条缝里。 */
internal const val CONTENT_IN_START = 0.35f

/**
 * 手机竖着拿时，AI 增强识别跑完的那一下在画布顶上说一声结果，几秒后收起：它在底栏的「工具」里（见 [EditorToolbar]），
 * 收起了「工具」，圆钮上写不下字。展开着时那一项上也写着结果。
 * 只在 [finished]（跑完的次数，见 EditorUiState.aiReviewsFinished）变了时说：批量里换回跑过的图不再说一遍。
 * 和识别中的提示条一样用 Box 画底色，不吃触摸。
 */
@Composable
internal fun AiReviewResultPill(review: AiReview, finished: Int, modifier: Modifier = Modifier) {
    var said by remember { mutableStateOf<AiReview?>(null) }
    var visible by remember { mutableStateOf(false) }
    var seen by remember { mutableIntStateOf(finished) }
    LaunchedEffect(finished) {
        // 换了一张图，计数从 0 重来：不说
        val fresh = finished != seen && finished != 0
        seen = finished
        if (!fresh) return@LaunchedEffect
        said = review
        visible = true
        delay(AI_RESULT_MS)
        visible = false
    }
    // 又跑起来了，或者换了图（载入、识别时它先收起）：上一回的结果不再挂着
    LaunchedEffect(review) { if (review == AiReview.Hidden || review == AiReview.Running) visible = false }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val text = when (val result = said) {
            is AiReview.Done ->
                if (result.found == 0) stringResource(R.string.ai_review_result_none)
                else stringResource(R.string.ai_review_result_found, result.found)
            else -> stringResource(R.string.ai_review_result_failed)
        }
        Box(
            Modifier
                .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.inverseOnSurface)
        }
    }
}

/** 跑完的结果在画布顶上停多久。 */
private const val AI_RESULT_MS = 3_000L
