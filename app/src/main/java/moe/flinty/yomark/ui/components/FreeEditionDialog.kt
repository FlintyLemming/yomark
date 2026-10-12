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
 * 说清楚这是全功能免费版、导出没有水印，再给一个打赏入口。打赏页交给系统浏览器打开，
 * 应用自己不发请求。没有浏览器能接时（[openUrl] 抛异常）把网址写出来，让用户自己去开。
 */
@Composable
fun FreeEditionDialog(
    onDismiss: () -> Unit,
    openUrl: (String) -> Unit = LocalUriHandler.current::openUri,
) {
    var noBrowser by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.free_edition)) },
        text = {
            Column {
                Text(stringResource(R.string.free_edition_text))
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
            TextButton(onClick = {
                runCatching { openUrl(Edition.DONATE_URL) }
                    .onSuccess { onDismiss() }
                    .onFailure { noBrowser = true }
            }) { Text(stringResource(R.string.donate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
