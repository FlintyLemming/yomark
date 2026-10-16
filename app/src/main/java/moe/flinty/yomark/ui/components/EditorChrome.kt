package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
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
    /** 用途水印。手机竖着拿时它在底栏的「编辑」里，传 null，这里就不出现。 */
    onPurpose: (() -> Unit)?,
    onSettings: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    /** 已设用途水印时按钮着主题色，一眼看出它开着。 */
    purposeOn: Boolean = false,
    /** 去水印的购买入口（开源版是免费版说明与打赏）。已购用户传 null，按钮就不出现。 */
    onRemoveWatermark: (() -> Unit)? = null,
    /** 调试信息（设置 → 关于，连点版本号打开）。没打开时传 null，按钮就不出现。 */
    onDebug: (() -> Unit)? = null,
    /** 「对比」按钮的按压状态：按住时画布显示打码前的原图（见 EditorScreen）。传 null 时按钮不出现：手机竖着拿时它在底栏。 */
    compare: MutableInteractionSource? = null,
    /** 有前后可比：打了码，或者设了用途水印。没有时「对比」灰掉。 */
    canCompare: Boolean = false,
    /**
     * 导出，在最右上角。手机竖着拿时在这儿：底栏只放干活时反复用的工具，收尾这一下放在和返回相对的角上，
     * 展开着一组工具时也一直在。传 null 时不出现：宽屏的导出在样式列表那一块的最底下（见 [EditorActionRow]）。
     */
    onExport: (() -> Unit)? = null,
    /** 导出按钮上的字（见 [EditorActionRow]）。 */
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
            if (onPurpose != null) {
                IconButton(onClick = onPurpose, Modifier.layoutId(TopBarItem.PURPOSE)) {
                    Icon(
                        PurposeWatermarkIcon,
                        stringResource(if (purposeOn) R.string.purpose_watermark_on else R.string.purpose_watermark),
                        tint = if (purposeOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
            }
            if (compare != null) {
                // 按住看原图、松手回来，点一下没有动作：来回比的时候，手一松就该回到打码后的样子
                IconButton(onClick = {}, Modifier.layoutId(TopBarItem.COMPARE), enabled = canCompare, interactionSource = compare) {
                    Icon(Icons.Filled.Compare, stringResource(R.string.compare_original))
                }
            }
            if (onDebug != null) {
                IconButton(onClick = onDebug, Modifier.layoutId(TopBarItem.DEBUG)) { Icon(Icons.Filled.BugReport, stringResource(R.string.debug_info)) }
            }
            IconButton(onClick = onSettings, Modifier.layoutId(TopBarItem.SETTINGS)) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
            if (onExport != null) {
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
            }
        },
        modifier = modifier.fillMaxWidth().padding(8.dp),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val part = { item: TopBarItem -> measurables.firstOrNull { it.layoutId == item } }
        val back = part(TopBarItem.BACK)!!.measure(loose)
        val icons = measurables.filter { it.layoutId !in TopBarItem.ENDS }.map { it.layoutId as TopBarItem to it.measure(loose) }
        val allIcons = icons.sumOf { it.second.width }
        val gap = if (part(TopBarItem.EXPORT) != null) EXPORT_GAP.roundToPx() else 0
        val label = part(TopBarItem.EXPORT)?.maxIntrinsicWidth(Constraints.Infinity) ?: 0
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else back.width + allIcons + gap + label
        // 别的按钮都摆上，导出的字还整个放得下才写字
        val withLabel = back.width + allIcons + gap + label <= width
        val button = if (withLabel) part(TopBarItem.EXPORT)?.measure(loose.copy(maxWidth = width - back.width - allIcons - gap))
        else part(TopBarItem.EXPORT_ICON)?.measure(loose)
        var room = width - back.width - gap - (button?.width ?: 0)
        val kept = mutableSetOf<TopBarItem>()
        for ((item, icon) in icons.sortedBy { it.first }) {
            if (icon.width > room) break
            kept += item
            room -= icon.width
        }
        val shown = icons.filter { it.first in kept }.map { it.second }
        val iconsWidth = shown.sumOf { it.width }
        val barHeight = (listOf(back) + shown + listOfNotNull(button)).maxOf { it.height }
            .coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, barHeight) {
            val centered = { p: Placeable, x: Int -> p.place(x, (barHeight - p.height) / 2) }
            centered(back, 0)
            // 中间这几个靠在导出左边，和返回之间空着
            var x = width - (button?.width ?: 0) - gap - iconsWidth
            shown.forEach { centered(it, x); x += it.width }
            button?.let { centered(it, width - it.width) }
        }
    }
}

/**
 * 顶栏上的按钮。一行放不下时（窄屏，或者调大了显示大小、字号）导出先换成只有图标的那个，字不折行；
 * 还放不下（再开着调试信息）时，中间那几个照要紧的先后从最后一个起不摆出来，返回和导出一直在。
 * 去水印、设置在首页也有；用途水印、对比只在宽屏的顶栏上，那儿放得下。
 * 摆出来的还是照 [EditorTopBar] 里写的顺序从左往右。
 */
