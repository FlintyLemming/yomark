package com.youma.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youma.app.core.model.SensitiveKindLabels
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.FaceOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.RuleState
import com.youma.app.engine.SemanticOption
import com.youma.app.engine.TextEngineOption
import com.youma.app.rules.RuleCatalog

/**
 * 识别设置（2026-09-04 增补设计 §5）。
 *
 * 逐项开关，不给命名预设——预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 * 每根轴下面写清楚它的代价，但**不写「推荐」**：哪个好正是用户要自己看的。
 *
 * 改动即时生效，没有「应用」按钮：写进 DataStore，EditorViewModel 收到就重跑当前这张图。
 */
@Composable
fun RecognitionSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                Text("识别设置", style = MaterialTheme.typography.titleLarge)
            }
        }

        item {
            AxisSection(
                title = "文字识别",
                note = "PP-OCR = PaddleOCR v5 移动版，中文最准、能精确到单个字，但比 ML Kit 慢。" +
                    "其余三档是 ML Kit：两者并跑 = 拉丁与中文识别器并行取并集；拉丁识别器不认中文。",
                options = TextEngineOption.entries,
                selected = config.textEngine,
                label = ::textEngineLabel,
                onSelect = { onChange(config.copy(textEngine = it)) },
            )
        }

        item {
            AxisSection(
                title = "条码",
                note = "宽松会把解不出内容的疑似条码也框出来（布料印花、格纹容易撞上），" +
                    "但只圈不打码；严格只认解得出内容的，倾斜或失焦的收款码可能整个漏掉。",
                options = BarcodeOption.entries,
                selected = config.barcode,
                label = ::barcodeLabel,
                onSelect = { onChange(config.copy(barcode = it)) },
            )
        }

        item {
            AxisSection(
                title = "人脸",
                note = "精确模式召回更高、更慢。两种模式都只取边界框，不做任何身份特征提取。",
                options = FaceOption.entries,
                selected = config.face,
                label = ::faceLabel,
                onSelect = { onChange(config.copy(face = it)) },
            )
        }

        item {
            AxisSection(
                title = "语义判定（实验）",
                note = "规则之后，让设备上的 Gemini Nano 再看一遍整页文字，补上规则认不出的人名、地址。" +
                    "结果只圈出、标「AI」，不自动打码。只在支持 AICore 的机型上生效（如 Pixel 9 及以后），" +
                    "不支持时自动跳过；推理全在本机，本应用不联网。",
                options = SemanticOption.entries,
                selected = config.semantic,
                label = ::semanticLabel,
                onSelect = { onChange(config.copy(semantic = it)) },
            )
        }

        item {
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("规则", style = MaterialTheme.typography.titleMedium)
                Row(Modifier.weight(1f)) {}
                TextButton(onClick = { onChange(RecognitionConfig()) }) { Text("全部恢复默认") }
            }
            Text(
                "「仅圈出」的项不会自动打码，但导出前必然弹窗告知——这是分层默认的安全网。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        items(RuleCatalog.all, key = { it.id }) { rule ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    SensitiveKindLabels.display(rule.kind),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                RuleState.entries.forEach { state ->
                    FilterChip(
                        selected = RuleCatalog.stateOf(config, rule.id) == state,
                        onClick = {
                            onChange(config.withRule(rule.id, state, RuleCatalog.factoryState(rule.id)))
                        },
                        label = { Text(ruleStateLabel(state)) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> AxisSection(
    title: String,
    note: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun textEngineLabel(option: TextEngineOption) = when (option) {
    TextEngineOption.PADDLE -> "PP-OCR"
    TextEngineOption.LATIN -> "拉丁"
    TextEngineOption.CHINESE -> "中文"
    TextEngineOption.BOTH -> "两者并跑"
}

private fun barcodeLabel(option: BarcodeOption) = when (option) {
    BarcodeOption.LOOSE -> "宽松"
    BarcodeOption.STRICT -> "严格"
    BarcodeOption.OFF -> "关闭"
}

private fun faceLabel(option: FaceOption) = when (option) {
    FaceOption.FAST -> "快速"
    FaceOption.ACCURATE -> "精确"
    FaceOption.OFF -> "关闭"
}

private fun semanticLabel(option: SemanticOption) = when (option) {
    SemanticOption.GEMINI_NANO -> "Gemini Nano"
    SemanticOption.OFF -> "关闭"
}

private fun ruleStateLabel(state: RuleState) = when (state) {
    RuleState.OFF -> "关"
    RuleState.OUTLINED -> "仅圈出"
    RuleState.MASKED -> "打码"
}
