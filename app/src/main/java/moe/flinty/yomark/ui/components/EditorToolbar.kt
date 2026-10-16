package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.VerticalAlignmentLine
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch
import moe.flinty.yomark.R
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskStyleInfo
import moe.flinty.yomark.ui.AiReview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** 手机底栏上的两组工具。 */
enum class ToolGroup(@StringRes val label: Int) {
    /** 盖住：色块、马赛克、模糊。 */
    REDACT(R.string.tool_group_redact),

    /** 往图上加东西：马克笔、画笔、文本、用途水印。 */
    EDIT(R.string.tool_group_edit);

    companion object {
        /** 样式归哪一组：只做标记、盖不住的马克笔、画笔、文本归「编辑」，其余归「打码」（见 MaskStyle.redacts）。 */
        fun of(style: MaskStyle): ToolGroup = if (style.redacts) REDACT else EDIT
    }
}

/**
 * 手机底栏的工具那一行，浮着：一条胶囊装「打码」「编辑」两组，右边各一颗圆钮是「AI 增强识别」和「按住对比」，整组居中。
 * 同一个容器里的是同一种东西：胶囊里的点开还有工具，圆钮点（按）了就干活。
 * 导出不在这一行，在顶栏右上角（见 EditorTopBar）：这一行只放干活时反复用的，收尾那一下不和它们挤。
 * 画布不伸到这一行底下（见 EditorScreen 的 CanvasAndPanel）：浮起来只是样子，图的最底下那一截照样看得见。
 *
 * 点一组，胶囊往两边撑满这一行，圆钮缩进去，两组让开：点的那一组的样子变成返回箭头，滑到胶囊最左边，
 * 那儿只留这一个箭头，地方都给右边一个个展开的工具。点返回（或系统返回键，见 EditorScreen）收回来。
 *
 * 样式照旧是画笔（见 EditorUiState.brush）：点一种换样式，再点当前那一种，胶囊往上长成调节面板（见 EditorBottomBar）。
 * 「编辑」里的水印点一下就长出用途水印的面板，开着时选中的是它，再点一下收起。
 * 收着时每一组画的是它眼下的样子（见 GroupButton）：马克笔盖不住，用着它的时候「编辑」换成马克笔的样子，看得出来。
 *
 * 胶囊的底子不在这里画，底栏画：面板就是它往上长出来的，长出来、缩回去都得是同一块，一圈边、一层投影。
 * 原先这一行自己画一条胶囊、面板在底下另垫一块，半路上两块的边和投影一起露出来，工具上面多一道线、面板里多一层影子。
 * 这一行只摆胶囊里的东西，胶囊眼下左右到哪儿用 [PillLeft]、[PillRight] 告诉底栏。
 *
 * 圆钮上没有字。AI 的结果在圆钮上是角标和颜色，跑完那一下画布顶上再说一声（见 AiReviewResultPill）；
 * 对比点一下没有比上，弹个提示说要按住。
 *
 * @param open 展开着的那一组，收着时为 null
 * @param onOpen 点了一组（展开它）或点了返回（null）
 * @param purposeOn 设了用途水印：读屏念「已开启」
 * @param purposeOpen 用途水印的面板开着：选中的是水印，不是当前的样式
 * @param compare 「按住对比」的按压状态：按住时画布显示打码前的原图（见 EditorScreen）。null 时没有这一颗
 * @param canCompare 有前后可比：打了码，或者设了用途水印。没有时「按住对比」灰掉
 */
