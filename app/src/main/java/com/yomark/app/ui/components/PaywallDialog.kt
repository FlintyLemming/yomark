package com.yomark.app.ui.components

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
                Text("一次付费，永久有效，不是订阅。")
                Text("付费只去掉导出图片右下角的「有码 Yomark」水印，其他功能免费版都有。")
            }
        },
        confirmButton = { TextButton(onClick = onBuy) { Text("购买") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
