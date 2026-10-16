package moe.flinty.yomark.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/*
 * 编辑器里两种调节面板（打码样式、用途水印）共用的架子和行：外面那一列、顶上的名字和收起、
 * 一样东西上面的小标题、左边一列名字右边一条滑条的一行。两种面板长得一个样子，换着开时看着是同一块面板换了内容。
 */

/**
 * 调节面板的那一列：放不下就在里面滚动，里面的东西变多变少（换了样式、点开自定义颜色）时一点点变高变矮。
 * 手机上它是底栏的胶囊长出来的那一块里的东西（见 EditorBottomBar），宽屏上是从方块长出来的那一块里的（见 WideEditorPanel）。
 */
@Composable
internal fun PanelColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .animateContentSize()
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        content = content,
    )
}

/**
 * 面板顶上：左边是面板的名字，右边是收起。
 *
 * @param collapseLabel 读屏念的收起按钮
 */
@Composable
internal fun PanelHeader(title: String, onCollapse: () -> Unit, collapseLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onCollapse) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = collapseLabel)
        }
    }
}

/** 一样东西上面的小标题，如「画法」「颜色」。 */
@Composable
internal fun PanelLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

/**
 * 名字、滑条、右边写着当前值的一行。
 *
 * 值不折成两行（「Mediu」「m」）：右边那一格留 [valueWidth]，留了它滑条剩不到 [MinSliderWidth] 时（字号调大、屏幕又窄），
 * 值挪到名字那一行的右头，滑条独占下面一行——挤成一小截的滑条拖不准。
 *
 * @param valueWidth 右边那一格的宽。写的是档位的名字时按最长的那一档留（见 [rememberValueWidth]），拖到哪一档滑条都一样长
 * @param onValueChangeFinished 松手。拖动期间每一帧都走 [onChange]，要把整段拖动记成一步（撤销）的，在这里收尾。
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    labelWidth: Dp = PanelLabelWidth,
    valueWidth: Dp = PanelValueWidth,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    @Composable
    fun Track(modifier: Modifier) = Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        onValueChangeFinished = onValueChangeFinished,
        modifier = modifier.semantics { contentDescription = label },
    )

    @Composable
    fun Value(modifier: Modifier) =
        Text(valueText, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.End, maxLines = 1, modifier = modifier)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (valueBesideSlider(maxWidth, labelWidth, valueWidth)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(labelWidth))
                Track(Modifier.weight(1f))
                Value(Modifier.width(valueWidth))
            }
        } else {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Value(Modifier.padding(start = 8.dp))
                }
                Track(Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * 右边写值的那一格要多宽：放得下 [texts] 里最长的那个，前面再空 [PanelValueGap]，不贴着滑条的头；至少 [PanelValueWidth]（数字有这么宽就够）。
 * 写的是档位的名字时传每一档的名字，拖到哪一档滑条都一样长；一个面板里几条滑条用同一个宽，右头对齐。
 */
@Composable
internal fun rememberValueWidth(texts: List<String>): Dp {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(texts, style, measurer, density) {
        val widest = texts.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
        max(with(density) { widest.toDp() } + PanelValueGap, PanelValueWidth)
    }
}

/** 一行宽 [row]，名字占 [label]、值占 [value] 时，值还放不放在滑条右边：滑条至少留 [MinSliderWidth]（见 [SliderRow]）。 */
internal fun valueBesideSlider(row: Dp, label: Dp, value: Dp): Boolean = row - label - value >= MinSliderWidth

/** 名字和开关的一行，整行都能点。开关和用途水印的「应用」一样，开着时滑块上有个对勾。 */
@Composable
internal fun PanelSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            thumbContent = if (checked) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
            } else {
                null
            },
            // 默认的对勾在白色滑块上看不见
            colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
        )
    }
}

internal val PanelLabelWidth = 72.dp
private val PanelValueWidth = 48.dp
private val PanelValueGap = 8.dp

/**
 * 滑条至少这么长：六档之间还隔着 18 dp 上下，拖得准。
 * 默认字号下最长的英文档位（Fairly coarse）在 320 dp 宽的手机上、宽屏右边那一栏最窄时（见 wideSidePanelWidth）都还放在滑条右边。
 */
internal val MinSliderWidth = 96.dp