private enum class TopBarItem {
    BACK, EXPORT, EXPORT_ICON, UNDO, REDO, PURPOSE, COMPARE, SETTINGS, DEBUG, REMOVE_WATERMARK;

    companion object {
        /** 两头的，不参与让出来。 */
        val ENDS = setOf(BACK, EXPORT, EXPORT_ICON)
    }
}

/** 导出和它左边的按钮之间多空一点。 */
private val EXPORT_GAP = 4.dp

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
 * 点面板外面的哪儿（图上、两边、顶栏，见 [FoldOnTouch]）都和点左下角的返回一样：面板和这一组一起收起。
 *
 * 胶囊就画在这里，只有这一块：上沿跟着面板长到哪儿，左右跟着工具那一行说的走（见 PillLeft），
 * 面板收着时它就是工具那一行的胶囊。长出来、缩回去的半路上也只有这一圈边、一层投影，看着是整个胶囊在变大变小；
 * 开着面板点返回收起这一组，胶囊一边往下缩一边往回收窄。
 *
 * @param toolGroup 展开着的那一组，收着时为 null
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
    purpose: PurposePanelModel = PurposePanelModel(),
    purposeActions: PurposePanelActions = object : PurposePanelActions {},
    /** 批量时的胶片条。单张时为 null。 */
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
    pushCanvas: Boolean = false,
) {
    val wanted = (styleBar.panelOpen || purpose.open) && styleBar.colorPick == null && toolGroup != null
    val grow = animateFloatAsState(if (wanted) 1f else 0f, foldSpec(wanted), label = "stylePanel")
    val shown by remember { derivedStateOf { grow.value > 0f } }
    val panelShown = wanted || shown
    // 收回去的半路上两样都已经关了，刚才开着的那一个还要接着画完
    val last = remember { LastPanel() }
    if (purpose.open) last.purpose = true else if (styleBar.panelOpen) last.purpose = false
    // 收回去的半路上面板已经关了，画布还照刚才那样让，跟着胶囊的上沿一点点回来，不一下跳回去
    if (wanted) last.push = pushCanvas
    val container = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = EDGE_ALPHA)

    Layout(
        content = {
            // 垫在最底下，铺满底栏这一整块（画布那一块都是它的）：落在胶囊外面的手指都归它。顶栏那一截见 EditorScreen
            if (wanted) FoldOnTouch({ onToolGroup(null) }, Modifier.layoutId(BarPart.SCRIM))
            filmstrip?.invoke(Modifier.layoutId(BarPart.FILM))
            // 面板上没有控件的地方也接住手指：落不到底下的画布上去画框。
            // 面板收着时不接：两组只有 40 dp 高，按在它们上下边外面一点也算按到，胶囊接了就按不着了
            Box(
                Modifier
                    .layoutId(BarPart.PILL)
                    .floatingSurface(container, edge, PANEL_RADIUS)
                    .then(if (panelShown) Modifier.pointerInput(Unit) { swallowTouches() } else Modifier),
            )
            // 两种面板换着开时外面这一列不换，跟着里面的东西一点点变高变矮
            if (panelShown) PanelColumn(Modifier.layoutId(BarPart.CONTENT)) {
                if (last.purpose) PurposePanelContent(purpose, purposeActions, compactWhileTyping = true)
                else StylePanelContent(styleBar, styleActions, compactWhileTyping = true)
            }
            EditorToolbar(
                open = toolGroup,
                onOpen = onToolGroup,
                styleBar = styleBar,
                styleActions = styleActions,
                purposeOn = purpose.text != null,
                purposeOpen = purpose.open,
                onPurpose = purposeActions::onToggle,
                aiReview = aiReview,
                onAiReview = onAiReview,
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
        // 面板最高长到画布那一块的顶上，再高就在面板里面滚动
        val roomAbove = (rowY - pad).coerceAtLeast(0)
        val content = part(BarPart.CONTENT)?.measure(Constraints(minWidth = panelWidth, maxWidth = panelWidth, maxHeight = roomAbove))
        val p = grow.value
        val openTop = rowY - (content?.height ?: 0)
        val pillTop = lerp(rowY, openTop, p)
        // 左右听工具那一行的：收着、展开、半路上都不一样宽。面板开着时它撑满这一行，和面板一样宽
        val pillLeft = row[PillLeft]
        val pillRight = row[PillRight]
        val pill = part(BarPart.PILL)!!.measure(Constraints.fixed((pillRight - pillLeft).coerceAtLeast(0), height - pad - pillTop))
        val scrim = part(BarPart.SCRIM)?.measure(Constraints.fixed(width, height))

        // 画布让到哪儿：平常只让工具那一行（和胶片条），面板往上长时盖在图上；要让开面板时跟着胶囊的上沿一点点让
        val canvasEnd = if (last.push) min(pillTop, height - resting) else height - resting
        layout(width, height, mapOf(PanelTop to canvasEnd)) {
            scrim?.place(0, 0)
            film?.place(FILM_MARGIN.roundToPx(), rowY - filmGap - film.height)
            pill.place(pillLeft, pillTop)
            // 名字、参数跟着胶囊的上沿往上走，胶囊没盖到的地方不露出来，后半程显出来
            content?.placeWithLayer(margin, pillTop) {
                alpha = span(p, CONTENT_IN_START, 1f)
                clip = true
                shape = AbovePill((pillLeft - margin).toFloat(), (pillRight - margin).toFloat(), (rowY - pillTop).toFloat())
            }
            row.place(0, rowY)
        }
    }
}

private enum class BarPart { SCRIM, FILM, PILL, CONTENT, ROW }

/** 胶囊上一次长出来的是不是用途水印的面板、画布让没让开它。只在组合时写、量的时候读，不触发重组。 */
private class LastPanel {
    var purpose = false
    var push = false
}

/**
 * 面板上的东西只露出胶囊盖着的、工具那一行上面的那一截：左右从 [left] 到 [right]，往下到 [bottom]，按它自己的坐标。
 * 不从面板底下漏到工具那一行里；开着面板收起这一组时胶囊往回收窄，也不从两边漏出去。
 */
private class AbovePill(private val left: Float, private val right: Float, private val bottom: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(left, 0f, right, bottom.coerceIn(0f, size.height)))
}