@Composable
fun EditorToolbar(
    open: ToolGroup?,
    onOpen: (ToolGroup?) -> Unit,
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    purposeOn: Boolean,
    onPurpose: () -> Unit,
    modifier: Modifier = Modifier,
    purposeOpen: Boolean = false,
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
    compare: MutableInteractionSource? = null,
    canCompare: Boolean = false,
) {
    val progress = animateFloatAsState(if (open != null) 1f else 0f, foldSpec(open != null), label = "toolGroup")
    // 收回去的半路上 open 已经是 null，那一组的工具还要接着画完
    val last = remember { LastOpen() }
    if (open != null) last.group = open
    val shown = open ?: last.group
    val settledOpen by remember { derivedStateOf { progress.value == 1f } }
    val settledShut by remember { derivedStateOf { progress.value == 0f } }
    val collapsedRow = open == null || !settledOpen
    val expandedRow = shown != null && (open != null || !settledShut)
    val active = ToolGroup.of(styleBar.look.style)
    // 收着时有几颗圆钮按参数算，不按组合里有没有：展开停稳后圆钮不在组合里，胶囊收回去的位置也不能跟着变
    val circles = listOfNotNull(Slot.AI.takeIf { aiReview != AiReview.Hidden }, Slot.COMPARE.takeIf { compare != null })
    val container = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = EDGE_ALPHA)

    Layout(
        content = {
            ToolGroup.entries.forEach { group ->
                // 两组一直都在组合里：胶囊收着时多宽要按两组一起量。展开停稳后不摆出来、读屏也读不到
                GroupButton(
                    group = group,
                    inUse = styleBar.look.takeIf { group == active },
                    nearColors = styleBar.nearColors,
                    // 半路上不接着点：等这一行停稳
                    enabled = open == null && settledShut,
                    away = !collapsedRow,
                    onClick = { onOpen(group) },
                    modifier = Modifier.layoutId(group),
                )
            }
            if (expandedRow) {
                BackButton(enabled = open != null, onClick = { onOpen(null) }, modifier = Modifier.layoutId(Slot.BACK))
                VerticalDivider(
                    Modifier.layoutId(Slot.DIVIDER).height(DIVIDER_HEIGHT),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                when (shown) {
                    ToolGroup.REDACT -> RedactTools(styleBar, styleActions, purposeOpen)
                    ToolGroup.EDIT -> EditTools(styleBar, styleActions, purposeOn, purposeOpen, onPurpose)
                }
            }
            if (collapsedRow) {
                if (aiReview != AiReview.Hidden) AiReviewCircle(aiReview, onAiReview, Modifier.layoutId(Slot.AI).floatingSurface(container, edge))
                if (compare != null) CompareCircle(compare, canCompare, Modifier.layoutId(Slot.COMPARE).floatingSurface(container, edge))
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val margin = MARGIN.roundToPx()
        val inset = INSET.roundToPx()
        val gap = GAP.roundToPx()
        val rowMin = TOOL_ROW_HEIGHT.roundToPx()
        val groupParts = HashMap<ToolGroup, Measurable>()
        val circleParts = HashMap<Slot, Measurable>()
        val toolParts = ArrayList<Measurable>()
        var picked = -1
        var backPart: Measurable? = null
        var dividerPart: Measurable? = null
        for (m in measurables) when (val id = m.layoutId) {
            is ToolGroup -> groupParts[id] = m
            Slot.BACK -> backPart = m
            Slot.DIVIDER -> dividerPart = m
            Slot.TOOL -> toolParts += m
            Slot.PICKED -> {
                picked = toolParts.size
                toolParts += m
            }
            Slot.AI, Slot.COMPARE -> circleParts[id as Slot] = m
        }

        // 收着：两组各按本来的宽度，加上两头的边就是胶囊；圆钮和胶囊一样高。
        // 放不下时一步步让：先把圆钮缩小，再去掉名字后面的小箭头（见 GroupButton），最后两组按比例缩、名字截断
        val chevronRoom = CHEVRON_ROOM.roundToPx()
        val natural = ToolGroup.entries.associateWith { groupParts.getValue(it).maxIntrinsicWidth(rowMin) }
        val room = width - margin * 2
        val sizes = CIRCLE_SIZES.map { it.roundToPx() }
        fun fits(groupsWidth: Int, circle: Int) = groupsWidth + inset * 2 + circles.size * (gap + circle) <= room
        val full = natural.values.sum()
        val bare = full - chevronRoom * natural.size
        val circle = sizes.firstOrNull { fits(full, it) } ?: sizes.last()
        val chevrons = fits(full, circle)
        val squeeze = if (chevrons) 1f else ((room - circles.size * (gap + circle) - inset * 2).toFloat() / bare).coerceAtMost(1f)
        val groups = ToolGroup.entries.associateWith {
            val w = if (chevrons) natural.getValue(it) else ((natural.getValue(it) - chevronRoom) * squeeze).toInt()
            groupParts.getValue(it).measure(Constraints.fixedWidth(w.coerceAtLeast(0)))
        }
        val pillWidth = groups.values.sumOf { it.width } + inset * 2
        val shutLeft = (width - pillWidth - circles.size * (gap + circle)) / 2
        val shutRight = shutLeft + pillWidth
        val shutX = HashMap<ToolGroup, Int>()
        ToolGroup.entries.fold(shutLeft + inset) { x, group -> shutX[group] = x; x + groups.getValue(group).width }
        val circleX = HashMap<Slot, Int>()
        circles.fold(shutRight + gap) { x, slot -> circleX[slot] = x; x + circle + gap }
        val circlePlaceables = circleParts.mapValues { (_, m) -> m.measure(Constraints.fixed(circle, circle)) }

        // 展开：胶囊撑满这一行，最左边只有返回箭头，分隔线后面的工具按名字长短分剩下的（见 toolWidths）
        val openLeft = margin
        val openRight = width - margin
        val backX = openLeft + inset
        val back = backPart?.measure(Constraints())
        val backWidth = back?.width ?: 0
        // 箭头从点开的那一组的图标那儿滑过来：两样的中心对齐
        val backFrom = (GROUP_PAD_START + GLYPH_BOX / 2 - BACK_SIZE / 2).roundToPx()
        val dividerGap = DIVIDER_GAP.roundToPx()
        val divider = dividerPart?.measure(Constraints())
        val toolsStart = backX + backWidth + dividerGap * 2 + (divider?.width ?: 0)
        val toolsRoom = (openRight - inset - toolsStart).coerceAtLeast(0)
        val toolNatural = toolParts.map { it.maxIntrinsicWidth(rowMin) }
        val toolWidths = toolWidths(
            natural = toolNatural,
            room = toolsRoom,
            picked = picked,
            // 选中那一项的小箭头眼下多宽：换选中的半路上它还在长（见 NameAndChevronPolicy）
            chevron = if (picked >= 0) toolNatural[picked] - toolParts[picked].minIntrinsicWidth(rowMin) else chevronRoom,
            bare = (TOOL_PAD_QUIET * 2 + GLYPH_BOX).roundToPx(),
        )
        // 每一格和胶囊一样高：整格都能点，小胶囊在格子正中
        val tools = toolParts.mapIndexed { i, m ->
            m.measure(Constraints(minWidth = toolWidths[i], maxWidth = toolWidths[i], minHeight = rowMin))
        }
        val toolX = toolWidths.runningFold(toolsStart) { x, w -> x + w }

        val height = max(rowMin, (groups.values + tools + circlePlaceables.values + listOfNotNull(back)).maxOf { it.height })
        // 胶囊有多宽跟着动画走：每一帧重新算，交给底栏画（见 PillLeft）。
        // 孩子们约束不变，不会跟着重新量；动画也不重组
        val p = progress.value
        val left = lerp(shutLeft, openLeft, p)
        val right = lerp(shutRight, openRight, p)
        val slide = SLIDE.roundToPx()
        fun Placeable.centerY() = (height - this.height) / 2

        layout(width, height, mapOf(PillLeft to left, PillRight to right)) {
            val fadeOut = span(p, 0f, FADE_OUT)
            // 两组在前半程淡掉；展开停稳后不摆出来
            if (collapsedRow) groups.forEach { (group, placeable) ->
                placeable.placeWithLayer(shutX.getValue(group), placeable.centerY()) { alpha = 1f - fadeOut }
            }
            // 返回箭头从点开的那一组的图标那儿滑到胶囊最左边，一边滑一边显出来
            val backAt = shown?.let { lerp(shutX.getValue(it) + backFrom, backX, p) } ?: backX
            back?.let {
                it.placeWithLayer(backAt, it.centerY()) {
                    val t = span(p, BACK_IN_START, BACK_IN_END)
                    alpha = t
                    scaleX = 0.6f + 0.4f * t
                    scaleY = scaleX
                }
            }
            // 圆钮在前半程缩小、淡掉，胶囊从底下撑过去。投影画在圆钮外面：
            // 默认的淡出是把这一层先画进一块和它一样大的缓冲再叠上去，出了边的投影就被切掉，所以逐笔乘上 alpha
            circlePlaceables.forEach { (slot, placeable) ->
                placeable.placeWithLayer(circleX.getValue(slot), placeable.centerY()) {
                    alpha = 1f - fadeOut
                    scaleX = 1f - CIRCLE_SHRINK * fadeOut
                    scaleY = scaleX
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
            }
            val shift = backAt - backX
            divider?.let { it.placeWithLayer(backAt + backWidth + dividerGap, it.centerY()) { alpha = span(p, 0.45f, 0.8f) } }
            // 工具跟着返回箭头一起走，从左往右一个个淡入，各自从左边一点滑到位；收回去时倒过来，右边的先走。
            // 胶囊还没撑到的地方不露出来：最右边那几个跟着胶囊的右沿一点点现出来
            val stagger = if (tools.size > 1) (1f - TOOL_IN_START - TOOL_IN) / (tools.size - 1) else 0f
            tools.forEachIndexed { i, tool ->
                val start = TOOL_IN_START + i * stagger
                val t = span(p, start, start + TOOL_IN)
                val x = toolX[i] + shift - ((1f - t) * slide).roundToInt()
                tool.placeWithLayer(x, tool.centerY()) {
                    alpha = t
                    shape = Reveal((left - x).toFloat(), (right - x).toFloat())
                    clip = true
                }
            }
        }
    }
}

/** 收回去时还在画的那一组。只在组合时写、摆放时读，不触发重组。 */
private class LastOpen {
    var group: ToolGroup? = null
}

/** [TOOL] 是展开后的一项工具，选中的那一项是 [PICKED]（分宽度时它的名字留到最后，见 [toolWidths]）。 */
private enum class Slot { AI, COMPARE, BACK, DIVIDER, TOOL, PICKED }

/**
 * 胶囊眼下的左沿、右沿，按工具那一行自己的坐标（见 EditorToolbar）。收着、展开、半路上都不一样宽，
 * 底栏照着这两条线画胶囊（见 EditorBottomBar）。
 */
internal val PillLeft = VerticalAlignmentLine(::min)
internal val PillRight = VerticalAlignmentLine(::max)

/** [p] 落在 [start]～[end] 这一段的哪儿，0～1。 */
internal fun span(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)

/**
 * 展开后各项工具的宽：[natural] 是各项本来要多宽，[room] 是分隔线右边一共有多宽，[picked] 是选中的那一项（没有是 -1）。
 * 放得下时各按本来的宽，多出来的平分：小胶囊在各自格子的正中，几项之间的空一样大。
 *
 * 放不下时（窄屏、字调大了、英文的长名字）选中的名字最要紧，一定写全；其次是没选中的名字，从短的起能写几个写几个，
 * 写不下的只留小样子（[bare] 宽，见 [NameAndChevron]），不截断成「Water…」；最后还有地方，选中的名字后面才画小箭头（[chevron]）。
 * 都只剩小样子还放不下，才截断选中的名字。
 *
 * 换选中的那一项时，两项的本来宽度一路在变（见 ToolItem），这里每一帧重新分，旁边几项跟着挪。
 *
 * 每一项按给它的宽自己定画什么：格子够它本来的宽就画全，不够就照上面的顺序少画，所以这里分出来的和它们画出来的对得上。
 */
internal fun toolWidths(natural: List<Int>, room: Int, picked: Int, chevron: Int, bare: Int): List<Int> {
    if (natural.isEmpty()) return emptyList()
    if (natural.sum() <= room) return spread(natural, room)
    val want = natural.indices.mapTo(ArrayList()) { if (it == picked) natural[it] - chevron else bare }
    for (i in natural.indices.filter { it != picked }.sortedBy { natural[it] }) {
        if (want.sum() - bare + natural[i] > room) break
        want[i] = natural[i]
    }
    if (picked in want.indices && want.sum() + chevron <= room) want[picked] += chevron
    val over = want.sum() - room
    if (over > 0 && picked in want.indices) want[picked] = (want[picked] - over).coerceAtLeast(bare)
    return spread(want, room)
}

/** 多出来的地方平分给每一项。 */
private fun spread(want: List<Int>, room: Int): List<Int> {
    val extra = (room - want.sum()).coerceAtLeast(0) / want.size
    return want.map { it + extra }
}

/** 一项工具上胶囊盖住的那一截：从 [left] 到 [right]，按这一项自己的坐标。 */
private class Reveal(private val left: Float, private val right: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(left, 0f, right, size.height))
}

// ---------- 两组的工具 ----------

/** 打码：盖得住的几种样式，排序同样式栏（MaskStyleInfo.ORDER）。 */
private val REDACT_STYLES = MaskStyleInfo.ORDER.filter { ToolGroup.of(it) == ToolGroup.REDACT }

@Composable
private fun RedactTools(model: StyleBarModel, actions: StyleBarActions, purposeOpen: Boolean) {
    REDACT_STYLES.forEach { style -> StyleItem(style, model, actions, purposeOpen) }
}

/**
 * 编辑：马克笔、画笔是样式，和打码的几种一样是画笔（EditorUiState.brush）：都能拉框，也都能照手画，画笔还能拉箭头（见 StrokeShape）；
 * 文本也是样式：拿着它点图上一处就在那儿写字（见 EditorViewModel.onTap），写的字在它的调节面板里；
 * 水印是用途水印。点它和点了再点马克笔、画笔一样，胶囊长出它的调节面板（见 EditorBottomBar），
 * 不用先点一下选中：它不是一种样式，没有「换成它」这一步。面板开着时选中的是它，名字后面的小箭头朝下，再点一下收起。
 * 设没设只有读屏念（「已开启」），颜色和别的工具一样只看选没选中：原先设了就着主题色，没选中也像选中了（2026-10-15）。
 * 设了的话图上就有，看得见。
 */
@Composable
private fun EditTools(model: StyleBarModel, actions: StyleBarActions, purposeOn: Boolean, purposeOpen: Boolean, onPurpose: () -> Unit) {
    StyleItem(MaskStyle.MARKER, model, actions, purposeOpen)
    StyleItem(MaskStyle.PEN, model, actions, purposeOpen)
    StyleItem(MaskStyle.TEXT, model, actions, purposeOpen)
    ToolItem(
        label = stringResource(R.string.tool_watermark),
        onClick = onPurpose,
        role = Role.Button,
        selected = purposeOpen,
        // 选中就是面板开着：小箭头露出来时总是朝下
        panelOpen = true,
        stateText = if (purposeOn) stringResource(R.string.tool_on) else null,
    ) { ink ->
        Icon(PurposeWatermarkIcon, contentDescription = null, modifier = Modifier.size(GLYPH_ICON), tint = ink)
    }
}

/** 一种样式。用途水印的面板开着时选中的是水印，样式都不算选中。 */
@Composable
private fun StyleItem(style: MaskStyle, model: StyleBarModel, actions: StyleBarActions, purposeOpen: Boolean) {
    val selected = style == model.look.style && !purposeOpen
    ToolItem(
        label = stringResource(MaskStyleInfo.label(style)),
        onClick = { actions.onStyleClick(style) },
        selected = selected,
        // 没选中的也带着小箭头：换走的那一下它要缩回去，不是一下没了（见 ToolItem）
        panelOpen = model.panelOpen,
    ) { ink ->
        StyleGlyph(style, model.look.options, model.nearColors, ink = ink)
    }
}

// ---------- 按钮 ----------

/**
 * 收着时胶囊里的一组：左边是这一组眼下的样子，右边是名字，名字后面一个小箭头，说明点开里面还有东西。
 * 画笔在这一组时，样子就是画笔的样子（色块是当前的颜色，和图上的码一个颜色）；不在时是这一组自己的图标——
 * 用着马克笔时「编辑」换成马克笔的样子，看得出现在点框是在标记、不是在打码。
 * 不垫选中的底色：那是导航栏「已经在这儿了」的样子，看着像点了也没用。
 * 地方不够时（窄屏、字调大了）先不画小箭头，名字截断是最后一步（见 [LabelAndChevron]）。
 *
 * 点开后两组都让开，胶囊最左边只留一个返回箭头（见 [BackButton]）。
 *
 * @param inUse 画笔在这一组时它的样子；不在时为 null，画这一组自己的图标
 * @param away 展开停稳了：这一组不摆出来，读屏也不该读到它
 */
@Composable
private fun GroupButton(
    group: ToolGroup,
    inUse: MaskLook?,
    nearColors: List<Int>,
    enabled: Boolean,
    away: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val inUseName = inUse?.let { stringResource(MaskStyleInfo.label(it.style)) }
    Row(
        modifier
            .height(GROUP_HEIGHT)
            .clip(CircleShape)
            .then(
                if (away) Modifier.clearAndSetSemantics {}
                else Modifier.semantics { if (inUseName != null) stateDescription = inUseName },
            )
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.tool_group_expand), onClick = onClick)
            .padding(start = GROUP_PAD_START, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(GLYPH_BOX), contentAlignment = Alignment.Center) {
            when {
                inUse != null -> StyleGlyph(inUse.style, inUse.options, nearColors)
                group == ToolGroup.REDACT -> MosaicGlyph(colors.onSurfaceVariant, 18.dp)
                else -> Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(GLYPH_ICON), tint = colors.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(6.dp))
        LabelAndChevron(Modifier.weight(1f, fill = false)) {
            Text(
                stringResource(group.label),
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(CHEVRON),
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 展开后胶囊最左边的返回：只有一个箭头，地方都留给右边的工具。按下去的水波只在这一颗圆里，不碰分隔线。
 * 点它收回去（系统返回键也行，见 EditorScreen）。
 */
@Composable
private fun BackButton(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(BACK_SIZE)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = stringResource(R.string.tool_group_collapse), onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.back),
            modifier = Modifier.size(GLYPH_ICON),
            tint = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * 组名和它后面的小箭头（[content] 依次是这两样）。给的地方不够连小箭头一起放，就不画小箭头，名字用全部的地方，再不够才截断：
 * 两个字的名字截成「打…」比少一个小箭头难认得多。小箭头在名字后面 [CHEVRON_ROOM] 那一截的最右边。
 */
@Composable
private fun LabelAndChevron(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val (label, chevron) = measurables
        val room = CHEVRON_ROOM.roundToPx()
        val loose = constraints.copy(minWidth = 0)
        // 量本来多宽时（宽度不限）小箭头总放得下
        val bounded = constraints.hasBoundedWidth
        val withChevron = !bounded || label.maxIntrinsicWidth(constraints.maxHeight) + room <= constraints.maxWidth
        val text = label.measure(if (withChevron && bounded) loose.copy(maxWidth = constraints.maxWidth - room) else loose)
        val arrow = chevron.measure(Constraints())
        val width = text.width + if (withChevron) room else 0
        layout(width, max(text.height, arrow.height)) {
            text.place(0, (max(text.height, arrow.height) - text.height) / 2)
            if (withChevron) arrow.place(width - arrow.width, (max(text.height, arrow.height) - arrow.height) / 2)
        }
    }
}

/**
 * 展开后一项工具的名字和它后面的小箭头（[content] 依次是这两样，没有小箭头的只有名字），连着名字和小样子之间的空。
 *
 * 小箭头只有选中的样式露出来，照 GNav 选中时名字长出来的样子：[open] 是它那一截长出来了多少（0～1），
 * 小箭头贴着这一截的右沿，跟着它从名字后面推出来，名字盖着的那一段不露；[drift] 是它还差几成滑到位
 * （GNav 的名字在自己那一截里往左滑 8，这里停稳时贴着右沿，所以是从右边多出 8 dp 滑回来），[fade] 是它的浓淡。
 *
 * 给的地方不够时：选中的（[keep]）先不画小箭头，再截断名字——选中的名字非写不可；
 * 没选中的写不全就整个不画，只剩小样子：截成「Water…」难认，只有小样子的倒和作者给的悬浮底栏截图一个样子。
 * 不画时名字照样摆着、只是透明：读屏还念得到，点的还是整格。
 */
@Composable
private fun NameAndChevron(
    keep: Boolean,
    open: Float,
    drift: Float,
    fade: Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Layout(content, modifier, NameAndChevronPolicy(keep, open, drift, fade))
}

/**
 * [NameAndChevron] 怎么排。几样都一样就是同一个排法，重组时没变不用重新量。
 * 最窄是名字写全、不带小箭头，最宽带着眼下长出来的那一截小箭头：EditorToolbar 拿两样的差算选中那一项的小箭头眼下多宽，
 * 换选中的半路上它还在长，按满宽算会把选中的名字截掉一截（见 [toolWidths]）。
 */
private data class NameAndChevronPolicy(val keep: Boolean, val open: Float, val drift: Float, val fade: Float) : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val gap = TOOL_GLYPH_GAP.roundToPx()
        val label = measurables.first()
        val chevron = measurables.getOrNull(1)?.measure(Constraints())
        val room = if (chevron != null) chevronRoom(open) else 0
        // 量本来多宽时（宽度不限）都写得下
        val bounded = constraints.hasBoundedWidth
        val natural = label.maxIntrinsicWidth(constraints.maxHeight)
        val whole = !bounded || gap + natural <= constraints.maxWidth
        val withChevron = room > 0 && (!bounded || gap + natural + room <= constraints.maxWidth)
        val text = label.measure(if (keep && !whole) Constraints(maxWidth = (constraints.maxWidth - gap).coerceAtLeast(0)) else Constraints())
        val shown = keep || whole
        val end = gap + text.width
        val width = if (!shown) 0 else end + if (withChevron) room else 0
        val height = max(text.height, chevron?.height ?: 0)
        return layout(width, height) {
            if (shown) text.place(gap, (height - text.height) / 2) else text.placeWithLayer(0, (height - text.height) / 2) { alpha = 0f }
            if (withChevron && chevron != null) {
                val x = end + room - chevron.width + (CHEVRON_DRIFT.toPx() * drift).roundToInt()
                chevron.placeWithLayer(x, (height - chevron.height) / 2) {
                    alpha = fade
                    shape = Reveal((end - x).toFloat(), chevron.width.toFloat())
                    clip = true
                }
            }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        TOOL_GLYPH_GAP.roundToPx() + measurables.first().maxIntrinsicWidth(height)

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) =
        minIntrinsicWidth(measurables, height) + if (measurables.size > 1) chevronRoom(open) else 0
}

/** 小箭头那一截长出来 [open]（0～1）时多宽。 */
private fun Density.chevronRoom(open: Float) = (CHEVRON_ROOM.toPx() * open).roundToInt()

/**
 * 浮起来的底子：胶囊（连同从它长出来的调节面板，见 EditorBottomBar）、圆钮都是它。一圈很细的边，底下两层很淡的投影，
 * 照 Bettbox 1.19.4 的悬浮底栏：一层铺得开（模糊 30、往下 10、8% 的黑），一层贴着边（模糊 10、往下 2、4% 的黑）。
 * Android 的 elevation 投影调不了模糊和偏移，又浓又硬，这里自己画（minSdk 29，硬件加速下 setShadowLayer 什么形状都画得出）。
 * 两头是半圆，宽高一样时就是圆。投影画在自己的边界外面，外面的一层不能裁边、也不能离屏淡出（见摆圆钮的那一段）。
 */
internal fun Modifier.floatingSurface(fill: Color, edge: Color, maxRadius: Dp = Dp.Infinity) = drawWithCache {
    val radius = min(size.minDimension / 2, maxRadius.toPx())
    val shadows = SHADOWS.map { (blur, dy, alpha) ->
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = fill.toArgb()
            setShadowLayer(blur.toPx(), 0f, dy.toPx(), Color.Black.copy(alpha = alpha).toArgb())
        }
    }
    val line = EDGE_WIDTH.toPx()
    onDrawBehind {
        drawIntoCanvas { canvas ->
            shadows.forEach { canvas.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, it) }
        }
        // 边画在里面，和 Flutter 的 Border.all 一样
        drawRoundRect(
            edge,
            topLeft = Offset(line / 2, line / 2),
            size = Size(size.width - line, size.height - line),
            cornerRadius = CornerRadius(radius - line / 2),
            style = Stroke(line),
        )
    }
}

/** 两层投影：模糊、往下、黑色的浓淡（见 floatingSurface）。 */
private val SHADOWS = listOf(Triple(30.dp, 10.dp, 0.08f), Triple(10.dp, 2.dp, 0.04f))

/**
 * 「AI 增强识别」：三颗星。跑的时候三颗星转着；跑完了换成对勾、点不动；没跑成星星是红的、还能点。
 * 对勾上不挂数量角标（2026-10-15 去掉）：新发现几处，画布顶上已经说过一声。
 * 圆钮上没有字，读屏念的是宽屏按钮上那句（见 [AiReviewButton]）；跑完那一下画布顶上会说一声（见 [AiReviewResultPill]）。
 */
@Composable
private fun AiReviewCircle(state: AiReview, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val failed = state == AiReview.Failed
    val label = aiReviewLabel(state)
    Box(
        modifier
            .fillMaxSize()
            .clip(CircleShape)
            .semantics { contentDescription = label }
            // 跑着、跑完了都点不动；对勾是结果，不灰掉
            .clickable(enabled = state == AiReview.Ready || failed, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state is AiReview.Done) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(CIRCLE_ICON), tint = colors.onSurfaceVariant)
        } else {
            SparkleIcon(
                animating = state == AiReview.Running,
                modifier = Modifier.size(CIRCLE_ICON),
                tint = if (failed) colors.error else colors.primary,
            )
        }
    }
}

