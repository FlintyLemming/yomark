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
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.R
import moe.flinty.yomark.billing.Edition

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
 * 写全 https://，末尾不带斜杠（2026-10-15 起）；打开的还是带斜杠的那个，GitHub Pages 不用再跳一次。
 *
 * @param play 上架 Play 的内购版（见 Edition）：版本号后面写一句「Play 版」，和 GitHub 上发的开源版分得开。
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
    play: Boolean = !Edition.isFree,
    openUrl: (String) -> Unit = LocalUriHandler.current::openUri,
    notify: (String) -> Unit = rememberToast(),
) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = stringResource(R.string.settings_about), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.version),
                    summary = if (play) "$version · ${stringResource(R.string.play_edition)}" else version,
                    interaction = Modifier.clickable {
                        if (debugTrace) {
                            notify(context.getString(R.string.debug_already_on))
                            return@clickable
                        }
                        taps++
                        val left = TAPS_TO_DEBUG - taps
                        when {
                            left <= 0 -> {
                                taps = 0
                                onDebugTraceChange(true)
                                notify(context.getString(R.string.debug_turned_on))
                            }
                            left <= TAPS_TO_DEBUG - 3 -> notify(context.getString(R.string.debug_taps_left, left))
                        }
                    },
                    leading = { RowIcon(Icons.Outlined.Info) },
                )
                LinkRow(
                    title = stringResource(R.string.privacy_policy),
                    summary = PRIVACY_POLICY_URL.removeSuffix("/"),
                    icon = Icons.Outlined.Policy,
                    onClick = { runCatching { openUrl(PRIVACY_POLICY_URL) } },
                )
            }
        }
        item {
            GroupSpacer()
            SettingsGroup {
                LinkRow(
                    title = stringResource(R.string.reset_settings),
                    summary = stringResource(R.string.reset_settings_summary),
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
            title = { Text(stringResource(R.string.reset_confirm_title)) },
            text = { Text(stringResource(R.string.reset_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text(stringResource(R.string.reset)) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) } },
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
