package com.youma.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.FaceOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.RuleState
import com.youma.app.engine.SemanticOption
import com.youma.app.engine.TextEngineOption
import com.youma.app.rules.RuleCatalog
import com.youma.app.ui.canvas.ScanStyle
import com.youma.app.ui.components.ColorSwatch
import com.youma.app.ui.theme.ThemeColor
import com.youma.app.ui.theme.colorScheme
import com.youma.app.ui.theme.systemDynamicColorAvailable

/**
 * 设置（2026-09-04 增补设计 §5）。一级页放识别的四根轴和识别动效，逐条的规则收进二级页 [RuleSettingsScreen]；
 * 最底下是与识别无关的主题色。
 *
 * 逐项开关，不给命名预设——预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 * 每根轴下面写清楚它的代价，但**不写「推荐」**：哪个好正是用户要自己看的。
 *
 * 改动即时生效，没有「应用」按钮：写进 DataStore，编辑器的 ViewModel 收到就重跑当前这张图。
 * 最后一项「识别动效」只管识别时画面上的过渡，换它不重跑。
 *
 * 两页各是一个 Activity（[SettingsActivity]、[RuleSettingsActivity]），这里不拦系统返回：
 * 返回由 Activity 自己 finish，预见式返回的跨 Activity 动画才放得出来。
 */
@Composable
fun RecognitionSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    scanStyle: ScanStyle,
    onScanStyleChange: (ScanStyle) -> Unit,
    themeColor: ThemeColor,
    onThemeColorChange: (ThemeColor) -> Unit,
    onOpenRules: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "设置", onNavigateUp = onNavigateUp, modifier = modifier) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                AxisSection(
                    title = "文字识别",
                    note = "PP-OCR 中文最准，速度稍慢。其余三项为 ML Kit：「英文」不支持中文，「中英」最慢。",
                    options = TextEngineOption.entries,
                    selected = config.textEngine,
                    label = ::textEngineLabel,
                    onSelect = { onChange(config.copy(textEngine = it)) },
                )
            }

            item {
                AxisSection(
                    title = "条码",
                    note = "严格：只认能解码的条码，歪斜、模糊的可能漏掉。" +
                        "宽松：无法解码的疑似条码也圈出（不打码），可能误圈花纹。",
                    options = BarcodeOption.entries,
                    selected = config.barcode,
                    label = ::barcodeLabel,
                    onSelect = { onChange(config.copy(barcode = it)) },
                )
            }

            item {
                AxisSection(
                    title = "人脸",
                    note = "精确：漏检更少，速度更慢。仅定位人脸，不识别身份。",
                    options = FaceOption.entries,
                    selected = config.face,
                    label = ::faceLabel,
                    onSelect = { onChange(config.copy(face = it)) },
                )
            }

            item {
                AxisSection(
                    title = "AI 复查（实验）",
                    note = "编辑页显示「AI 复查」按钮，由端侧 Gemini Nano 补查人名和地址，结果只圈出。" +
                        "离线运行，仅支持部分机型（如 Pixel 9 及以后）。",
                    options = SemanticOption.entries,
                    selected = config.semantic,
                    label = ::semanticLabel,
                    onSelect = { onChange(config.copy(semantic = it)) },
                )
            }

            item {
                AxisSection(
                    title = "识别动效",
                    note = "识别时画面上的效果，不影响识别结果。" +
                        "扫光：一束光自上而下扫过，打码块跟在光后落下。" +
                        "磨砂：整页模糊、闪着光点，识别完从中心散开。",
                    options = ScanStyle.entries,
                    selected = scanStyle,
                    label = ::scanStyleLabel,
                    onSelect = onScanStyleChange,
                )
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                val changed = config.ruleOverrides.size
                ListItem(
                    headlineContent = { Text("规则") },
                    supportingContent = {
                        Text(
                            "按类型设为打码、圈出或关闭" +
                                if (changed > 0) "，已改 $changed 项" else "",
                        )
                    },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
                    modifier = Modifier.clickable(onClick = onOpenRules),
                )
            }

            item {
                ListItem(
                    headlineContent = { Text("全部恢复默认") },
                    supportingContent = { Text("识别设置和规则全部恢复出厂值，不动主题色") },
                    modifier = Modifier.clickable {
                        onChange(RecognitionConfig())
                        onScanStyleChange(ScanStyle.SWEEP)
                    },
                )
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ThemeColorSection(selected = themeColor, onSelect = onThemeColorChange)
            }
        }
    }
}

/**
 * 规则（识别设置的二级页）。顶上是导出前提醒的开关，下面每条规则三态：关 / 仅圈出 / 打码。
 * 右上角的「恢复默认」只管规则，不动一级页的四根轴，也不动提醒开关。
 *
 * 提醒开关放这一页，是因为它兜的正是「圈出」这一态：关掉它，圈出的内容导出时就没人再问了。
 */