/** AI 增强识别眼下的状态，宽屏按钮上的那一句。 */
@Composable
internal fun aiReviewLabel(state: AiReview): String = when (state) {
    AiReview.Ready, AiReview.Hidden -> stringResource(R.string.ai_review)
    AiReview.Running -> stringResource(R.string.ai_reviewing)
    is AiReview.Done -> if (state.found == 0) stringResource(R.string.ai_review_none) else stringResource(R.string.ai_review_found, state.found)
    AiReview.Failed -> stringResource(R.string.ai_review_failed)
}

/**
 * 「按住对比」：按住时画布换成打码前的原图，松手回来（见 EditorScreen）。来回比的时候，手一松就该回到打码后的样子。
 * 圆钮上写不下「按住」：点一下（没比上）就在它上面弹个提示。长按是在比，不弹提示、也不震。没打码、也没设水印时没有可比的，灰掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompareCircle(interaction: MutableInteractionSource, enabled: Boolean, modifier: Modifier = Modifier) {
    val hint = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.compare_hold)
    // TooltipBox 把 modifier 给了它里面那一层，layoutId 和这一行给的大小要落在最外面
    Box(modifier, propagateMinConstraints = true) {
        TooltipBox(
            positionProvider = rememberHintPosition(),
            tooltip = { PlainTooltip { Text(stringResource(R.string.compare_original)) } },
            state = hint,
            focusable = false,
            enableUserInput = false,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .semantics { contentDescription = label }
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        enabled = enabled,
                        role = Role.Button,
                        onLongClick = {},
                        hapticFeedbackEnabled = false,
                        onClick = { scope.launch { hint.show() } },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Compare,
                    contentDescription = null,
                    modifier = Modifier.size(CIRCLE_ICON).alpha(if (enabled) 1f else DISABLED_ALPHA),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 提示在圆钮正上方，靠着屏幕边时往里收，不出屏。 */
