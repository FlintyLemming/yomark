package moe.flinty.yomark.ui.components

import android.widget.Toast
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import moe.flinty.yomark.billing.BillingRepository
import moe.flinty.yomark.billing.RestoreResult

/**
 * 去水印的购买入口（spec §10）。
 *
 * 措辞要说清楚「付费买的纯粹是外观」——免费版一项隐私能力都不缺。
 * 这是避免商店评论区出现「保护隐私还要收费」的唯一办法。
 *
 * 点「购买」后对话框自己关掉：购买在 Play 的界面里进行，买完回来不该还停在这个框上。
 *
 * 「恢复购买」单独放在正文里，和「购买」分开：换机、重装过的人得一眼看出点它不会再扣钱。
 * 恢复成了购买态会变，框由调用方跟着购买态关掉（同买成）；没恢复成就把原因写在框里。
 */
@Composable
fun PaywallDialog(
    onBuy: () -> Unit,
    onRestore: suspend () -> RestoreResult,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var restoring by remember { mutableStateOf(false) }
    var restoreResult by remember { mutableStateOf<RestoreResult?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("去除水印") },
        text = {
            Column {
                Text("一次付费，永久有效，不是订阅。")
                Text("付费只去掉导出图片右下角的「有码 Yomark」水印，其他功能免费版都有。")
                Spacer(Modifier.height(16.dp))
                Text("以前买过？点「恢复购买」，不会再次扣费。")
                TextButton(
                    onClick = {
                        restoring = true
                        restoreResult = null
                        scope.launch {
                            restoreResult = onRestore()
                            restoring = false
                        }
                    },
                    enabled = !restoring,
                ) { Text(if (restoring) "正在查询…" else "恢复购买") }
                restoreMessage(restoreResult)?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onBuy()
                onDismiss()
            }) { Text("购买") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun restoreMessage(result: RestoreResult?): String? = when (result) {
    RestoreResult.NotFound -> "没有找到购买记录。请确认 Play 商店登录的是购买时用的 Google 账号。"
    RestoreResult.Unavailable -> "暂时连不上 Google Play，请联网后再试。"
    RestoreResult.Restored, null -> null
}

/**
 * 首页和编辑器的「恢复购买」都走这里。
 *
 * 查询放在页面的 lifecycleScope 里跑：恢复成了购买框会立刻关掉，框自己的协程跟着被取消，
 * 那句「已恢复」不能跟着丢。老购买恢复不放感谢页（见 [BillingRepository.restore]），
 * 没有这句提示，用户只看到框突然消失。
 */
suspend fun ComponentActivity.restorePurchase(billing: BillingRepository): RestoreResult =
    lifecycleScope.async {
        billing.restore().also {
            if (it == RestoreResult.Restored) {
                Toast.makeText(this@restorePurchase, "已恢复购买，导出不再带水印", Toast.LENGTH_SHORT).show()
            }
        }
    }.await()
