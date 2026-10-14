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
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.layout.layoutId
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
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        // 向上回到首页，放在 Android 约定的左上角
        IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
        Row(Modifier.weight(1f)) {}
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Filled.Undo, stringResource(R.string.undo)) }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Filled.Redo, stringResource(R.string.redo)) }
        if (onRemoveWatermark != null) {
            IconButton(onClick = onRemoveWatermark) { Icon(Icons.Filled.WorkspacePremium, stringResource(if (Edition.isFree) R.string.free_edition else R.string.remove_watermark)) }
        }
        if (onPurpose != null) {
            IconButton(onClick = onPurpose) {
                Icon(
                    PurposeWatermarkIcon,
                    stringResource(if (purposeOn) R.string.purpose_watermark_on else R.string.purpose_watermark),
                    tint = if (purposeOn) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
        if (compare != null) {
            // 按住看原图、松手回来，点一下没有动作：来回比的时候，手一松就该回到打码后的样子
            IconButton(onClick = {}, enabled = canCompare, interactionSource = compare) {
                Icon(Icons.Filled.Compare, stringResource(R.string.compare_original))
            }
        }
        if (onDebug != null) {
            IconButton(onClick = onDebug) { Icon(Icons.Filled.BugReport, stringResource(R.string.debug_info)) }
        }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, stringResource(R.string.settings)) }
        if (onExport != null) {
            Spacer(Modifier.width(4.dp))
            Button(onClick = onExport, enabled = !exporting) { Text(stringResource(if (exporting) R.string.exporting else exportLabel)) }
        }
    }
}

/**
 * 手机的底栏在画布那一块里从哪儿开始（见 EditorScreen 的 CanvasAndPanel）。
 *
 * 底栏占满画布那一块的高：调节面板从工具那一行往上长出来，盖在画布上，图不跟着往上顶。
 * 画布只给这条线以下让地方；没有这条线的（宽屏的那一栏、用途水印面板）照它们自己的高让。
 */
val PanelTop = HorizontalAlignmentLine(::min)

