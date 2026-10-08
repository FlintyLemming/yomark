package moe.flinty.yomark.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.engine.RuleState

/*
 * 设置各页共用的部件，照 Android 16 的系统设置排：
 *
 * - 页面底色是 surfaceContainer，行是一张张浅色卡片（surfaceBright）。同一组的行之间只留一道 2 dp 的缝，
 *   组的外侧是大圆角、组内相邻处是小圆角，看上去是一整块被切开的卡片；
 * - 大标题栏，列表往上滚时收成普通标题栏；
 * - 一级页每一项前面一个彩色圆底图标（SettingsIconColors），二级页的分组标题用主题色。
 */

/** 设置一级页图标的一对颜色：圆底与图标。配色表见 [SettingsIconColors]。 */
internal class SettingsIconColor(val container: Color, val content: Color)

private val OuterCorner = 20.dp
private val InnerCorner = 4.dp
private val RowGap = 2.dp
private val CardInset = 16.dp

/** 行里内容的起点到屏幕边的距离。分组标题、页尾说明和它对齐。 */
private val TextInset = CardInset + 16.dp

/**
 * 懒加载列表里逐行排的一组（行多、要按 key 定位时用），第 [index] 行（共 [count] 行）的形状：
 * 只有整组的四个外角是大圆角。行还得自己让出卡片两侧的边距，见 [lazyGroupInset]。
 */
internal fun groupItemShape(index: Int, count: Int): Shape {
    val top = if (index == 0) OuterCorner else InnerCorner
    val bottom = if (index == count - 1) OuterCorner else InnerCorner
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 懒加载列表里逐行排的组，每行左右让出的边距；[SettingsGroup] 里的行不用，组已经让过了。 */
internal fun Modifier.lazyGroupInset(): Modifier = padding(horizontal = CardInset)

/** 同一组的行与行之间的缝。懒加载列表里逐行排时，除最后一行外每行下面垫一道。 */
@Composable
internal fun RowGapSpacer() {
    Spacer(Modifier.height(RowGap))
}

/**
 * 每一页的骨架。应用是边到边的（见 enableLightEdgeToEdge），系统栏让不让开由各屏自己管：
 * 标题栏垫在状态栏下面、固定在顶上，列表滚上去时从它底下穿过；列表一直画到屏幕底边，
 * 最后一行下面留出导航条的高度。横屏时左右再让开挖孔与三键导航。
 *
 * 各页都是自己的 Activity，这里不拦系统返回：返回由 Activity 自己 finish，预见式返回的跨 Activity 动画才放得出来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = background,
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = actions,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = background,
                    scrolledContainerColor = background,
                ),
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding(),
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            content = content,
        )
    }
}

/** 分组标题：卡片上方一行主题色的小字。页面最上面那一个传 [first]，和标题栏之间不用再空那么多。 */
@Composable
internal fun SectionHeader(text: String, modifier: Modifier = Modifier, first: Boolean = false) {
    Text(
        text,
        modifier
            .fillMaxWidth()
            .padding(start = TextInset, end = TextInset, top = if (first) 8.dp else 24.dp, bottom = 8.dp)
            .semantics { heading() },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** 页面最上面的一段说明，写这一页管什么。 */
@Composable
internal fun PageIntro(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = TextInset, end = TextInset, top = 4.dp, bottom = 16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 页面以卡片开头（上面没有分组标题、没有说明）时，卡片和标题栏之间的那点空。 */
@Composable
internal fun PageTopSpacer() {
    Spacer(Modifier.height(8.dp))
}

/** 卡片下方的说明文字。 */
@Composable
internal fun FooterText(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(start = TextInset, end = TextInset, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 组与组之间（没有分组标题时）的间距。 */
@Composable
internal fun GroupSpacer() {
    Spacer(Modifier.height(20.dp))
}

/**
 * 一组连在一起的行，行数固定、不需要懒加载时用。行自己不用传形状：
 * 每行都是小圆角，整组的外角由这里裁成大圆角。
 */
@Composable
internal fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = CardInset).clip(RoundedCornerShape(OuterCorner)),
        verticalArrangement = Arrangement.spacedBy(RowGap),
        content = content,
    )
}

/**
 * 一行卡片。[interaction] 是整行的点击语义（clickable / toggleable / selectable），由各种行自己给，
 * 这样点哪里都算，读屏也把标题、说明和开关读成一个整体。
 *
 * 放在 [SettingsGroup] 里时不用传 [shape]；懒加载列表里逐行排的组，用 [groupItemShape] 按位置给，
 * 再在 [modifier] 上加 [lazyGroupInset]。
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    interaction: Modifier = Modifier,
    summary: String? = null,
    shape: Shape = RoundedCornerShape(InnerCorner),
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .then(interaction)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(
            Modifier.fillMaxWidth().alpha(if (enabled) 1f else DisabledAlpha),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Box(Modifier.padding(end = 16.dp), contentAlignment = Alignment.Center) { leading() }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (summary != null) {
                    Text(
                        summary,
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Box(Modifier.padding(start = 12.dp), contentAlignment = Alignment.Center) { trailing() }
            }
        }
        if (below != null) {
            Box(Modifier.alpha(if (enabled) 1f else DisabledAlpha)) { below() }
        }
    }
}

private const val DisabledAlpha = 0.38f

/** 一级页的彩色图标：浅色圆底上一个同色相的深色图标。 */
@Composable
internal fun SettingsIcon(icon: ImageVector, colors: SettingsIconColor, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).background(colors.container, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(22.dp))
    }
}

/** 二级页行首的单色图标。 */
@Composable
internal fun RowIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    Icon(
        icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.size(24.dp),
    )
}

