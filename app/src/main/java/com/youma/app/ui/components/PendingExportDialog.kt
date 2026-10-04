package com.youma.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.SensitiveKindLabels

/**
 * 导出拦截（spec §7.4）。
 *
 * 三态模型开了一个二态模型没有的风险：有候选会以 OUTLINED 状态出厂，
 * 用户没注意就导出 = 泄露。这个对话框**不是打磨项，是这套模型成立的前提**。
 *
 * 刻意不提供「不再提示」：它是分层默认的唯一安全网，关掉它等于把
 * URL / IP / 快递单号三类彻底变成静默漏检。有留存数据后再评估。
 */
@Composable
fun PendingExportDialog(
    pendingCount: Int,
    byKind: Map<SensitiveKind, Int>,
    onMaskAll: () -> Unit,
    onExportAnyway: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还有 $pendingCount 处没打码") },
        text = {
            Text(byKind.entries.joinToString("、") { (kind, n) -> SensitiveKindLabels.plural(kind, n) })
        },
        confirmButton = { TextButton(onClick = onMaskAll) { Text("全部打码") } },
        dismissButton = { TextButton(onClick = onExportAnyway) { Text("仍然导出") } },
    )
}
