package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
     * 宽屏也在这儿（2026-10-16），原先在样式列表那一块最底下，和 AI 增强识别一行。
     */
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
    /** 去水印的购买入口（开源版是免费版说明与打赏）。已购用户传 null，按钮就不出现。 */
    onRemoveWatermark: (() -> Unit)? = null,
    /** 调试信息（设置 → 关于，连点版本号打开）。没打开时传 null，按钮就不出现。 */
    onDebug: (() -> Unit)? = null,
    /** 导出按钮上的字。别的应用来要图时是「完成」：导出就是把图交回去。 */
    @StringRes exportLabel: Int = R.string.export,
    exporting: Boolean = false,
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
            val label = stringResource(if (exporting) R.string.exporting else exportLabel)
            Button(onClick = onExport, Modifier.layoutId(TopBarItem.EXPORT), enabled = !exporting) {
                Text(label, maxLines = 1, softWrap = false)
            }
            // 字放不下时换成它（见 TopBarItem）。导出中转圈，读屏念的还是按钮上的字
            FilledIconButton(
                onClick = onExport,
                modifier = Modifier.layoutId(TopBarItem.EXPORT_ICON).semantics { contentDescription = label },
                enabled = !exporting,
            ) {
                if (exporting) CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                // 别的应用来要图时是「完成」：交回去
                else Icon(if (exportLabel == R.string.done) Icons.Filled.Check else Icons.Filled.Download, contentDescription = null)
            }
        },
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val part = { item: TopBarItem -> measurables.firstOrNull { it.layoutId == item } }
        val back = part(TopBarItem.BACK)!!.measure(loose)
        val icons = measurables.filter { it.layoutId !in TopBarItem.ENDS }.map { it.layoutId as TopBarItem to it.measure(loose) }
        val allIcons = icons.sumOf { it.second.width }
        val gap = EXPORT_GAP.roundToPx()
        val label = part(TopBarItem.EXPORT)!!.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else back.width + allIcons + gap + label
        // 别的按钮都摆上，导出的字还整个放得下才写字
        val withLabel = back.width + allIcons + gap + label <= width
        val button = if (withLabel) part(TopBarItem.EXPORT)!!.measure(loose.copy(maxWidth = width - back.width - allIcons - gap))
        else part(TopBarItem.EXPORT_ICON)!!.measure(loose)
        var room = width - back.width - gap - button.width
        val kept = mutableSetOf<TopBarItem>()
        for ((item, icon) in icons.sortedBy { it.first }) {
            if (icon.width > room) break
            kept += item
            room -= icon.width
        }
        val shown = icons.filter { it.first in kept }.map { it.second }
        val iconsWidth = shown.sumOf { it.width }
        val barHeight = (listOf(back) + shown + button).maxOf { it.height }
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, barHeight) {
            val centered = { p: Placeable, x: Int -> p.place(x, (barHeight - p.height) / 2) }
            centered(back, 0)
            // 中间这几个靠在导出左边，和返回之间空着
            var x = width - button.width - gap - iconsWidth
            shown.forEach { centered(it, x); x += it.width }
            centered(button, width - button.width)
        }
    }
}

/**
 * 顶栏上的按钮。一行放不下时（窄屏，或者调大了显示大小、字号）导出先换成只有图标的那个，字不折行；
 * 还放不下（再开着调试信息）时，中间那几个照要紧的先后从最后一个起不摆出来，返回和导出一直在。
 * 去水印、设置在首页也有。用途水印、对比在底栏上（宽屏也是，2026-10-16），顶栏上没有。
 * 摆出来的还是照 [EditorTopBar] 里写的顺序从左往右。
 */
private enum class TopBarItem {
    BACK, EXPORT, EXPORT_ICON, UNDO, REDO, SETTINGS, DEBUG, REMOVE_WATERMARK;

    companion object {
        /** 两头的，不参与让出来。 */
        val ENDS = setOf(BACK, EXPORT, EXPORT_ICON)
    }
}

/** 导出和它左边的按钮之间多空一点。 */
private val EXPORT_GAP = 4.dp