@Composable
private fun rememberHintPosition(): PopupPositionProvider {
    val density = LocalDensity.current
    val space = with(density) { HINT_SPACING.roundToPx() }
    val edge = with(density) { MARGIN.roundToPx() }
    return remember(space, edge) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = anchorBounds.center.x - popupContentSize.width / 2
                return IntOffset(
                    x.coerceIn(edge, (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)),
                    anchorBounds.top - popupContentSize.height - space,
                )
            }
        }
    }
}

/**
 * 展开后的一项工具：左边是小样子，右边是名字，排成一行，和收着时胶囊里的一组一个排法。
 * 原先小样子叠在名字上面，胶囊压到 48 dp 之后两样都得缩一号，挤（2026-10-14）。并排之后名字和收着时一样大。
 *
 * 选中的垫一颗深一点的小胶囊：主题色 20% 叠在胶囊的底色上，小样子、名字、小箭头着主题色，名字加粗（照作者给的悬浮底栏截图）。
 * 截图上是粗体；labelLarge 的 Medium 在不少系统的中文字体上和常规体看不出差别。
 * 没选中的不垫底，左右只留一点空，地方让给选中的那一颗。
 *
 * 整格都能点（和胶囊一样高），按下去的水波只在小胶囊里：整格一样大的水波会碰到胶囊的上下沿。
 * 这一格多宽由 EditorToolbar 按名字长短分（见 [toolWidths]）。地方不够时选中的先去掉小箭头、实在不行再截断名字，
 * 没选中的写不全就只留小样子（见 [NameAndChevron]）。
 *
 * 换选中的那一项不是一下跳过去，照 Bettbox 1.19.4 的底栏（google_nav_bar 的 GNav，250 ms）：
 * 新选中的底色淡进来，小胶囊往两边撑开，名字后面的小箭头一边长出地方一边从右边滑进来、淡进来；
 * 换走的那一项倒过来，小箭头一下就淡没了。小样子和名字的颜色、粗细跟着换，起步快。
 * 两项一个撑开一个收窄，加起来一样宽，旁边几项跟着一点点挪（各格的宽按名字分）。
 *
 * @param panelOpen 名字后面的小箭头：再点一下长出调节面板（开着时朝下）。只有选中时露出来；null 是没有小箭头的
 * @param enabled 点不动的灰掉
 * @param stateText 读屏念的状态，如水印的「已开启」
 */
