package moe.flinty.yomark.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.billing.Edition

/**
 * 开源版里去水印按钮打开的是它，不是 [PaywallDialog]（见 [Edition]）。
 *
 * 没解锁时给「去除水印」，点了就解锁（[onUnlock]），对话框不关、换成已去除的说明，
 * 设置 → 导出里从此多一个「无水印」。两种状态都有打赏入口。打赏页交给系统浏览器打开，
 * 应用自己不发请求。没有浏览器能接时（[openUrl] 抛异常）把网址写出来，让用户自己去开。
 */
@Composable
fun FreeEditionDialog(
    unlocked: Boolean,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
    openUrl: (String) -> Unit = LocalUriHandler.current::openUri,
) {
    var noBrowser by rememberSaveable { mutableStateOf(false) }
    val donate = {
        runCatching { openUrl(Edition.DONATE_URL) }
            .onSuccess { onDismiss() }
            .onFailure { noBrowser = true }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("全功能免费版") },
        text = {
            Column {
                Text(if (unlocked) "水印已去除，可在设置 → 导出里改回。" else "开源版可以免费去除水印。")
                Text("如果有码帮到了你，欢迎打赏。")
                if (noBrowser) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "没有找到可以打开网页的应用，请在浏览器里访问 ${Edition.DONATE_URL}",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (unlocked) {
                TextButton(onClick = { donate() }) { Text("打赏") }
            } else {
                TextButton(onClick = onUnlock) { Text("去除水印") }
            }
        },
        dismissButton = {
            if (unlocked) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            } else {
                TextButton(onClick = { donate() }) { Text("打赏") }
            }
        },
    )
}
