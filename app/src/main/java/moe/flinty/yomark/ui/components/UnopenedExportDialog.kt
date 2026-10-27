package moe.flinty.yomark.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/**
 * 批量导出、分享时还有没打开过的图（spec §7.6）。
 *
 * 胶片条上可以跳着看，难免有几张一眼都没看过。没打开过的图没识别、也没人检查过，不能替用户导出——
 * 那等于把无人值守批量从后门放了进来。所以先问：去看看，还是只导出看过的那些。
 *
 * @param opened 看过的张数。一张都没看过时没有「只导出看过的」
 * @param share 点的是分享：按钮上写「只分享看过的」
 * @param overwrite 点的是覆盖原图：按钮上写「只覆盖看过的」
 */
@Composable
fun UnopenedExportDialog(
    unopened: Int,
    opened: Int,
    onReview: () -> Unit,
    onExportOpened: () -> Unit,
    onDismiss: () -> Unit,
    share: Boolean = false,
    overwrite: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unopened_title, unopened)) },
        text = { Text(stringResource(R.string.unopened_text)) },
        confirmButton = { TextButton(onClick = onReview) { Text(stringResource(R.string.review)) } },
        dismissButton = if (opened > 0) {
            {
                val label = when {
                    share -> R.string.share_opened
                    overwrite -> R.string.overwrite_opened
                    else -> R.string.export_opened
                }
                TextButton(onClick = onExportOpened) { Text(stringResource(label, opened)) }
            }
        } else null,
    )
}
