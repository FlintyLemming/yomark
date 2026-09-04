package com.dama.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 去水印的购买入口（spec §10）。
 *
 * 措辞要说清楚「付费买的纯粹是外观」——免费版一项隐私能力都不缺。
 * 这是避免商店评论区出现「保护隐私还要收费」的唯一办法。
 */
@Composable
fun PaywallDialog(onBuy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("去除水印") },
        text = {
            Column {
                Text("一次性买断，不是订阅。")
                Text(
                    "免费版的隐私能力一项都不缺：完整识别、三态编辑、六种打码样式、" +
                        "原图分辨率导出、元数据清除。付费去掉的只是导出图右下角的 DAMA 标记。"
                )
            }
        },
        confirmButton = { TextButton(onClick = onBuy) { Text("购买") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("以后再说") } },
    )
}