@Composable
private fun ToolItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    panelOpen: Boolean? = null,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    stateText: String? = null,
    glyph: @Composable (ink: Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = if (role == Role.RadioButton) {
        Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    }
    // 一条匀速的进度推着几样东西，各按各的曲线走，和 GNav 一个 AnimationController 推着几样一样
    val pick by animateFloatAsState(if (selected) 1f else 0f, tween(PICK_MS, easing = LinearEasing), label = "pick")
    // 底色在 GNav 里是 AnimatedContainer：从眼下的颜色按 easeOut 变过去
    val tint by animateFloatAsState(if (selected) SELECTED_TINT else 0f, tween(PICK_MS, easing = FLUTTER_EASE_OUT), label = "pickTint")
    // 撑开、收窄：GNav 的 curve（easeInCubic），选中时倒过来用，两头都是起步快
    val grow = if (selected) FLUTTER_EASE_IN_CUBIC.flipped().transform(pick) else FLUTTER_EASE_IN_CUBIC.transform(pick)
    val inked = if (selected) FLUTTER_EASE_OUT_CIRC.transform(pick) else FLUTTER_EASE_IN_EXPO.transform(pick)
    val ink = lerp(colors.onSurfaceVariant, colors.primary, inked)
    val style = MaterialTheme.typography.labelLarge
    Box(
        modifier
            .layoutId(if (selected) Slot.PICKED else Slot.TOOL)
            .then(press)
            .semantics { if (stateText != null) stateDescription = stateText }
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .height(TOOL_CHIP_HEIGHT)
                .clip(CircleShape)
                .background(colors.primary.copy(alpha = tint))
                .indication(interaction, LocalIndication.current)
                .padding(start = lerp(TOOL_PAD_QUIET, TOOL_PAD_START, grow), end = lerp(TOOL_PAD_QUIET, TOOL_PAD_END, grow)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(GLYPH_BOX), contentAlignment = Alignment.Center) { glyph(ink) }
            NameAndChevron(
                keep = selected,
                open = grow,
                drift = 1f - FLUTTER_EASE_OUT_SINE.transform(pick),
                fade = if (selected) FLUTTER_EASE_IN.transform(pick) else pick.pow(13),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Text(
                    label,
                    style = style,
                    color = ink,
                    fontWeight = lerp(style.fontWeight ?: FontWeight.Normal, FontWeight.Bold, inked),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (panelOpen != null) {
                    Icon(
                        if (panelOpen) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = null,
                        modifier = Modifier.size(CHEVRON),
                        tint = ink,
                    )
                }
            }
        }
    }
}