/** 点进下一页（或弹出对话框）的一行。Android 的设置首页不画右箭头，这里也不画。 */
@Composable
internal fun NavigationRow(
    title: String,
    summary: String?,
    icon: ImageVector,
    iconColors: SettingsIconColor,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsRow(
        title = title,
        summary = summary,
        modifier = modifier,
        interaction = Modifier.clickable(onClick = onClick),
        leading = { SettingsIcon(icon, iconColors) },
    )
}

/** 单选列表里的一项：行首一个单选圆点。整行都能点。 */
@Composable
internal fun RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        summary = summary,
        modifier = modifier,
        enabled = enabled,
        interaction = Modifier.selectable(
            selected = selected,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onClick,
        ),
        // 圆点按「可用」画，灰掉交给整行的透明度：圆点自己再按不可用配色画一遍，会比文字淡得多
        leading = { RadioButton(selected = selected, onClick = null) },
    )
}

/** 带开关的一行。开着时滑块里画一个对勾，和 Android 16 的系统开关一样。 */
@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
) {
    SettingsRow(
        title = title,
        summary = summary,
        modifier = modifier,
        interaction = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        leading = icon?.let { { RowIcon(it) } },
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                thumbContent = if (checked) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
                // 默认的对勾是 onPrimaryContainer：预设主题色（SchemeContent）里它是白的，画在白色滑块上就没了
                colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
            )
        },
    )
}

/**
 * 关 / 圈出 / 打码 三选一，排成一排连在一起的按钮（Material 3 Expressive 的 connected button group）：
 * 两端是半圆，相邻处小圆角，选中的那个变成整颗胶囊、填主题色。
 * 文字规则每行一个，摆在行尾，一眼扫下来就看得出每一类现在是什么状态。
 */
@Composable
internal fun RuleStateSelector(
    selected: RuleState,
    onSelect: (RuleState) -> Unit,
    modifier: Modifier = Modifier,
) {
    val states = RuleState.entries
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(RowGap)) {
        states.forEachIndexed { i, state ->
            val isSelected = state == selected
            val inner by animateDpAsState(if (isSelected) SegmentHeight / 2 else 6.dp, label = "segment corner")
            val outer = CornerSize(50)
            val start = if (i == 0) outer else CornerSize(inner)
            val end = if (i == states.lastIndex) outer else CornerSize(inner)
            val container by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                label = "segment container",
            )
            val content = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            Box(
                Modifier
                    .height(SegmentHeight)
                    .widthIn(min = 48.dp)
                    .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                    .background(container)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelect(state) })
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                CompositionLocalProvider(LocalContentColor provides content) {
                    Text(ruleStateLabel(state), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }
    }
}

private val SegmentHeight = 40.dp

internal fun ruleStateLabel(state: RuleState) = when (state) {
    RuleState.OFF -> "关"
    RuleState.OUTLINED -> "圈出"
    RuleState.MASKED -> "打码"
}