/**
 * 手机的底栏：一行浮着的工具（见 [EditorToolbar]），它自己留两边的空。导出在顶栏右上角（见 [EditorTopBar]）。
 * 批量时胶片条单独一行，在工具上面。
 *
 * 样式的调节面板（展开一组后再点当前的样式）不另外弹一层：胶囊直接往上长成一块大圆角面板，
 * 面板的名字、参数在上面一点点显出来，工具那一行还在面板最底下，换样式不用先收起。
 * 面板盖在图上（批量时连胶片条一起盖住），画布不让地方，图不动（见 [PanelTop]）。
 * 收起：点面板右上角的箭头、按返回、或者点返回收起这一组。吸管在等取色时面板先缩回去，取完色再长出来。
 *
 * @param toolGroup 展开着的那一组，收着时为 null
 * @param compare 「按住对比」的按压状态（见 [EditorToolbar]）
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
    /** 已设用途水印：「编辑」里的水印着主题色。 */
    purposeOn: Boolean = false,
    onPurpose: () -> Unit = {},
    /** 批量时的胶片条。单张时为 null。 */
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
) {
    val wanted = styleBar.panelOpen && styleBar.colorPick == null && toolGroup != null
    val grow = animateFloatAsState(if (wanted) 1f else 0f, tween(EXPAND_MS, easing = EASING), label = "stylePanel")
    val shown by remember { derivedStateOf { grow.value > 0f } }
    val panelShown = wanted || shown
    val container = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = PANEL_EDGE_ALPHA)

    Layout(
        content = {
            filmstrip?.invoke(Modifier.layoutId(BarPart.FILM))
            if (panelShown) {
                // 面板的底子垫在工具那一行底下，和胶囊一个颜色、一样的投影，胶囊自己的边和投影淡掉（见 EditorToolbar 的 merge）。
                // 面板上没有控件的地方也接住手指：落不到底下的画布上去画框
                Box(Modifier.layoutId(BarPart.CARD).floatingSurface(container, Color.Transparent, PANEL_RADIUS).pointerInput(Unit) { swallowTouches() })
                StylePanelContent(styleBar, styleActions, Modifier.layoutId(BarPart.CONTENT))
            }
            EditorToolbar(
                open = toolGroup,
                onOpen = onToolGroup,
                styleBar = styleBar,
                styleActions = styleActions,
                purposeOn = purposeOn,
                onPurpose = onPurpose,
                aiReview = aiReview,
                onAiReview = onAiReview,
                compare = compare,
                canCompare = canCompare,
                merge = { grow.value },
                modifier = Modifier.layoutId(BarPart.ROW),
            )
            // 边画在工具上面：底子垫在工具底下，它的边被胶囊的底色盖住了
            if (panelShown) Box(Modifier.layoutId(BarPart.EDGE).floatingEdge(edge, PANEL_RADIUS))
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
        val cardWidth = (width - margin * 2).coerceAtLeast(0)
        // 面板最高长到画布那一块的顶上，再高就在面板里面滚动
        val roomAbove = (rowY - pad).coerceAtLeast(0)
        val content = part(BarPart.CONTENT)?.measure(Constraints(minWidth = cardWidth, maxWidth = cardWidth, maxHeight = roomAbove))
        val p = grow.value
        val openTop = rowY - (content?.height ?: 0)
        val cardTop = lerp(rowY, openTop, p)
        val cardHeight = height - pad - cardTop
        val card = part(BarPart.CARD)?.measure(Constraints.fixed(cardWidth, cardHeight))
        val edgePart = part(BarPart.EDGE)?.measure(Constraints.fixed(cardWidth, cardHeight))

        layout(width, height, mapOf(PanelTop to height - resting)) {
            film?.place(FILM_MARGIN.roundToPx(), rowY - filmGap - film.height)
            card?.place(margin, cardTop)
            // 名字、参数跟着面板的上沿往上走，面板还没长到的地方不露出来，后半程显出来
            content?.placeWithLayer(margin, cardTop) {
                alpha = span(p, CONTENT_IN_START, 1f)
                clip = true
                shape = TopSlice((rowY - cardTop).toFloat())
            }
            row.place(0, rowY)
            edgePart?.place(margin, cardTop)
        }
    }
}

private enum class BarPart { FILM, CARD, CONTENT, ROW, EDGE }

/** 只留最上面 [height] 那一截：面板上的东西在工具那一行上面露出来，不从面板底下漏出去。 */
private class TopSlice(private val height: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(0f, 0f, size.width, height.coerceIn(0f, size.height)))
}

/** 接住落在面板上的手指，什么也不做。 */
private suspend fun PointerInputScope.swallowTouches() = awaitEachGesture {
    awaitFirstDown().consume()
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

/** 面板的圆角：和胶囊两头的半圆一样大（胶囊高 48），长高了四个角还是这么圆，看得出是胶囊长出来的。 */
private val PANEL_RADIUS = TOOL_ROW_HEIGHT / 2

/** 面板的那一圈细边，和胶囊一样（见 EditorToolbar 的 EDGE_ALPHA）。 */
private const val PANEL_EDGE_ALPHA = 0.5f

/** 面板上的名字、参数从这儿起淡入：面板先长出一截再显出来，不和它一起挤在一条缝里。 */
private const val CONTENT_IN_START = 0.35f

/**
 * 手机竖着拿时，AI 增强识别跑完的那一下在画布顶上说一声结果，几秒后收起：底栏的圆钮上写不下字（见 [EditorToolbar]）。
 * 结果还留在圆钮上，新发现几处是角标，没跑成星星是红的。
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
 * 「AI 增强识别」：点了才让 Gemini Nano 把整页文字再看一遍。机型不支持、设置里关了时整个不出现。
 * 跑的时候两颗星一前一后地转着交错，代替转圈的进度条。
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
