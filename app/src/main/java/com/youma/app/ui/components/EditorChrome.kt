package com.youma.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youma.app.core.model.MaskStyle
import com.youma.app.ui.AiReview

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onPurpose: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /** 去水印的购买入口。已购用户传 null，按钮就不出现。 */
    onRemoveWatermark: (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Filled.Undo, "撤销") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Filled.Redo, "重做") }
        Row(Modifier.weight(1f)) {}
        if (onRemoveWatermark != null) {
            IconButton(onClick = onRemoveWatermark) { Icon(Icons.Filled.WorkspacePremium, "去除水印") }
        }
        IconButton(onClick = onPurpose) { Icon(Icons.Filled.Layers, "用途水印") }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "识别设置") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
    }
}

@Composable
fun EditorBottomBar(
    style: MaskStyle,
    degradeNote: String?,
    onStyleChange: (MaskStyle) -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
    /** 批量进度，如「2 / 5」。单张时为 null。 */
    batchLabel: String? = null,
    /** 非 null 时说明后面还有图：主按钮是「下一张」而不是「导出」（spec §7.6）。 */
    onNext: (() -> Unit)? = null,
    aiReview: AiReview = AiReview.HIDDEN,
    onAiReview: () -> Unit = {},
) {
    Column(modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        StyleBar(style = style, onChange = onStyleChange, degradeNote = degradeNote)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiReviewButton(aiReview, onAiReview)
            Spacer(Modifier.weight(1f))
            if (batchLabel != null) {
                Text(batchLabel, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(12.dp))
            }
            if (onNext != null) {
                Button(onClick = onNext) { Text("下一张") }
            } else {
                Button(onClick = onExport, enabled = !exporting) { Text(if (exporting) "导出中…" else "导出") }
            }
        }
    }
}

/**
 * 「AI 复查」：点了才让 Gemini Nano 把整页文字再看一遍。机型不支持、设置里关了时整个不出现。
 * 跑完就停在「已复查」——同一页再跑一遍结果一样，不该让人以为多点几次能多找出点什么。
 */
@Composable
private fun AiReviewButton(state: AiReview, onClick: () -> Unit) {
    when (state) {
        AiReview.HIDDEN -> Unit
        AiReview.READY -> TextButton(onClick = onClick) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("AI 复查")
        }
        AiReview.RUNNING -> TextButton(onClick = {}, enabled = false) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("复查中…")
        }
        AiReview.DONE -> TextButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("已复查")
        }
    }
}
