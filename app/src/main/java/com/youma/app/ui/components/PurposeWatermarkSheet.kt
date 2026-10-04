package com.youma.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 用途水印的文案输入。默认给一句常见措辞，用户改中间那几个字即可。 */
@Composable
fun PurposeWatermarkSheet(
    initial: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial ?: "仅供办理 XX 使用") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("用途水印") },
        text = {
            Column {
                Text("在整张图上铺满一行字，写明这张图的用途，防止被挪用。")
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.ifBlank { null }) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = { onConfirm(null) }) { Text("不加") } },
    )
}