@Composable
fun RuleSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    exportReminder: Boolean,
    onExportReminderChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = "规则",
        onNavigateUp = onNavigateUp,
        modifier = modifier,
        actions = {
            TextButton(
                onClick = { onChange(config.copy(ruleOverrides = emptyMap())) },
                enabled = config.ruleOverrides.isNotEmpty(),
            ) { Text("恢复默认") }
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            item {
                ListItem(
                    headlineContent = { Text("导出前提醒") },
                    supportingContent = { Text("有圈出但未打码的内容时，导出前提示") },
                    trailingContent = { Switch(checked = exportReminder, onCheckedChange = null) },
                    modifier = Modifier.toggleable(
                        value = exportReminder,
                        role = Role.Switch,
                        onValueChange = onExportReminderChange,
                    ),
                )
                HorizontalDivider(Modifier.padding(bottom = 8.dp))
            }

            items(RuleCatalog.all, key = { it.id }) { rule ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(RuleCatalog.label(rule), style = MaterialTheme.typography.bodyMedium)
                        RuleCatalog.note(rule)?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    RuleState.entries.forEach { state ->
                        FilterChip(
                            selected = RuleCatalog.stateOf(config, rule.id) == state,
                            onClick = {
                                onChange(config.withRule(rule.id, state, RuleCatalog.factoryState(rule.id)))
                            },
                            label = { Text(ruleStateLabel(state)) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 两页共用的骨架。应用是边到边的（见 enableLightEdgeToEdge），系统栏让不让开由各屏自己管：
 * 标题栏垫在状态栏下面、固定在顶上，列表滚上去时从它底下穿过；列表一直画到屏幕底边，
 * 最后一行下面留出导航条的高度。横屏时左右再让开挖孔与三键导航。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                },
                actions = actions,
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
                scrollBehavior = scroll,
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
        content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> AxisSection(
    title: String,
    note: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(label(option)) },
                )
            }
        }
        Text(
            note,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 主题色：「跟随系统」（莫奈取色）是一个开关式的选项，下面一排是预设色。
 * 色块画的是选它之后的主色，不是种子色——按钮、选中态最后就是这个颜色。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeColorSection(selected: ThemeColor, onSelect: (ThemeColor) -> Unit) {
    val context = LocalContext.current
    val presets = remember(context) {
        ThemeColor.entries.filter { it != ThemeColor.SYSTEM }.associateWith { it.colorScheme(context).primary }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("主题色", style = MaterialTheme.typography.titleMedium)
        FilterChip(
            selected = selected == ThemeColor.SYSTEM,
            onClick = { onSelect(ThemeColor.SYSTEM) },
            label = { Text(themeColorLabel(ThemeColor.SYSTEM)) },
            modifier = Modifier.padding(top = 4.dp),
        )
        FlowRow(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            presets.forEach { (color, primary) ->
                ColorSwatch(
                    color = primary,
                    name = themeColorLabel(color),
                    selected = color == selected,
                    onClick = { onSelect(color) },
                )
            }
        }
        Text(
            if (systemDynamicColorAvailable) "跟随系统：随壁纸取色，换壁纸后跟着变。"
            else "跟随系统需要 Android 12 及以上，本机上是紫色。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun themeColorLabel(color: ThemeColor) = when (color) {
    ThemeColor.SYSTEM -> "跟随系统"
    ThemeColor.PURPLE -> "紫色"
    ThemeColor.BLUE -> "蓝色"
    ThemeColor.TEAL -> "青色"
    ThemeColor.GREEN -> "绿色"
    ThemeColor.ORANGE -> "橙色"
    ThemeColor.RED -> "红色"
    ThemeColor.PINK -> "粉色"
}

private fun textEngineLabel(option: TextEngineOption) = when (option) {
    TextEngineOption.PADDLE -> "PP-OCR"
    TextEngineOption.LATIN -> "英文"
    TextEngineOption.CHINESE -> "中文"
    TextEngineOption.BOTH -> "中英"
}

private fun barcodeLabel(option: BarcodeOption) = when (option) {
    BarcodeOption.LOOSE -> "宽松"
    BarcodeOption.STRICT -> "严格"
    BarcodeOption.OFF -> "关闭"
}

private fun faceLabel(option: FaceOption) = when (option) {
    FaceOption.FAST -> "快速"
    FaceOption.ACCURATE -> "精确"
    FaceOption.OFF -> "关闭"
}

private fun semanticLabel(option: SemanticOption) = when (option) {
    SemanticOption.GEMINI_NANO -> "Gemini Nano"
    SemanticOption.OFF -> "关闭"
}

private fun scanStyleLabel(style: ScanStyle) = when (style) {
    ScanStyle.SWEEP -> "扫光"
    ScanStyle.FROST -> "磨砂"
}

private fun ruleStateLabel(state: RuleState) = when (state) {
    RuleState.OFF -> "关"
    RuleState.OUTLINED -> "圈出"
    RuleState.MASKED -> "打码"
}
