package com.dama.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dama.app.core.model.MaskStyle

@Composable
fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onUndo, enabled = canUndo) { Icon(Icons.Filled.Undo, "撤销") }
        IconButton(onClick = onRedo, enabled = canRedo) { Icon(Icons.Filled.Redo, "重做") }
        Row(Modifier.weight(1f)) {}
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭") }
    }
}

@Composable
fun EditorBottomBar(
    style: MaskStyle,
    implemented: Set<MaskStyle>,
    onStyleChange: (MaskStyle) -> Unit,
    onExport: () -> Unit,
    exporting: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // M1 只有实色块；M4 补齐后这里自然列出全部六种
            implemented.forEach { s ->
                Button(onClick = { onStyleChange(s) }, enabled = s != style) { Text(s.label()) }
            }
        }
        Button(onClick = onExport, enabled = !exporting) { Text(if (exporting) "导出中…" else "导出") }
    }
}

private fun MaskStyle.label() = when (this) {
    MaskStyle.SOLID -> "实色块"
    MaskStyle.PIXELATE -> "像素化"
    MaskStyle.BLUR -> "模糊"
    MaskStyle.MARKER -> "马克笔"
    MaskStyle.EMOJI -> "Emoji"
    MaskStyle.ERASE -> "抹除"
}
