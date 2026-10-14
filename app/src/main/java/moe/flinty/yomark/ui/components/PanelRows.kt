package moe.flinty.yomark.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
    onValueChangeFinished: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(labelWidth))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(PanelValueWidth),
        )
    }
}

internal val PanelLabelWidth = 72.dp
private val PanelValueWidth = 48.dp
