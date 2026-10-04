package com.youma.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 去水印的购买入口（spec §10）。
 *
 * 措辞要说清楚「付费买的纯粹是外观」——免费版一项隐私能力都不缺。
 * 这是避免商店评论区出现「保护隐私还要收费」的唯一办法。
 *
 * 另有兑换码入口：[onRedeem] 返回码是否正确，正确时由调用方关掉对话框，错误时这里提示。
 */
@Composable
fun PaywallDialog(onBuy: () -> Unit, onRedeem: (String) -> Boolean, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    var wrong by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("去除水印") },
        text = {
            Column {
                Text("一次付费，永久有效，不是订阅。")
                Text("付费只去掉导出图片右下角的「有码 Youma」水印，其他功能免费版都有。")
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; wrong = false },
                        label = { Text("兑换码") },
                        singleLine = true,
                        isError = wrong,
                        supportingText = if (wrong) ({ Text("兑换码无效") }) else null,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { wrong = !onRedeem(code) },
                        enabled = code.isNotBlank(),
                    ) { Text("兑换") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onBuy) { Text("购买") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
