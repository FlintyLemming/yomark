package moe.flinty.yomark.ui.components

import androidx.annotation.StringRes
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.BrandingWatermark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import moe.flinty.yomark.R
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskSafety
import moe.flinty.yomark.render.MaskStyleInfo
import moe.flinty.yomark.ui.AiReview
import kotlin.math.max
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
 * 手机底栏的工具那一行。收着时左边「打码」「编辑」两组，挨着它们是「AI 增强识别」，最右边是 [trailing]（导出）。
 *
 * 点一组，这一行换成这一组的工具：点的那一组变成返回，滑到最左边，工具在它右边一个个展开，
 * 另一组、AI、导出让开。点返回（或系统返回键，见 EditorScreen）收回来。
 *
 * 样式照旧是画笔（见 EditorUiState.brush）：点一种换样式，再点当前那一种弹出调节面板（StyleSheet）。
 * 收着时每一组画的是它眼下的样子（见 GroupButton）：马克笔盖不住，用着它的时候「编辑」换成马克笔的样子，看得出来。
 *
 * @param open 展开着的那一组，收着时为 null
 * @param onOpen 点了一组（展开它）或点了返回（null）
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
    trailing: @Composable RowScope.() -> Unit = {},
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

    Layout(
        content = {
            ToolGroup.entries.forEach { group ->
                if (collapsedRow || (expandedRow && group == shown)) {
                    GroupButton(
                        group = group,
                        inUse = styleBar.look.takeIf { group == active },
                        nearColors = styleBar.nearColors,
                        isOpen = group == open,
                        morph = { if (group == shown) progress.value else 0f },
                        // 收回去的半路上不接着点开：等这一行停稳
                        enabled = group == open || (open == null && settledShut),
                        onClick = { onOpen(if (group == open) null else group) },
                        modifier = Modifier.layoutId(group),
                    )
                }
            }
            if (expandedRow) {
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
                if (aiReview != AiReview.Hidden) AiReviewItem(aiReview, onAiReview, Modifier.layoutId(Slot.AI))
                Row(Modifier.layoutId(Slot.TRAILING), verticalAlignment = Alignment.CenterVertically, content = trailing)
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val groupWidth = GROUP_WIDTH.roundToPx()
        val gap = DIVIDER_GAP.roundToPx()
        val groups = HashMap<ToolGroup, Placeable>()
        var divider: Placeable? = null
        val toolParts = ArrayList<Measurable>()
        for (m in measurables) when (val id = m.layoutId) {
            is ToolGroup -> groups[id] = m.measure(Constraints.fixedWidth(groupWidth))
            Slot.DIVIDER -> divider = m.measure(Constraints())
            Slot.TOOL -> toolParts += m
        }
        // 收着的那一排：两组之后是 AI（至少和一组一样宽，字长就再宽些），导出在最右边。先量导出，它总是完整的
        val groupsEnd = groupWidth * ToolGroup.entries.size
        val end = measurables.firstOrNull { it.layoutId == Slot.TRAILING }
            ?.measure(Constraints(maxWidth = (width - groupsEnd).coerceAtLeast(0)))
        val ai = measurables.firstOrNull { it.layoutId == Slot.AI }?.let {
            val room = (width - groupsEnd - (end?.width ?: 0)).coerceAtLeast(0)
            it.measure(Constraints(minWidth = groupWidth.coerceAtMost(room), maxWidth = room))
        }
        val toolsStart = groupWidth + gap * 2 + (divider?.width ?: 0)
        val toolWidth = if (toolParts.isEmpty()) 0 else ((width - toolsStart) / toolParts.size).coerceAtLeast(0)
        val tools = toolParts.map { it.measure(Constraints.fixedWidth(toolWidth)) }
        val all = groups.values + tools + listOfNotNull(divider, ai, end)
        val height = max(ROW_MIN_HEIGHT.roundToPx(), all.maxOfOrNull { it.height } ?: 0)
        val slide = SLIDE.roundToPx()
        fun Placeable.centerY() = (height - this.height) / 2

        layout(width, height) {
            // 只在摆放时读进度：动画只重新摆、不重新量，更不重组
            val p = progress.value
            val fadeOut = span(p, 0f, FADE_OUT)
            // 展开的那一组变成返回，从它原来的位置滑到最左边
            val backX = shown?.let { lerp(it.ordinal * groupWidth, 0, p) } ?: 0
            groups.forEach { (group, placeable) ->
                if (group == shown) placeable.place(backX, placeable.centerY())
                else placeable.placeWithLayer(group.ordinal * groupWidth, placeable.centerY()) { alpha = 1f - fadeOut }
            }
            ai?.let { it.placeWithLayer(groupsEnd, it.centerY()) { alpha = 1f - fadeOut } }
            divider?.let { it.placeWithLayer(backX + groupWidth + gap, it.centerY()) { alpha = span(p, 0.2f, 0.6f) } }
            // 工具跟着返回一起走，从左往右一个个淡入，各自从左边一点滑到位；收回去时倒过来，右边的先走
            val stagger = if (tools.size > 1) (1f - TOOL_IN_START - TOOL_IN) / (tools.size - 1) else 0f
            tools.forEachIndexed { i, tool ->
                val start = TOOL_IN_START + i * stagger
                val t = span(p, start, start + TOOL_IN)
                tool.placeWithLayer(backX + toolsStart + i * toolWidth - ((1f - t) * slide).roundToInt(), tool.centerY()) { alpha = t }
            }
            end?.let { it.placeWithLayer(width - it.width + (fadeOut * slide).roundToInt(), it.centerY()) { alpha = 1f - fadeOut } }
        }
    }
}

/** 收回去时还在画的那一组。只在组合时写、摆放时读，不触发重组。 */
private class LastOpen {
    var group: ToolGroup? = null
}

private enum class Slot { AI, DIVIDER, TOOL, TRAILING }

/** [p] 落在 [start]～[end] 这一段的哪儿，0～1。 */
private fun span(p: Float, start: Float, end: Float) = ((p - start) / (end - start)).coerceIn(0f, 1f)

// ---------- 两组的工具 ----------

/** 打码：盖得住的几种样式，排序同样式栏（MaskStyleInfo.ORDER）。 */
private val REDACT_STYLES = MaskStyleInfo.ORDER.filter { ToolGroup.of(it) == ToolGroup.REDACT }

@Composable
private fun RedactTools(model: StyleBarModel, actions: StyleBarActions) {
    REDACT_STYLES.forEach { style -> StyleItem(style, model, actions) }
}

/**
 * 编辑：马克笔是一种样式，和打码的几种一样是画笔；画笔还没做，先占个位置；
 * 水印是用途水印，点开它的调节面板（顶替底栏），开着时着主题色。
 */
@Composable
private fun EditTools(model: StyleBarModel, actions: StyleBarActions, purposeOn: Boolean, onPurpose: () -> Unit) {
    StyleItem(MaskStyle.MARKER, model, actions)
    ToolItem(label = stringResource(R.string.tool_pen), onClick = {}, enabled = false, role = Role.Button) {
        Icon(Icons.Outlined.Gesture, contentDescription = null, modifier = Modifier.size(GLYPH_ICON), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ToolItem(
        label = stringResource(R.string.tool_watermark),
        onClick = onPurpose,
        role = Role.Button,
        on = purposeOn,
        stateText = if (purposeOn) stringResource(R.string.tool_on) else null,
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.BrandingWatermark,
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
 * 收着时的一组：上面是这一组眼下的样子，下面是名字，名字后面一个小箭头，说明点开里面还有东西。
 * 画笔在这一组时，样子就是画笔的样子（色块是当前的颜色，和图上的码一个颜色）；不在时是这一组自己的图标——
 * 用着马克笔时「编辑」换成马克笔的样子，看得出现在点框是在标记、不是在打码。
 * 不垫选中的胶囊：那是导航栏「已经在这儿了」的样子，看着像点了也没用。
 *
 * 展开后它就是返回：样子换成返回箭头，小箭头褪掉，名字留着，看得出现在在哪一组里。
 *
 * @param inUse 画笔在这一组时它的样子；不在时为 null，画这一组自己的图标
 * @param morph 变成返回走到哪儿了，0～1。只在绘制时读
 */
@Composable
private fun GroupButton(
    group: ToolGroup,
    inUse: MaskLook?,
    nearColors: List<Int>,
    isOpen: Boolean,
    morph: () -> Float,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val inUseName = inUse?.let { stringResource(MaskStyleInfo.label(it.style)) }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .semantics { if (!isOpen && inUseName != null) stateDescription = inUseName }
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClickLabel = stringResource(if (isOpen) R.string.tool_group_collapse else R.string.tool_group_expand),
                onClick = onClick,
            )
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(PILL_WIDTH, PILL_HEIGHT), contentAlignment = Alignment.Center) {
            Box(Modifier.graphicsLayer { alpha = 1f - span(morph(), 0f, FADE_OUT) }) {
                when {
                    inUse != null -> StyleGlyph(inUse.style, inUse.options, nearColors)
                    group == ToolGroup.REDACT -> MosaicGlyph(colors.onSurfaceVariant, 18.dp)
                    else -> Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(GLYPH_ICON), tint = colors.onSurfaceVariant)
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = if (isOpen) stringResource(R.string.back) else null,
                modifier = Modifier
                    .size(GLYPH_ICON)
                    .graphicsLayer {
                        val t = span(morph(), 0.4f, 1f)
                        alpha = t
                        scaleX = 0.6f + 0.4f * t
                        scaleY = scaleX
                    },
                tint = colors.onSurface,
            )
        }
        Spacer(Modifier.height(4.dp))
        // 小箭头挂在名字右边，左边空出一样宽，名字还在图标正下方；展开后箭头褪掉，名字不挪
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(CHEVRON))
            Text(
                stringResource(group.label),
                style = MaterialTheme.typography.labelMedium,
                color = if (isOpen) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .size(CHEVRON)
                    .graphicsLayer { alpha = 1f - span(morph(), 0f, FADE_OUT) },
                tint = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 「AI 增强识别」在这一行里和两组并排，一个样子：上面是星星，下面一行字。结果就写在字上，和宽屏的 [AiReviewButton] 一样：
 * 跑的时候两颗星转着，跑完了说新发现几处、停住不可点，没跑成是红的、还能点。
 */
@Composable
private fun AiReviewItem(state: AiReview, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val failed = state == AiReview.Failed
    val label = when (state) {
        AiReview.Ready, AiReview.Hidden -> stringResource(R.string.ai_review)
        AiReview.Running -> stringResource(R.string.ai_reviewing)
        is AiReview.Done -> if (state.found == 0) stringResource(R.string.ai_review_none) else stringResource(R.string.ai_review_found, state.found)
        AiReview.Failed -> stringResource(R.string.ai_review_failed)
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            // 跑着、跑完了都点不动，但字是结果，要看得清，不灰掉
            .clickable(enabled = state == AiReview.Ready || failed, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(PILL_WIDTH, PILL_HEIGHT), contentAlignment = Alignment.Center) {
            if (state is AiReview.Done) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(GLYPH_ICON), tint = colors.onSurfaceVariant)
            } else {
                SparkleIcon(
                    animating = state == AiReview.Running,
                    modifier = Modifier.size(GLYPH_ICON),
                    tint = if (failed) colors.error else colors.primary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) colors.error else colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 展开后的一项工具，和原先样式栏上的一项一个样子：上面是小样子，下面是名字，选中的垫一颗胶囊。
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
    val press = if (role == Role.RadioButton) {
        Modifier.selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier.clickable(enabled = enabled, role = role, onClick = onClick)
    }
    Column(
        modifier
            .layoutId(Slot.TOOL)
            .clip(RoundedCornerShape(16.dp))
            .then(press)
            .semantics { if (stateText != null) stateDescription = stateText }
            .padding(vertical = 6.dp)
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(PILL_WIDTH, PILL_HEIGHT)
                .background(if (selected) colors.secondaryContainer else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) { glyph() }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
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
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

private const val EXPAND_MS = 350

/** Material 3 的标准曲线：起步快、落地缓，展开收起都用它。 */
private val EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

/** 收着的那一排（另一组、右边的按钮）在前半程让开。 */
private const val FADE_OUT = 0.5f

/** 工具从这儿起一个个淡入，每个走这么一段，最后一个刚好走到头。 */
private const val TOOL_IN_START = 0.25f
private const val TOOL_IN = 0.5f

private val GROUP_WIDTH = 72.dp
private val DIVIDER_GAP = 6.dp
private val DIVIDER_HEIGHT = 32.dp
private val SLIDE = 24.dp
private val ROW_MIN_HEIGHT = 64.dp
private val PILL_WIDTH = 52.dp
private val PILL_HEIGHT = 32.dp
private val GLYPH_ICON = 20.dp

/** 组名后面的小箭头。 */
private val CHEVRON = 14.dp
private const val DISABLED_ALPHA = 0.38f
