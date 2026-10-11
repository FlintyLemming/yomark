package moe.flinty.yomark.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.debug.TraceLog

/**
 * 隐私权政策，Play 要求商品详情和应用内都能打开。页面源文件在 site/privacy/index.html，和落地页一起由 GitHub Pages 发布。
 * 交给系统浏览器打开，应用本身仍然没有网络权限。
 */
const val PRIVACY_POLICY_URL = "https://yomark.flinty.moe/privacy/"

/** 连点版本号几下打开调试信息。和 Android 系统设置里连点版本号打开开发者选项一样，七下。 */
internal const val TAPS_TO_DEBUG = 7

/**
 * 关于：应用的版本号，和隐私权政策（原先单独一行摆在一级页上，2026-10-11 挪进来）。
 *
 * 版本号连点七下打开调试信息，照着 Android 系统设置里打开开发者选项的样子：点到第三下起提示还差几下，
 * 打开后这一页多一个「开发者」分组：开关、保留最近几次、查看调试记录；编辑页顶栏也多一个按钮（见 DebugTraceSheet）。
 * 藏起来是因为它只给排查问题用；不想要了把开关拨回去，整组跟着收起来，记下的也一并删掉。
 *
 * 隐私权政策那一行把网址写在摘要里：手机上没有浏览器能接时（[openUrl] 抛异常），用户照着抄也能打开。
 *
 * @param notify 弹一句提示（连点版本号时的「还差几下」）。默认是系统的 Toast，单测里换掉。
 */
@Composable
fun AboutSettingsScreen(
    debugTrace: Boolean,
    onDebugTraceChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    traceCapacity: Int = TraceLog.DEFAULT_CAPACITY,
    onTraceCapacityChange: (Int) -> Unit = {},
    onOpenTraceHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
    version: String = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
    openUrl: (String) -> Unit = LocalUriHandler.current::openUri,
    notify: (String) -> Unit = rememberToast(),
) {
    var taps by remember { mutableIntStateOf(0) }

    SettingsScaffold(title = "关于", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SettingsRow(
                    title = "版本",
                    summary = version,
                    interaction = Modifier.clickable {
                        if (debugTrace) {
                            notify("调试信息已经打开")
                            return@clickable
                        }
                        taps++
                        val left = TAPS_TO_DEBUG - taps
                        when {
                            left <= 0 -> {
                                taps = 0
                                onDebugTraceChange(true)
                                notify("已打开调试信息")
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
        if (debugTrace) {
            item {
                SectionHeader("开发者")
                SettingsGroup {
                    SwitchRow(
                        title = "调试信息",
                        summary = "编辑页顶栏多一个按钮，查看每次识别的文字识别结果、规则命中，以及 AI 增强识别的提示词和模型的原始回答",
                        icon = Icons.Outlined.BugReport,
                        checked = true,
                        onCheckedChange = onDebugTraceChange,
                    )
                    LinkRow(
                        title = "调试记录",
                        summary = "最近 $traceCapacity 次识别，换了图、重开应用都还在",
                        icon = Icons.Outlined.History,
                        onClick = onOpenTraceHistory,
                    )
                }
            }
            item {
                SectionHeader("保留最近几次识别")
                SettingsGroup(Modifier.selectableGroup()) {
                    TraceLog.CAPACITY_OPTIONS.forEach { count ->
                        RadioRow(
                            title = "$count 次",
                            selected = traceCapacity == count,
                            onClick = { onTraceCapacityChange(count) },
                        )
                    }
                }
                FooterText(
                    "记录存在应用自己的目录里，不写入系统日志、不进备份；里面有截图上识别出的原文。" +
                        "超出的旧记录自动删掉，关掉调试信息时全部删掉。",
                )
            }
        }
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
