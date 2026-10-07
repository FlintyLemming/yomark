package com.yomark.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yomark.app.export.PurposeWatermarkStyle
import kotlin.math.roundToInt

/**
 * 用途水印的调节面板：文案、颜色、角度、透明度、密度。
 *
 * 它顶替编辑器的底栏，而不是弹一个对话框或带遮罩的底部弹层——画布留在上面不被盖住，
 * 每动一下滑条，图上的水印就跟着变，所见即导出。
 * 输入法弹起时编辑器整体让开（见 EditorScreen），打字时也看得到效果。
 */
@Composable
fun PurposeWatermarkPanel(
    text: String?,
    style: PurposeWatermarkStyle,
    onTextChange: (String) -> Unit,
    onStyleChange: (PurposeWatermarkStyle) -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 输入框自己持有正在编辑的文字：清空时 ViewModel 那边是 null（暂不加水印），
    // 但框里该是空的，而不是跳回上一句。
    var draft by remember { mutableStateOf(text.orEmpty()) }
    // 横屏时屏幕矮，面板最多占一半高度，多出来的滚动，画布总有地方留着
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp

    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("用途水印", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReset) { Text("恢复默认") }
            TextButton(onClick = onRemove) { Text("移除") }
            Spacer(Modifier.width(4.dp))
            Button(onClick = onDone) { Text("完成") }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; onTextChange(it) },
            singleLine = true,
            placeholder = { Text("如：仅供办理签证使用") },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        LabeledRow("颜色") {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PurposeWatermarkStyle.PALETTE.forEach { argb ->
                    ColorSwatch(
                        color = Color(argb),
                        name = COLOR_NAMES[argb] ?: "",
                        selected = argb == style.color,
                        onClick = { onStyleChange(style.copy(color = argb)) },
                    )
                }
            }
        }
        SliderRow(
            label = "角度",
            value = style.angle,
            range = PurposeWatermarkStyle.ANGLE_RANGE,
            // 15° 一档：角度是凭眼睛对齐的，连续值反而难停在 0°、±45° 上；档再细刻度点就密成一条线
            steps = ((PurposeWatermarkStyle.ANGLE_RANGE.endInclusive -
                PurposeWatermarkStyle.ANGLE_RANGE.start) / ANGLE_STEP).roundToInt() - 1,
            valueText = "${style.angle.roundToInt()}°",
            onChange = { onStyleChange(style.copy(angle = it)) },
        )
        SliderRow(
            // 叫「不透明度」：数字越大越显眼，和拖动方向、显示的百分比是一回事
            label = "不透明度",
            value = style.opacity,
            range = PurposeWatermarkStyle.OPACITY_RANGE,
            valueText = "${(style.opacity * 100).roundToInt()}%",
            onChange = { onStyleChange(style.copy(opacity = it)) },
        )
        SliderRow(
            label = "密度",
            value = style.density,
            range = PurposeWatermarkStyle.DENSITY_RANGE,
            valueText = densityText(style.density),
            onChange = { onStyleChange(style.copy(density = it)) },
        )
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(LABEL_WIDTH))
        content()
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    steps: Int = 0,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(LABEL_WIDTH))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.width(VALUE_WIDTH),
        )
    }
}

private fun densityText(density: Float) = when {
    density < 0.8f -> "稀疏"
    density <= 1.25f -> "适中"
    else -> "密集"
}

private const val ANGLE_STEP = 15f
private val LABEL_WIDTH = 72.dp
private val VALUE_WIDTH = 48.dp

private val COLOR_NAMES = PurposeWatermarkStyle.PALETTE.zip(
    listOf("黑色", "灰色", "白色", "红色", "蓝色", "绿色")
).toMap()