private const val EXPAND_MS = 350

/** Material 3 的标准曲线：起步快、落地缓。 */
private val EXPAND_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/**
 * 收回去比展开快一点，曲线是 ease-out cubic：起步快，尾巴不像标准曲线那样拖。
 * 原先收回去和展开一样 350 ms、同一条曲线，看着拖、像匀速；换成 250 ms 的 emphasized decelerate 又太快（2026-10-15）。
 */
private const val COLLAPSE_MS = 300
private val COLLAPSE_EASING = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** 展开、收回各走各的。工具那一行和样式面板（见 EditorBottomBar）都用它：两样一起收时胶囊的宽和高同步。 */
internal fun foldSpec(opening: Boolean): AnimationSpec<Float> =
    if (opening) tween(EXPAND_MS, easing = EXPAND_EASING) else tween(COLLAPSE_MS, easing = COLLAPSE_EASING)

/** 收着的那一排（两组、圆钮）在前半程让开。 */
private const val FADE_OUT = 0.5f

/** 返回箭头在这一段里显出来：两组淡到一半时开始。 */
private const val BACK_IN_START = 0.25f
private const val BACK_IN_END = 0.75f

/** 工具从这儿起一个个淡入，每个走这么一段，最后一个刚好走到头。 */
private const val TOOL_IN_START = 0.25f
private const val TOOL_IN = 0.5f

