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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import kotlin.math.roundToInt

/*
 * 编辑器里两种调节面板（打码样式、用途水印）共用的架子和行：外面那一列、宽屏上垫的底、顶上的名字和收起、
 * 一样东西上面的小标题、左边一列名字右边一条滑条的一行。两种面板长得一个样子，换着开时看着是同一块面板换了内容。
 */

/**
 * 调节面板的那一列：放不下就在里面滚动，里面的东西变多变少（换了样式、点开自定义颜色）时一点点变高变矮。
 * 手机上它是底栏的胶囊长出来的那一块里的东西（见 EditorBottomBar），宽屏上外面再垫一块底（见 [WidePanel]）。
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

/** 宽屏上的调节面板：占框列表的位置（见 WideEditorPanel），一块圆角的底，里面是 [PanelColumn]。 */
@Composable
internal fun WidePanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        PanelColumn(content = content)
    }
}

/**
 * 面板顶上：左边是面板的名字，右边是收起。宽屏上样式方块里写着当前样式的名字，那时样式面板不再写一遍（[title] 为 null）。
 *
 * @param collapseLabel 读屏念的收起按钮
 */
@Composable
internal fun PanelHeader(title: String?, onCollapse: () -> Unit, collapseLabel: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
        }
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
 * 名字和值都一行写全，不折成两行（「Mediu」「m」）：左边那一列、右边那一格按 [widths] 留。
 * 留了它们滑条剩不到 [MinSliderWidth] 时（字号调大、屏幕又窄），值挪到名字那一行的右头，滑条独占下面一行——挤成一小截的滑条拖不准。
 *
 * @param widths 名字那一列、写值那一格的宽，一个面板里的滑条用同一份（见 [rememberSliderWidths]）：滑条的头尾对齐，
 *   挤不下时一起换成上下两行
 * @param onValueChangeFinished 松手。拖动期间每一帧都走 [onChange]，要把整段拖动记成一步（撤销）的，在这里收尾。
 */
@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    widths: SliderWidths,
    modifier: Modifier = Modifier,
    steps: Int = 0,
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
        if (widths.fitOneRow(maxWidth)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(widths.label))
                Track(Modifier.weight(1f))
                Value(Modifier.width(widths.value))
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

/** 滑条那一行左边名字那一列 [label]、右边写值那一格 [value] 的宽（见 [SliderRow]、[rememberSliderWidths]）。 */
@Immutable
internal data class SliderWidths(val label: Dp, val value: Dp) {

    /** 一行宽 [row] 时名字、滑条、值还排在一行里：滑条至少留 [MinSliderWidth]。 */
    fun fitOneRow(row: Dp): Boolean = row - label - value >= MinSliderWidth

    companion object {
        /**
         * 名字最宽 [label]、值最宽 [value] 时两格各留多宽：字和滑条之间再空 [PanelSliderGap]，不贴着滑条的头尾；
         * 名字那一列至少 [PanelLabelWidth]，写值那一格至少 [PanelValueWidth]，字短的面板和原先一样。
         */
        fun fitting(label: Dp, value: Dp) =
            SliderWidths(max(label + PanelSliderGap, PanelLabelWidth), max(value + PanelSliderGap, PanelValueWidth))
    }
}

/**
 * 一个面板里滑条的名字那一列、写值那一格各留多宽：放得下 [labels] 里最长的名字、[values] 里最宽的值（量出来的，跟着字号走）。
 * 一个面板里的滑条用同一份，自定义颜色的三条也是：滑条的头尾对齐。
 *
 * @param labels 这个面板里每一条滑条的名字，一时没摆出来的也算上（自定义颜色还收着、马克笔拉框时没有粗细）：摆出来时别的滑条不挪
 * @param values 每一条会写出来的值里最宽的：档位传每一档的名字，数字传两头的（见 [endTexts]）。拖到哪儿滑条都一样长
 */
@Composable
internal fun rememberSliderWidths(labels: List<String>, values: List<String>): SliderWidths {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodyMedium
    val valueStyle = MaterialTheme.typography.labelLarge
    val density = LocalDensity.current
    return remember(labels, values, labelStyle, valueStyle, measurer, density) {
        fun widest(texts: List<String>, style: TextStyle): Dp =
            with(density) { (texts.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0).toDp() }
        SliderWidths.fitting(widest(labels, labelStyle), widest(values, valueStyle))
    }
}

/** 数字的滑条两头写的值：数字一样宽，拖到中间写出来的不比两头宽。 */
internal fun ClosedFloatingPointRange<Float>.endTexts(text: (Float) -> String): List<String> = listOf(text(start), text(endInclusive))

/** 滑条右边写的百分数。 */
internal fun percentText(fraction: Float): String = "${(fraction * 100).roundToInt()}%"

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

private val PanelLabelWidth = 72.dp
private val PanelValueWidth = 48.dp
private val PanelSliderGap = 8.dp

/**
 * 滑条至少这么长：六档之间还隔着 18 dp 上下，拖得准。
 * 默认字号下最长的英文档位（Fairly coarse）在 320 dp 宽的手机上、宽屏右边那一栏最窄时（见 wideSidePanelWidth）都还放在滑条右边。
 */
internal val MinSliderWidth = 96.dp
