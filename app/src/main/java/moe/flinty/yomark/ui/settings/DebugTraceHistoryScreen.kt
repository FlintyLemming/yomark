package moe.flinty.yomark.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.debug.TraceRun
import moe.flinty.yomark.ui.components.TraceRunList
import moe.flinty.yomark.ui.components.copyToClipboard
import moe.flinty.yomark.ui.components.share
import moe.flinty.yomark.ui.components.toText
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/**
 * 设置 → 开发者 → 调试记录：不用先打开一张图，也看得到之前几次识别的记录。内容和编辑页顶栏的面板是同一份。
 *
 * 记录一次一张卡片，自己就是一个长列表，套不进 [SettingsScaffold] 的 LazyColumn 里，所以这一页用普通的小标题栏。
 * 「清空」先问一句：删了就找不回来。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTraceHistoryScreen(
    runs: List<TraceRun>,
    onClear: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = modifier,
        containerColor = background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.debug_log)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) }
                },
                actions = {
                    IconButton(onClick = { copyToClipboard(context, runs.toText()) }, enabled = runs.isNotEmpty()) {
                        Icon(Icons.Outlined.ContentCopy, stringResource(R.string.copy_all))
                    }
                    IconButton(onClick = { share(context, runs.toText()) }, enabled = runs.isNotEmpty()) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.share))
                    }
                    IconButton(onClick = { confirmClear = true }, enabled = runs.isNotEmpty()) {
                        Icon(Icons.Outlined.DeleteSweep, stringResource(R.string.clear))
                    }
                },
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        val direction = LocalLayoutDirection.current
        TraceRunList(
            runs = runs,
            emptyText = stringResource(R.string.history_empty),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(direction),
                top = padding.calculateTopPadding() + 8.dp,
                end = padding.calculateEndPadding(direction),
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.clear_confirm_title)) },
            text = { Text(stringResource(R.string.clear_confirm_text, runs.size)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