/** 胶囊展开后离屏幕两边多远；收着时整组也不比这更靠边。调节面板也是这么宽（见 EditorBottomBar）。 */
private val MARGIN = 12.dp

/** 胶囊两头里面留的边。 */
private val INSET = 8.dp

/** 胶囊和圆钮、圆钮和圆钮之间的空。 */
private val GAP = 8.dp

/**
 * 胶囊的高，也是这一行最矮的高度。原先 64，比批量时上面那一条胶片条（40）厚出一大截；压到 48 又挤：
 * 选中的小胶囊只剩 32，小样子上下各 4 dp（2026-10-14）。
 * 56 照作者给的悬浮底栏截图的比例：那张图上底栏约 62、小胶囊 44，小胶囊占底栏七成（见 [TOOL_CHIP_HEIGHT]）。
 */
internal val TOOL_ROW_HEIGHT = 56.dp

/** 圆钮和胶囊一样高；放不下就一档档缩小。 */
private val CIRCLE_SIZES = listOf(56.dp, 52.dp, 48.dp)

/** 圆钮缩进胶囊时缩到多小（按原来的比例少掉这么多）。 */
private const val CIRCLE_SHRINK = 0.4f

/** 浮起来的那一圈细边：outlineVariant 淡一半，1 dp（见 floatingSurface）。底栏画的胶囊也是这一圈（见 EditorBottomBar）。 */
internal const val EDGE_ALPHA = 0.5f
private val EDGE_WIDTH = 1.dp