/**
 * 底栏在画布那一块里从哪儿开始（见 EditorScreen 的 CanvasAndPanel）。
 *
 * 手机的底栏占满画布那一块的高：调节面板从工具那一行往上长出来，盖在画布上，图不跟着往上顶。
 * 宽屏竖着拿的也占满，只是这条线跟着面板的上沿走，画布让开面板（见 BarPlacement.BOTTOM）。
 * 画布只给这条线以下让地方；没有这条线的照它自己的高让。
 */
val PanelTop = HorizontalAlignmentLine(::min)

/**
 * 编辑器的底栏：一行浮着的工具（见 [EditorToolbar]），它自己留两边的空。导出在顶栏右上角（见 [EditorTopBar]）。
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
 * 宽屏、手机横着拿（[placement] 不是 PHONE，2026-10-16）也是它，在画布旁边那一块里（见 BarPlacement）：工具那一行在最底下，
 * 每一样各是一块圆角矩形（见 EditorToolbar 的 tiles），上面是框列表（[list]）。面板照样从这一行长上去，盖住列表、不盖图，
 * 胶囊和面板的角是 TILE_RADIUS 的圆角。图在旁边看得见，所以和手机不一样的是：点面板外面不收起，开着面板照样在图上画框、
 * 选中的框跟着面板变；吸管取色时面板不缩回去；打字时面板不收成只剩输入框。竖着拿时面板比这一块高，这一块跟着长高，
 * 画布跟着变矮（见 wideBottomOpen），大爆炸也是。
 *
 * @param toolGroup 展开着的那一组，收着时为 null
 * @param bigBangReady 大爆炸点得动（见 [EditorToolbar]）
 * @param bigBang 开着的大爆炸，面板从胶囊长出来；不开着（或者长按的手指还没抬，见 BigBangState.holding）是 null
 * @param compare 「按住对比」的按压状态（见 [EditorToolbar]）
 * @param purpose 用途水印：设了水印时「编辑」里的水印着主题色，面板开着时从胶囊长出来
 * @param pushCanvas 面板不盖在图上，画布让到面板上面为止（见 [PanelTop]）：写文本时字落在点的那一处，
 *   面板盖着的话打的字看不见。用途水印铺满整张图，盖住一截照样看得见，不用让。宽屏上不用给：面板总不盖图
 * @param placement 铺在哪儿：手机竖着拿铺满画布那一块，宽屏、手机横着拿在画布旁边那一块里
 * @param list 宽屏的框列表，在工具那一行上面，传进来的 Modifier 定好了它的位置和大小。手机上没有
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
    placement: BarPlacement = BarPlacement.PHONE,
    list: (@Composable (Modifier) -> Unit)? = null,
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
    val phone = placement == BarPlacement.PHONE
    // 大爆炸的面板等这一行换到「工具」（或者大爆炸自己占住这一行）再长：从收着的那一行打开时，
    // 胶囊先得从那颗圆钮长出来（见 PillInset）
    val bigBangRow = toolGroup == ToolGroup.TOOLS || toolGroup == ToolGroup.BIG_BANG
    // 吸管等着取色时手机上的面板缩回去，要点的是它盖着的图；宽屏上图在旁边，面板不用让
    val wanted = (bigBang != null && bigBangRow) ||
        ((styleBar.panelOpen || purpose.open) && (styleBar.colorPick == null || !phone) && toolGroup != null)
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
        // 宽屏竖着拿的面板总让开画布，不盖图（横着拿的在画布右边，碰不到图）
        last.push = pushCanvas || bigBang != null || !phone
        last.bare = toolGroup == ToolGroup.BIG_BANG
    }
    val container = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = EDGE_ALPHA)
    val radius = if (phone) PANEL_RADIUS else TILE_RADIUS

    Layout(
        content = {
            // 垫在最底下，铺满底栏这一整块（画布那一块都是它的）：落在胶囊外面的手指都归它。顶栏那一截见 EditorScreen。
            // 大爆炸开着时不垫：点图上是点字。宽屏上不垫：图在旁边，开着面板接着在图上画
            if (phone && wanted && bigBang == null) {
                FoldOnTouch({ if (purpose.open) purposeActions.onCollapse() else styleActions.onCollapse() }, Modifier.layoutId(BarPart.SCRIM))
            }
            list?.invoke(Modifier.layoutId(BarPart.LIST))
            filmstrip?.invoke(Modifier.layoutId(BarPart.FILM))
            // 面板上没有控件的地方也接住手指：落不到底下的画布（宽屏是列表）上去。
            // 面板收着时不接：两组只有 40 dp 高，按在它们上下边外面一点也算按到，胶囊接了就按不着了
            Box(
                Modifier
                    .layoutId(BarPart.PILL)
                    .floatingSurface(container, edge, radius)
                    .then(if (panelShown) Modifier.pointerInput(Unit) { swallowTouches() } else Modifier),
            )
            val bang = last.bang
            if (panelShown && last.kind == PanelKind.BIG_BANG && bang != null) {
                // 字块自己滚动，名字那一行不跟着滚，不放进 PanelColumn。
                // 手机上最多占一半，留一半给图；宽屏上图在旁边，给多高长多高（竖着拿的见 wideBottomOpen）
                BigBangContent(
                    bang.bang, bang.covered, bigBangActions,
                    Modifier.layoutId(BarPart.CONTENT).then(if (phone) Modifier.phoneBigBangHeight() else Modifier),
                )
            } else if (panelShown) {
                // 两种面板换着开时外面这一列不换，跟着里面的东西一点点变高变矮。
                // 打字时只留输入框是手机上的事：面板盖在图上，输入法又占掉下半截
                PanelColumn(Modifier.layoutId(BarPart.CONTENT)) {
                    if (last.kind == PanelKind.PURPOSE) PurposePanelContent(purpose, purposeActions, compactWhileTyping = phone)
                    else StylePanelContent(styleBar, styleActions, compactWhileTyping = phone)
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
                tiles = placement.tiles,
                modifier = Modifier.layoutId(BarPart.ROW),
            )
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val pad = BAR_PAD.roundToPx()
        val margin = PANEL_MARGIN.roundToPx()
        val part = { id: BarPart -> measurables.firstOrNull { it.layoutId == id } }
        // 宽屏竖着拿时这一块（列表、胶片条、工具那一行和从它长出来的面板）不铺满整个宽，在正中（见 BAR_MAX_WIDTH）
        val rowWidth = if (placement == BarPlacement.BOTTOM) min(width, BAR_MAX_WIDTH.roundToPx()) else width
        val rowX = (width - rowWidth) / 2
        val row = part(BarPart.ROW)!!.measure(Constraints.fixedWidth(rowWidth))
        val film = part(BarPart.FILM)?.measure(Constraints.fixedWidth((rowWidth - FILM_MARGIN.roundToPx() * 2).coerceAtLeast(0)))
        val filmGap = if (film != null) FILM_GAP.roundToPx() else 0
        val bar = pad + (film?.height ?: 0) + filmGap + row.height + pad
        // 闲着时这一块多高：手机上只有工具那一行（和胶片条），上面是图；宽屏上面还有列表，
        // 横着拿占满这一栏，竖着拿见 wideBottomRest
        val available = if (constraints.hasBoundedHeight) constraints.maxHeight else bar
        val resting = when (placement) {
            BarPlacement.PHONE -> bar
            BarPlacement.SIDE -> max(available, bar)
            BarPlacement.BOTTOM -> max(wideBottomRest(available), bar)
        }
        // 有多高就占多高：面板往上长的那一截在画布上面，画布只让到 PanelTop 为止
        val height = if (constraints.hasBoundedHeight) max(constraints.maxHeight, resting) else resting
        val rowY = height - pad - row.height
        val restTop = height - resting
        val panelWidth = (rowWidth - margin * 2).coerceAtLeast(0)
        // 面板的底：平常在工具那一行上面；大爆炸占住这一行时就是胶囊的底（见 ToolGroup.BIG_BANG）
        val contentBottom = if (last.bare) height - pad else rowY
        // 面板最高长到这一块的顶上，再高就在面板里面滚动；竖着拿的宽屏长到 wideBottomOpen 为止，画布留着
        val ceiling = if (placement == BarPlacement.BOTTOM) height - max(wideBottomOpen(available), resting) else 0
        val roomAbove = (contentBottom - ceiling - pad).coerceAtLeast(0)
        val content = part(BarPart.CONTENT)?.measure(Constraints(minWidth = panelWidth, maxWidth = panelWidth, maxHeight = roomAbove))
        val p = grow.value
        val openTop = contentBottom - (content?.height ?: 0)
        val pillTop = lerp(rowY, openTop, p)
        // 左右听工具那一行的：收着、展开、半路上都不一样宽。面板开着时它撑满这一行，和面板一样宽。
        // 上下平常就是这一行，从「工具」那颗矮一点的圆钮长出来时上下各缩进去一点（见 PillInset）
        val pillLeft = rowX + row[PillLeft]
        val pillRight = rowX + row[PillRight]
        val pillInset = row[PillInset].takeIf { it != AlignmentLine.Unspecified } ?: 0
        val pillBottom = height - pad - pillInset
        val pill = part(BarPart.PILL)!!.measure(
            Constraints.fixed((pillRight - pillLeft).coerceAtLeast(0), (pillBottom - pillTop - pillInset).coerceAtLeast(0)),
        )
        val scrim = part(BarPart.SCRIM)?.measure(Constraints.fixed(width, height))
        // 框列表在工具那一行（和胶片条）上面，铺到这一块的顶上。面板长上来时列表只到面板的上沿：
        // 盖住的那几行不留在列表里，读屏、键盘走不到看不见的行上去点掉一块码。面板是实心的，看着一个样
        val listTop = restTop + pad
        val listBottom = (if (film != null) rowY - filmGap - film.height else rowY) - LIST_GAP.roundToPx()
        val listEnd = if (panelShown) min(listBottom, pillTop) else listBottom
        val listed = part(BarPart.LIST)?.measure(
            Constraints.fixed(panelWidth, (listEnd - listTop).coerceAtLeast(0)),
        )

        // 画布让到哪儿：平常只让工具那一行（和胶片条），面板往上长时盖在图上；要让开面板时跟着胶囊的上沿一点点让
        val canvasEnd = if (last.push) min(pillTop, restTop) else restTop
        layout(width, height, mapOf(PanelTop to canvasEnd)) {
            scrim?.place(0, 0)
            listed?.place(rowX + margin, listTop)
            film?.place(rowX + FILM_MARGIN.roundToPx(), rowY - filmGap - film.height)
            pill.place(pillLeft, pillTop + pillInset)
            // 名字、参数跟着胶囊的上沿往上走，胶囊没盖到的地方不露出来，后半程显出来
            // （圆角和胶囊一样，见 floatingSurface：不超过它窄的那一边的一半）
            val corner = min(radius.toPx(), min(pill.width, pill.height) / 2f)
            val contentX = rowX + margin
            content?.placeWithLayer(contentX, pillTop) {
                alpha = span(contentIn.value, CONTENT_IN_START, 1f)
                clip = true
                shape = AbovePill(
                    left = (pillLeft - contentX).toFloat(),
                    top = pillInset.toFloat(),
                    right = (pillRight - contentX).toFloat(),
                    bottom = ((if (last.bare) pillBottom else rowY) - pillTop).toFloat(),
                    radius = corner,
                    roundBottom = last.bare,
                )
            }
            row.place(rowX, rowY)
        }
    }
}

private enum class BarPart { SCRIM, LIST, FILM, PILL, CONTENT, ROW }

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
private suspend fun PointerInputScope.swallowTouches(onDown: () -> Unit = {}) = awaitEachGesture {
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

/** 面板离屏幕两边多远：和展开后的胶囊一样宽（见 EditorToolbar 的 MARGIN）。宽屏的框列表也是。 */
private val PANEL_MARGIN = 12.dp
private val FILM_GAP = 6.dp

/** 宽屏的框列表和它下面的工具那一行（批量时是胶片条）之间空多少。 */
private val LIST_GAP = 8.dp

/** 胶囊的圆角：收着时就是两头的半圆，长高了四个角还是这么圆，看得出面板是胶囊长出来的。 */
private val PANEL_RADIUS = TOOL_ROW_HEIGHT / 2

/** 面板上的名字、参数从这儿起淡入：面板先长出一截再显出来，不和它一起挤在一条缝里。 */
private const val CONTENT_IN_START = 0.35f

/**
 * AI 增强识别跑完的那一下在画布顶上说一声结果，几秒后收起：它在底栏的「工具」里（见 [EditorToolbar]），
 * 收起了「工具」，圆钮上写不下字。展开着时那一项上也写着结果。宽屏也是（2026-10-16），原先宽屏的按钮上写着结果。
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
