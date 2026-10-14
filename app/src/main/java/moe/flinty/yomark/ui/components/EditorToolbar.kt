package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.launch
import moe.flinty.yomark.R
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskSafety
import moe.flinty.yomark.render.MaskStyleInfo
import moe.flinty.yomark.ui.AiReview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 手机底栏上的两组工具。 */
enum class ToolGroup(@StringRes val label: Int) {
    /** 盖住：色块、马赛克、模糊。 */
    REDACT(R.string.tool_group_redact),

    /** 往图上加东西：马克笔、画笔、用途水印。 */
    EDIT(R.string.tool_group_edit);

    companion object {
        /** 样式归哪一组：只做标记、盖不住的马克笔归「编辑」，其余归「打码」。 */
        fun of(style: MaskStyle): ToolGroup =
            if (MaskStyleInfo.safety(style) == MaskSafety.ANNOTATION_ONLY) EDIT else REDACT
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
 * 收着时每一组画的是它眼下的样子（见 GroupButton）：马克笔盖不住，用着它的时候「编辑」换成马克笔的样子，看得出来。
 *
 * 圆钮上没有字。AI 的结果在圆钮上是角标和颜色，跑完那一下画布顶上再说一声（见 AiReviewResultPill）；
 * 对比点一下没有比上，弹个提示说要按住。
 *
 * @param open 展开着的那一组，收着时为 null
 * @param onOpen 点了一组（展开它）或点了返回（null）
 * @param compare 「按住对比」的按压状态：按住时画布显示打码前的原图（见 EditorScreen）。null 时没有这一颗
 * @param canCompare 有前后可比：打了码，或者设了用途水印。没有时「按住对比」灰掉
 * @param merge 调节面板从胶囊往上长出来了多少，0～1（见 EditorBottomBar）：面板的底子垫在胶囊底下，
 *   胶囊自己的边和投影跟着淡掉，看上去是一整块。只在画的时候读，动起来不重组
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
    aiReview: AiReview = AiReview.Hidden,
    onAiReview: () -> Unit = {},
    compare: MutableInteractionSource? = null,
    canCompare: Boolean = false,
    merge: () -> Float = { 0f },
) {
    val progress = animateFloatAsState(
        if (open != null) 1f else 0f,
        tween(EXPAND_MS, easing = EASING),
        label = "toolGroup",
    )
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
            Box(Modifier.layoutId(Slot.PILL).floatingSurface(container, edge, chrome = { 1f - merge() }))
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
                    ToolGroup.REDACT -> RedactTools(styleBar, styleActions)
                    ToolGroup.EDIT -> EditTools(styleBar, styleActions, purposeOn, onPurpose)
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
        var pillPart: Measurable? = null
        var backPart: Measurable? = null
        var dividerPart: Measurable? = null
        for (m in measurables) when (val id = m.layoutId) {
            is ToolGroup -> groupParts[id] = m
            Slot.PILL -> pillPart = m
            Slot.BACK -> backPart = m
            Slot.DIVIDER -> dividerPart = m
            Slot.TOOL -> toolParts += m
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

        // 展开：胶囊撑满这一行，最左边只有返回箭头，分隔线后面的工具平分剩下的
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
        val toolWidth = if (toolParts.isEmpty()) 0 else ((openRight - inset - toolsStart) / toolParts.size).coerceAtLeast(0)
        val tools = toolParts.map { it.measure(Constraints.fixedWidth(toolWidth)) }

        val height = max(rowMin, (groups.values + tools + circlePlaceables.values + listOfNotNull(back)).maxOf { it.height })
        // 胶囊有多宽跟着动画走：每一帧重新量它（它自己画边和投影，见 floatingSurface）。
        // 别的孩子约束不变，不会跟着重新量；动画也不重组
        val p = progress.value
        val left = lerp(shutLeft, openLeft, p)
        val right = lerp(shutRight, openRight, p)
        val pill = pillPart!!.measure(Constraints.fixed((right - left).coerceAtLeast(0), height))
        val slide = SLIDE.roundToPx()
        fun Placeable.centerY() = (height - this.height) / 2

        layout(width, height) {
            val fadeOut = span(p, 0f, FADE_OUT)
            pill.place(left, 0)
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
                val x = toolsStart + shift + i * toolWidth - ((1f - t) * slide).roundToInt()
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

private enum class Slot { PILL, AI, COMPARE, BACK, DIVIDER, TOOL }

/** [p] 落在 [start]～[end] 这一段的哪儿，0～1。 */
internal fun span(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)

/** 一项工具上胶囊盖住的那一截：从 [left] 到 [right]，按这一项自己的坐标。 */
private class Reveal(private val left: Float, private val right: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density) =
        Outline.Rectangle(Rect(left, 0f, right, size.height))
}

// ---------- 两组的工具 ----------

/** 打码：盖得住的几种样式，排序同样式栏（MaskStyleInfo.ORDER）。 */
private val REDACT_STYLES = MaskStyleInfo.ORDER.filter { ToolGroup.of(it) == ToolGroup.REDACT }

@Composable
private fun RedactTools(model: StyleBarModel, actions: StyleBarActions) {
    REDACT_STYLES.forEach { style -> StyleItem(style, model, actions) }
}

/**
 * 编辑：马克笔、画笔是样式，和打码的几种一样是画笔（EditorUiState.brush）：都能拉框，也都能照手画（见 StrokeShape）；
 * 水印是用途水印，点开它的调节面板（顶替底栏），开着时着主题色。
 */
@Composable
private fun EditTools(model: StyleBarModel, actions: StyleBarActions, purposeOn: Boolean, onPurpose: () -> Unit) {
    StyleItem(MaskStyle.MARKER, model, actions)
    StyleItem(MaskStyle.PEN, model, actions)
    ToolItem(
        label = stringResource(R.string.tool_watermark),
        onClick = onPurpose,
        role = Role.Button,
        on = purposeOn,
        stateText = if (purposeOn) stringResource(R.string.tool_on) else null,
    ) {
        Icon(
            PurposeWatermarkIcon,
            contentDescription = null,
            modifier = Modifier.size(GLYPH_ICON),
            tint = if (purposeOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StyleItem(style: MaskStyle, model: StyleBarModel, actions: StyleBarActions) {
    val selected = style == model.look.style
    ToolItem(
        label = stringResource(MaskStyleInfo.label(style)),
        onClick = { actions.onStyleClick(style) },
        selected = selected,
        panelOpen = if (selected) model.panelOpen else null,
    ) {
        StyleGlyph(style, model.look.options, model.nearColors)
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
 * 浮起来的底子：胶囊、圆钮都是它。一圈很细的边，底下两层很淡的投影，照 Bettbox 1.19.4 的悬浮底栏：
 * 一层铺得开（模糊 30、往下 10、8% 的黑），一层贴着边（模糊 10、往下 2、4% 的黑）。
 * Android 的 elevation 投影调不了模糊和偏移，又浓又硬，这里自己画（minSdk 29，硬件加速下 setShadowLayer 什么形状都画得出）。
 * 两头是半圆，宽高一样时就是圆。投影画在自己的边界外面，外面的一层不能裁边、也不能离屏淡出（见摆圆钮的那一段）。
 */
internal fun Modifier.floatingSurface(
    fill: Color,
    edge: Color,
    maxRadius: Dp = Dp.Infinity,
    chrome: () -> Float = { 1f },
) = drawWithCache {
    val radius = min(size.minDimension / 2, maxRadius.toPx())
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
    val line = EDGE_WIDTH.toPx()
    onDrawBehind {
        // 边和投影的浓淡（见 EditorToolbar 的 merge）每一帧都可能不同，投影在画的时候设
        val a = chrome().coerceIn(0f, 1f)
        drawIntoCanvas {
            for ((blur, dy, alpha) in SHADOWS) {
                paint.setShadowLayer(blur.toPx(), 0f, dy.toPx(), Color.Black.copy(alpha = alpha * a).toArgb())
                it.nativeCanvas.drawRoundRect(0f, 0f, size.width, size.height, radius, radius, paint)
            }
        }
        if (a > 0f) drawFloatingEdge(edge.copy(alpha = edge.alpha * a), radius, line)
    }
}

/** 浮起来的那一圈细边，单独画：调节面板的边画在底栏的工具上面（见 EditorBottomBar）。 */
internal fun Modifier.floatingEdge(edge: Color, maxRadius: Dp = Dp.Infinity) = drawBehind {
    drawFloatingEdge(edge, min(size.minDimension / 2, maxRadius.toPx()), EDGE_WIDTH.toPx())
}

// 边画在里面，和 Flutter 的 Border.all 一样
private fun DrawScope.drawFloatingEdge(edge: Color, radius: Float, line: Float) = drawRoundRect(
    edge,
    topLeft = Offset(line / 2, line / 2),
    size = Size(size.width - line, size.height - line),
    cornerRadius = CornerRadius(radius - line / 2),
    style = Stroke(line),
)

/** 两层投影：模糊、往下、黑色的浓淡（见 floatingSurface）。 */
private val SHADOWS = listOf(Triple(30.dp, 10.dp, 0.08f), Triple(10.dp, 2.dp, 0.04f))

/**
 * 「AI 增强识别」：两颗星。跑的时候两颗星转着；跑完了换成对勾、点不动，新发现几处是角标上的数；没跑成星星是红的、还能点。
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
            // 跑着、跑完了都点不动；对勾和角标是结果，不灰掉
            .clickable(enabled = state == AiReview.Ready || failed, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (state is AiReview.Done) {
            BadgedBox(badge = { if (state.found > 0) Badge { Text("${state.found}") } }) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(CIRCLE_ICON), tint = colors.onSurfaceVariant)
            }
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
 * 展开后的一项工具，和原先样式栏上的一项一个样子：上面是小样子，下面是名字，选中的垫一颗小胶囊。
 * 按下去的水波只在小胶囊里，和导航栏一样：整项一样大的水波会碰到胶囊的上下沿。
 *
 * @param panelOpen 选中的样式名字后面的小箭头：再点一下弹出调节面板（开着时朝下）。null 不画
 * @param enabled 还没做好的（画笔）灰掉、点不动
 * @param on 开着的开关（水印）：名字着主题色
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
    on: Boolean = false,
    stateText: String? = null,
    glyph: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val press = if (role == Role.RadioButton) {
        Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
    }
    Column(
        modifier
            .layoutId(Slot.TOOL)
            .then(press)
            .semantics { if (stateText != null) stateDescription = stateText }
            .padding(vertical = TOOL_PAD)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(INDICATOR_WIDTH, INDICATOR_HEIGHT)
                .clip(CircleShape)
                .background(if (selected) colors.secondaryContainer else Color.Transparent)
                .indication(interaction, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) { glyph() }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    on -> colors.primary
                    selected -> colors.onSurface
                    else -> colors.onSurfaceVariant
                },
                maxLines = 1,
            )
            if (panelOpen != null) {
                Icon(
                    if (panelOpen) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

internal const val EXPAND_MS = 350

/** Material 3 的标准曲线：起步快、落地缓，展开收起都用它。 */
internal val EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

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
 * 胶囊的高，也是这一行最矮的高度。原先 64，比批量时上面那一条胶片条（40）厚出一大截，压到 48：
 * 展开后的工具小样子、名字都缩一号，刚好放得下（见 [ToolItem]）。
 */
internal val TOOL_ROW_HEIGHT = 48.dp

/** 圆钮和胶囊一样高；放不下就一档档缩小。 */
private val CIRCLE_SIZES = listOf(48.dp, 44.dp, 40.dp)

/** 圆钮缩进胶囊时缩到多小（按原来的比例少掉这么多）。 */
private const val CIRCLE_SHRINK = 0.4f

/** 浮起来的那一圈细边：outlineVariant 淡一半，1 dp（见 floatingSurface）。 */
private const val EDGE_ALPHA = 0.5f
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
private val INDICATOR_WIDTH = 48.dp
private val INDICATOR_HEIGHT = 24.dp

/** 展开后一项工具上下留的空：3 + 小样子 24 + 2 + 一行小字 16 + 3，正好是胶囊的 48。 */
private val TOOL_PAD = 3.dp
private val GLYPH_ICON = 20.dp
private val HINT_SPACING = 8.dp

/** 组名后面的小箭头，和它前面那一点空：地方不够时先去掉这一截。 */
private val CHEVRON = 16.dp
private val CHEVRON_ROOM = 18.dp
private const val DISABLED_ALPHA = 0.38f