private val GROUP_HEIGHT = 40.dp
private val GROUP_PAD_START = 12.dp
private val GLYPH_BOX = 24.dp

/** 返回箭头那一颗圆，和一组一样高。 */
private val BACK_SIZE = 40.dp
private val CIRCLE_ICON = 24.dp
private val DIVIDER_GAP = 6.dp
private val DIVIDER_HEIGHT = 32.dp
private val SLIDE = 24.dp

/** 展开后一项工具的小胶囊：高 40，上下各留 8 dp 的胶囊底色，占这一行七成，和截图上一样。 */
private val TOOL_CHIP_HEIGHT = 40.dp

/**
 * 选中的小胶囊：小样子左边、名字右边（含小箭头）留的空，照截图（左 16，名字后面 24，这里小箭头自己带着一点空）。
 * 没选中的不垫底，两边只留 [TOOL_PAD_QUIET]。
 */
private val TOOL_PAD_START = 16.dp
private val TOOL_PAD_END = 18.dp
private val TOOL_PAD_QUIET = 6.dp
private val TOOL_GLYPH_GAP = 8.dp

/** 选中的小胶囊是主题色叠这么浓。 */
private const val SELECTED_TINT = 0.2f

/** 换选中的那一项走多久：Bettbox 1.19.4 给 GNav 设的 250 ms（见 ToolItem）。 */
private const val PICK_MS = 250

/** 小箭头长出来时从右边多出这么远滑回来：GNav 的名字滑 8。 */
private val CHEVRON_DRIFT = 8.dp

/**
 * Flutter 的几条曲线，换选中的那一项用（google_nav_bar 用的就是这些）。
 * Compose 里同名的 easeInCubic、easeOutSine、easeOutCirc、easeInExpo 控制点和 Flutter 的不一样，照 Flutter 的抄。
 */
private val FLUTTER_EASE_IN = CubicBezierEasing(0.42f, 0f, 1f, 1f)
private val FLUTTER_EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val FLUTTER_EASE_IN_CUBIC = CubicBezierEasing(0.55f, 0.055f, 0.675f, 0.19f)
private val FLUTTER_EASE_OUT_SINE = CubicBezierEasing(0.39f, 0.575f, 0.565f, 1f)
private val FLUTTER_EASE_OUT_CIRC = CubicBezierEasing(0.075f, 0.82f, 0.165f, 1f)
private val FLUTTER_EASE_IN_EXPO = CubicBezierEasing(0.95f, 0.05f, 0.795f, 0.035f)

/** Flutter 的 Curve.flipped：把曲线倒过来走。 */
private fun Easing.flipped() = Easing { 1f - transform(1f - it) }

private val GLYPH_ICON = 20.dp
private val HINT_SPACING = 8.dp

/** 组名后面的小箭头，和它前面那一点空：地方不够时先去掉这一截。 */
private val CHEVRON = 16.dp
private val CHEVRON_ROOM = 18.dp
private const val DISABLED_ALPHA = 0.38f