/**
 * 调节面板开着时铺在它外面的一层，看不见：手指一落下就等于点了底栏左下角的返回，面板和这一组一起收起（[onFold]）。
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

/** 面板离屏幕两边多远：和展开后的胶囊一样宽（见 EditorToolbar 的 MARGIN）。 */
private val PANEL_MARGIN = 12.dp
private val FILM_GAP = 6.dp

/** 胶囊的圆角：收着时就是两头的半圆，长高了四个角还是这么圆，看得出面板是胶囊长出来的。 */
private val PANEL_RADIUS = TOOL_ROW_HEIGHT / 2

/** 面板上的名字、参数从这儿起淡入：面板先长出一截再显出来，不和它一起挤在一条缝里。 */
private const val CONTENT_IN_START = 0.35f

/**
 * 手机竖着拿时，AI 增强识别跑完的那一下在画布顶上说一声结果，几秒后收起：底栏的圆钮上写不下字（见 [EditorToolbar]）。
 * 圆钮上只留下结果的样子：跑成了是对勾（不带数），没跑成星星是红的。
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

/**
 * 宽屏的样式列表那一块（WideEditorPanel）最底下那一行。手机上这两样都在工具那一行里（见 [EditorBottomBar]）。
 *
 * 单张时左边「AI 增强识别」，右边「导出」。批量时左边是胶片条，占掉导出左边剩下的地方，
 * 「AI 增强识别」挪到它和导出之间；按钮出现、消失时胶片条跟着一点点变宽变窄，停在中间的那张始终在中间。
 *
 * @param filmstrip 批量时的胶片条，传进来的 Modifier 定好了它的宽。单张时为 null
 * @param exportLabel 导出按钮上的字。别的应用来要图时是「完成」：导出就是把图交回去
 */
@Composable
fun EditorActionRow(
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
    @StringRes exportLabel: Int = R.string.export,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (filmstrip != null) {
            filmstrip(Modifier.weight(1f))
            Box(Modifier.animateContentSize()) { AiReviewButton(aiReview, onAiReview) }
            Spacer(Modifier.width(8.dp))
        } else {
            AiReviewButton(aiReview, onAiReview)
            Spacer(Modifier.weight(1f))
        }
        Button(onClick = onExport, enabled = !exporting) { Text(stringResource(if (exporting) R.string.exporting else exportLabel)) }
    }
}

/**
 * 「AI 增强识别」：点了才让 Gemini Nano 把整页文字再看一遍。机型不支持（也没配外部模型）时整个不出现。
 * 跑的时候三颗星绕着一个圈转，代替转圈的进度条。
 * 结果就写在按钮上，不另弹提示：新发现几处、没有新发现，或者没跑成（还能点，再试一次）。
 * 跑成了就停住不可点——同一页再跑一遍结果一样，不该让人以为多点几次能多找出点什么。
 */
@Composable
internal fun AiReviewButton(state: AiReview, onClick: () -> Unit) {
    when (state) {
        AiReview.Hidden -> Unit
        AiReview.Ready -> TextButton(onClick = onClick) {
            SparkleIcon(animating = false, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ai_review))
        }
        AiReview.Running -> TextButton(onClick = {}, enabled = false) {
            // 按钮灰掉了，星星仍着主题色，看得出它在干活
            SparkleIcon(animating = true, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ai_reviewing))
        }
        is AiReview.Done -> TextButton(
            onClick = {},
            enabled = false,
            // 这是结果，要看得清，不能像别的不可点按钮那样灰下去
            colors = ButtonDefaults.textButtonColors(disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (state.found == 0) stringResource(R.string.ai_review_none) else stringResource(R.string.ai_review_found, state.found))
        }
        AiReview.Failed -> TextButton(
            onClick = onClick,
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            SparkleIcon(animating = false, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.ai_review_failed))
        }
    }
}
