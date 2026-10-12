package moe.flinty.yomark.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import moe.flinty.yomark.BuildConfig

/**
 * 隐私权政策，Play 要求商品详情和应用内都能打开。页面源文件在 site/privacy/index.html，和落地页一起由 GitHub Pages 发布。
 * 交给系统浏览器打开，应用本身仍然没有网络权限。
 */
const val PRIVACY_POLICY_URL = "https://yomark.flinty.moe/privacy/"

/** 连点版本号几下打开调试信息。和 Android 系统设置里连点版本号打开开发者选项一样，七下。 */
internal const val TAPS_TO_DEBUG = 7

/**
 * 关于：应用的版本号、隐私权政策（原先单独一行摆在一级页上，2026-10-11 挪进来），
 * 和恢复默认设置（原先在一级页的最后一项，同日挪进来），点了先问一句。
 *
 * 版本号连点七下打开调试信息，照着 Android 系统设置里打开开发者选项的样子：点到第三下起提示还差几下，
 * 打开后一级页多一项「开发者」（开关、保留最近几次、查看调试记录都在那一页，见 [DeveloperSettingsScreen]），
 * 编辑页顶栏也多一个按钮（见 DebugTraceSheet）。
 *
 * 隐私权政策那一行把网址写在摘要里：手机上没有浏览器能接时（[openUrl] 抛异常），用户照着抄也能打开。
 *
 * @param notify 弹一句提示（连点版本号时的「还差几下」）。默认是系统的 Toast，单测里换掉。
 */
@Composable
fun AboutSettingsScreen(
    debugTrace: Boolean,
    onDebugTraceChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    version: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    openUrl: (String) -> Unit = LocalUriHandler.current::openUri,
    notify: (String) -> Unit = rememberToast(),
) {
    var taps by remember { mutableIntStateOf(0) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = "关于", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SettingsRow(
                    title = "版本",
                    summary = version,
                    interaction = Modifier.clickable {
                        if (debugTrace) {
                            notify("调试信息已经打开，在设置的「开发者」里")
                            return@clickable
                        }
                        taps++
                        val left = TAPS_TO_DEBUG - taps
                        when {
                            left <= 0 -> {
                                taps = 0
                                onDebugTraceChange(true)
                                notify("已打开调试信息，设置里多了「开发者」一项")
                            }
                            left <= TAPS_TO_DEBUG - 3 -> notify("再点 $left 次即可打开调试信息")
                        }
                    },
                    leading = { RowIcon(Icons.Outlined.Info) },
                )
                LinkRow(
                    title = "隐私权政策",
                    summary = PRIVACY_POLICY_URL.removePrefix("https://"),
                    icon = Icons.Outlined.Policy,
                    onClick = { runCatching { openUrl(PRIVACY_POLICY_URL) } },
                )
            }
        }
        item {
            GroupSpacer()
            SettingsGroup {
                LinkRow(
                    title = "恢复默认设置",
                    summary = "除外观外，全部恢复出厂值",
                    icon = Icons.Outlined.SettingsBackupRestore,
                    onClick = { confirmReset = true },
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
            title = { Text("恢复默认设置？") },
            text = { Text("除外观外，全部恢复出厂值。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
    }
}

/** 连点时一下接一下地弹：新的一句顶掉上一句，不排队。 */
@Composable
private fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        var last: Toast? = null
        { text: String ->
            last?.cancel()
            last = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
}
