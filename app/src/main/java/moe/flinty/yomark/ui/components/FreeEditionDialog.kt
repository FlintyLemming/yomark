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
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

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
        title = { Text(stringResource(R.string.free_edition)) },
        text = {
            Column {
                Text(stringResource(if (unlocked) R.string.free_edition_unlocked_text else R.string.free_edition_text))
                Text(stringResource(R.string.free_edition_donate))
                if (noBrowser) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        stringResource(R.string.no_browser, Edition.DONATE_URL),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (unlocked) {
                TextButton(onClick = { donate() }) { Text(stringResource(R.string.donate)) }
            } else {
                TextButton(onClick = onUnlock) { Text(stringResource(R.string.remove_watermark)) }
            }
        },
        dismissButton = {
            if (unlocked) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
            } else {
                TextButton(onClick = { donate() }) { Text(stringResource(R.string.donate)) }
            }
        },
    )
}
