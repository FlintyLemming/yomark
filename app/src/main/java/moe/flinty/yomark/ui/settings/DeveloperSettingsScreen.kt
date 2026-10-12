package moe.flinty.yomark.ui.settings

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.History
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R
import moe.flinty.yomark.debug.TraceLog

/**
 * 开发者：调试信息的开关、保留最近几次、查看调试记录（2026-10-11 从「关于」里挪出来，单独一页）。
 *
 * 在「关于」里连点版本号七下打开之后，一级页才多出「开发者」这一项，照着 Android 系统设置里开发者选项的样子。
 * 开关拨回去时这一页不自己退出，只把下面几组收起来、记下的一并删掉；回到一级页，「开发者」这一项也跟着不见了。
 */
@Composable
fun DeveloperSettingsScreen(
    debugTrace: Boolean,
    onDebugTraceChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    traceCapacity: Int = TraceLog.DEFAULT_CAPACITY,
    onTraceCapacityChange: (Int) -> Unit = {},
    onOpenTraceHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.settings_developer), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.debug_info),
                    icon = Icons.Outlined.BugReport,
                    checked = debugTrace,
                    onCheckedChange = onDebugTraceChange,
                )
                if (debugTrace) {
                    LinkRow(
                        title = stringResource(R.string.debug_log),
                        summary = stringResource(R.string.debug_log_summary, traceCapacity),
                        icon = Icons.Outlined.History,
                        onClick = onOpenTraceHistory,
                    )
                }
            }
            if (!debugTrace) {
                FooterText(stringResource(R.string.debug_off_footer))
            }
        }
        if (debugTrace) {
            item {
                SectionHeader(stringResource(R.string.keep_runs))
                SettingsGroup(Modifier.selectableGroup()) {
                    TraceLog.CAPACITY_OPTIONS.forEach { count ->
                        RadioRow(
                            title = stringResource(R.string.runs_count, count),
                            selected = traceCapacity == count,
                            onClick = { onTraceCapacityChange(count) },
                        )
                    }
                }
            }
        }
    }
}
