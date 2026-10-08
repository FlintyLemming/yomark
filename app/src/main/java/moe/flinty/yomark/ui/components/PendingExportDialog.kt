package moe.flinty.yomark.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKindLabels

/**
 * 导出拦截（spec §7.4）。
 *
 * 三态模型开了一个二态模型没有的风险：有候选会以 OUTLINED 状态出厂，
 * 用户没注意就导出 = 泄露。所以出厂一定会问。
 *
 * 「不再提示」是勾选框，不是第三个按钮：勾上之后点哪个按钮都照常执行，只是以后不再弹。
 * 关掉之后在 设置 › 规则 顶上能重新打开，勾上时就把这条路写在下面。
 *
 * 两个回调的参数都是「勾了不再提示」。
 */
@Composable
fun PendingExportDialog(
    pendingCount: Int,
    byKind: Map<SensitiveKind, Int>,
    onMaskAll: (stopReminding: Boolean) -> Unit,
    onExportAnyway: (stopReminding: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var stopReminding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("还有 $pendingCount 处没打码") },
        text = {
            Column {
                Text(byKind.entries.joinToString("、") { (kind, n) -> SensitiveKindLabels.plural(kind, n) })
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .toggleable(value = stopReminding, role = Role.Checkbox, onValueChange = { stopReminding = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = stopReminding, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text("不再提示")
                }
                if (stopReminding) {
                    Text(
                        "可在 设置 › 规则 里重新打开",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onMaskAll(stopReminding) }) { Text("全部打码") } },
        dismissButton = { TextButton(onClick = { onExportAnyway(stopReminding) }) { Text("仍然导出") } },
    )
}
