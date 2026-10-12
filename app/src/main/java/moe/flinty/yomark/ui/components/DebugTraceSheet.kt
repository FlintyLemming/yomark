package moe.flinty.yomark.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import moe.flinty.yomark.debug.TraceBlock
import moe.flinty.yomark.debug.TraceRun
import moe.flinty.yomark.debug.TraceSection
import moe.flinty.yomark.debug.heading
import moe.flinty.yomark.debug.subheading
import moe.flinty.yomark.debug.toText

/**
 * 调试信息（设置 → 关于，连点版本号打开之后才有）：最近几次识别在各环节看到了什么。
 * 从编辑页顶栏的按钮弹出，和手机上的样式面板一样，是盖在画布上的模态底部动作条。
 *
 * 最新的一次在最上面、默认展开；提示词、模型的回答、堆栈这类大段原文默认收起，点开再看。文字都能长按选择。
 * 「复制」把整份记录作为纯文本放进剪贴板，「分享」交给系统的分享面板，贴给别人看、发到电脑上都行。
 *
 * @param onRerun 当前这张图再识别一遍；没有图、或者正在识别时为 null，按钮灰掉。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugTraceSheet(
    runs: List<TraceRun>,
    onRerun: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        // 和样式面板一样：比屏幕还高时顶到状态栏下面为止，多出来的在里面滚动
        modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
    ) {
        DarkSystemBarIcons()
        DebugTraceContent(
            runs = runs,
            onRerun = onRerun,
            onCopy = { copyToClipboard(context, it) },
            onShare = { share(context, it) },
        )
    }
}

/** 面板里的东西，拆出来好单测：模态底部动作条在 Robolectric 上要多开一个窗口。 */
@Composable
internal fun DebugTraceContent(
    runs: List<TraceRun>,
    onRerun: (() -> Unit)?,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("调试信息", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = { onRerun?.invoke() }, enabled = onRerun != null) { Text("重新识别") }
            IconButton(onClick = { onCopy(runs.toText()) }, enabled = runs.isNotEmpty()) {
                Icon(Icons.Outlined.ContentCopy, "复制全部")
            }
            IconButton(onClick = { onShare(runs.toText()) }, enabled = runs.isNotEmpty()) {
                Icon(Icons.Outlined.Share, "分享")
            }
        }
        TraceRunList(
            runs = runs,
            emptyText = "还没有记录。点「重新识别」再跑一遍当前这张图。",
            contentPadding = PaddingValues(top = 12.dp, bottom = 16.dp),
        )
    }
}

/** 一次一张卡片，最新的在最上面、默认展开。编辑页的面板和设置里的「调试记录」共用。 */
@Composable
internal fun TraceRunList(
    runs: List<TraceRun>,
    emptyText: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (runs.isEmpty()) {
            item {
                Text(
                    emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
        }
        itemsIndexed(runs, key = { _, run -> run.number }) { i, run ->
            RunCard(run, initiallyExpanded = i == 0)
        }
    }
}

/** 一次识别：标题行点了展开、收起；最新的那次一出现就是展开的。 */
@Composable
private fun RunCard(run: TraceRun, initiallyExpanded: Boolean) {
    var expanded by rememberSaveable(run.number) { mutableStateOf(initiallyExpanded) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(run.heading, style = MaterialTheme.typography.titleSmall)
                Text(
                    run.subheading,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
            )
        }
        if (expanded) {
            // 还在跑的那次会不断插进新的一节（按环节排，不一定在最后）：按标题记住各节里哪段原文是展开的
            val seen = HashMap<String, Int>()
            run.sections.forEach { section ->
                val nth = seen.merge(section.title, 1, Int::plus)
                key(section.title, nth) { SectionView(section) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionView(section: TraceSection) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(section.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            section.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (section.lines.isNotEmpty()) {
            SelectionContainer {
                // 一条一段：折行的后半截缩进一点，看得出每一条从哪儿开始
                Text(
                    section.lines.joinToString("\n"),
                    style = monoStyle().copy(textIndent = TextIndent(restLine = 2.em)),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        section.blocks.forEach { BlockView(it) }
    }
}

/** 一大段原文，默认收起，标题上写着有多少字。 */
@Composable
private fun BlockView(block: TraceBlock) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = !open }
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text("${block.label}（${block.text.length} 字）", style = MaterialTheme.typography.labelMedium)
        }
        if (open) {
            SelectionContainer {
                Text(
                    block.text,
                    style = monoStyle(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun monoStyle(): TextStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

internal fun List<TraceRun>.toText(): String = joinToString("\n\n") { it.toText() }

/**
 * 复制进剪贴板，标成敏感内容（ClipDescription.EXTRA_IS_SENSITIVE，Android 13 起才有这个常量，这里直接写它的值）：
 * 记录里是截图上的原文，系统的剪贴板预览、输入法的粘贴建议里不该露出来。
 * Android 13 起系统自己会提示已复制，之前的版本自己弹一句。
 */
internal fun copyToClipboard(context: Context, text: String) {
    val clip = ClipData.newPlainText("调试信息", text).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    }
    val copied = runCatching { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip) }.isSuccess
    if (!copied) {
        Toast.makeText(context, "复制失败，内容可能太长", Toast.LENGTH_SHORT).show()
    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }
}

internal fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, "分享调试信息")) }
}
