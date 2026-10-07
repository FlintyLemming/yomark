package com.yomark.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
 * 编辑器里两块调节面板（用途水印、打码样式）共用的行：左边一列名字，右边是控件。
 */

/** 左边一个名字、右边随便放什么的一行。 */
@Composable
internal fun LabeledRow(
    label: String,
    modifier: Modifier = Modifier,
    labelWidth: Dp = PanelLabelWidth,
    content: @Composable () -> Unit,
) {
    Row(modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(labelWidth))
        content()
    }
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
