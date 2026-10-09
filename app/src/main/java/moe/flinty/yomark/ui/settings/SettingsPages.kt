package moe.flinty.yomark.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.FactCheck
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.SemanticOption
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.components.ColorSwatch
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.colorScheme
import moe.flinty.yomark.ui.theme.systemDynamicColorAvailable

/*
 * 设置的七个二级页。每根轴下面写清楚它的代价，但**不写「推荐」**：哪个好正是用户要自己看的。
 * 逐项开关，不给命名预设——预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 *
 * 改动即时生效，没有「应用」按钮：写进 DataStore，编辑器的 ViewModel 收到就重跑当前这张图。
 * 外观和导出两页不影响识别，换它们不重跑。AI 页眼下只显示本机的 Gemini Nano，没有可改的。
 */

/**
 * 文字：每一类文字三态，关 / 圈出 / 打码。右上角的「恢复默认」只管这一页的规则。
 */
@Composable
fun TextRulesScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = "文字",
        onNavigateUp = onNavigateUp,
        modifier = modifier,
        actions = {
            TextButton(
                onClick = { onChange(config.copy(ruleOverrides = emptyMap())) },
                enabled = config.ruleOverrides.isNotEmpty(),
            ) { Text("恢复默认") }
        },
    ) {
        item {
            PageIntro("识别到的文字按类型处理：打码是直接遮住，圈出是只画虚线框、点一下才打码，关掉的类型不识别。")
        }
        val rules = RuleCatalog.all
        itemsIndexed(rules, key = { _, rule -> rule.id }) { i, rule ->
            SettingsRow(
                title = RuleCatalog.label(rule),
                modifier = Modifier.lazyGroupInset().testTag("rule-${rule.id}"),
                shape = groupItemShape(i, rules.size),
                leading = { RowIcon(rule.kind.settingsIcon()) },
                trailing = {
                    RuleStateSelector(
                        selected = RuleCatalog.stateOf(config, rule.id),
                        onSelect = { onChange(config.withRule(rule.id, it, RuleCatalog.factoryState(rule.id))) },
                    )
                },
                below = RuleCatalog.note(rule)?.let { note ->
                    {
                        Text(
                            note,
                            modifier = Modifier.padding(start = 40.dp, top = 6.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
            if (i < rules.lastIndex) RowGapSpacer()
        }
    }
}

/** 人脸：认出来之后怎么处理，和用哪种模式认。 */
@Composable
fun FaceSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "人脸", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader("识别到人脸时", first = true)
            HandlingGroup(config.faceState, offSummary = "不识别人脸") { onChange(config.copy(faceState = it)) }
        }
        item {
            SectionHeader("识别模式")
            SettingsGroup(Modifier.selectableGroup()) {
                FaceOption.entries.forEach { option ->
                    RadioRow(
                        title = faceModeLabel(option),
                        summary = when (option) {
                            FaceOption.FAST -> "速度更快"
                            FaceOption.ACCURATE -> "漏检更少，速度更慢"
                        },
                        selected = config.face == option,
                        enabled = config.faceState != RuleState.OFF,
                        onClick = { onChange(config.copy(face = option)) },
                    )
                }
            }
            FooterText("仅定位人脸，不识别身份。")
        }
    }
}

/** 条码：同人脸。解不出内容的疑似条码（宽松模式才有）不管怎么设都只圈出。 */
@Composable
fun BarcodeSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "条码", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader("识别到条码时", first = true)
            HandlingGroup(config.barcodeState, offSummary = "不识别条码") { onChange(config.copy(barcodeState = it)) }
        }
        item {
            SectionHeader("识别模式")
            SettingsGroup(Modifier.selectableGroup()) {
                BarcodeOption.entries.forEach { option ->
                    RadioRow(
                        title = barcodeModeLabel(option),
                        summary = when (option) {
                            BarcodeOption.STRICT -> "只认能解码的条码，歪斜、模糊的可能漏掉"
                            BarcodeOption.LOOSE -> "无法解码的疑似条码也圈出（不打码），可能误圈花纹"
                        },
                        selected = config.barcode == option,
                        enabled = config.barcodeState != RuleState.OFF,
                        onClick = { onChange(config.copy(barcode = option)) },
                    )
                }
            }
            FooterText("包括二维码和条形码。只用到条码的位置，不读取其中的内容。")
        }
    }
}

/** 文字识别：OCR 用哪一家，以及要不要 AI 复查。两项都是「怎么认字」，认出来怎么处理在「文字」那一页。 */
@Composable
fun TextRecognitionSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "文字识别", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader("识别引擎", first = true)
            SettingsGroup(Modifier.selectableGroup()) {
                TextEngineOption.entries.forEach { option ->
                    RadioRow(
                        title = textEngineLabel(option),
                        summary = when (option) {
                            TextEngineOption.PADDLE -> "中文最准，速度稍慢"
                            TextEngineOption.LATIN -> "不支持中文"
                            TextEngineOption.CHINESE -> "中英文都能认"
                            TextEngineOption.BOTH -> "两个模型一起跑，最慢"
                        },
                        selected = config.textEngine == option,
                        onClick = { onChange(config.copy(textEngine = option)) },
                    )
                }
            }
        }
        item {
            SectionHeader("AI 复查（实验）")
            SettingsGroup {
                SwitchRow(
                    title = "使用 AI 复查",
                    summary = "编辑页多一个按钮，点了由端侧 Gemini Nano 补查人名和地址，结果只圈出",
                    icon = Icons.Outlined.AutoAwesome,
                    checked = config.semantic != SemanticOption.OFF,
                    onCheckedChange = { on ->
                        onChange(config.copy(semantic = if (on) SemanticOption.GEMINI_NANO else SemanticOption.OFF))
                    },
                )
            }
            FooterText("离线运行，仅支持部分机型（如 Pixel 9 及以后）。")
        }
    }
}

/**
 * AI：眼下只显示本机的 Gemini Nano——有没有、是哪一版、模型下好了没有，还没有可设的项。
 * AI 复查的开关仍在「文字识别」页。
 *
 * @param nano 由 Activity 进页时问一次 AICore 得来；null 是还没问完。
 */
@Composable
fun AiSettingsScreen(
    nano: NanoStatus?,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "AI", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageIntro("Gemini Nano 是 Google 的端侧大模型，由系统的 AICore 服务在本机离线运行。编辑页的「AI 复查」用的就是它。")
            SettingsGroup {
                SettingsRow(
                    title = "Gemini Nano",
                    summary = nanoSummary(nano),
                    leading = { RowIcon(Icons.Outlined.AutoAwesome) },
                )
            }
            nanoNote(nano)?.let { FooterText(it) }
        }
    }
}

/**
 * 导出：导出前提醒。它兜的正是「圈出」这一态——关掉它，圈出的内容导出时就没人再问了。
 * 不在识别方案里：拨这个开关编辑器不重跑识别。
 */
@Composable
fun ExportSettingsScreen(
    exportReminder: Boolean,
    onExportReminderChange: (Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "导出", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SwitchRow(
                    title = "导出前提醒",
                    summary = "有圈出但未打码的内容时，导出前先列出来让你确认",
                    icon = Icons.AutoMirrored.Outlined.FactCheck,
                    checked = exportReminder,
                    onCheckedChange = onExportReminderChange,
                )
            }
            FooterText("关掉后，圈出但没打码的内容会照原样导出。")
        }
    }
}

/**
 * 外观：主题色与识别动效，都只是样子。
 *
 * 主题色：「跟随系统」（莫奈取色）是一个单选项，下面一排是预设色。
 * 色块画的是选它之后的主色，不是种子色——按钮、选中态最后就是这个颜色。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    themeColor: ThemeColor,
    onThemeColorChange: (ThemeColor) -> Unit,
    scanStyle: ScanStyle,
    onScanStyleChange: (ScanStyle) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val presets = remember(context) {
        ThemeColor.entries.filter { it != ThemeColor.SYSTEM }.associateWith { it.colorScheme(context).primary }
    }
    SettingsScaffold(title = "外观", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader("主题色", first = true)
            SettingsGroup {
                RadioRow(
                    title = themeColorLabel(ThemeColor.SYSTEM),
                    summary = if (systemDynamicColorAvailable) "随壁纸取色，换壁纸后跟着变"
                    else "需要 Android 12 及以上，本机上是紫色",
                    selected = themeColor == ThemeColor.SYSTEM,
                    onClick = { onThemeColorChange(ThemeColor.SYSTEM) },
                )
                SettingsRow(
                    title = "预设颜色",
                    below = {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            presets.forEach { (color, primary) ->
                                ColorSwatch(
                                    color = primary,
                                    name = themeColorLabel(color),
                                    selected = color == themeColor,
                                    onClick = { onThemeColorChange(color) },
                                )
                            }
                        }
                    },
                )
            }
        }
        item {
            SectionHeader("识别动效")
            SettingsGroup(Modifier.selectableGroup()) {
                ScanStyle.entries.forEach { style ->
                    RadioRow(
                        title = scanStyleLabel(style),
                        summary = when (style) {
                            ScanStyle.SWEEP -> "一束光自上而下扫过，打码块跟在光后落下"
                            ScanStyle.FROST -> "整页模糊、闪着光点，识别完从中心散开"
                        },
                        selected = scanStyle == style,
                        onClick = { onScanStyleChange(style) },
                    )
                }
            }
            FooterText("识别时画面上的效果，不影响识别结果。")
        }
    }
}

/** 人脸、条码共用：打码 / 仅圈出 / 关闭，出厂项（打码）排第一。 */
@Composable
private fun HandlingGroup(selected: RuleState, offSummary: String, onSelect: (RuleState) -> Unit) {
    SettingsGroup(Modifier.selectableGroup()) {
        listOf(RuleState.MASKED, RuleState.OUTLINED, RuleState.OFF).forEach { state ->
            RadioRow(
                title = handlingLabel(state),
                summary = when (state) {
                    RuleState.MASKED -> "直接遮住"
                    RuleState.OUTLINED -> "只画虚线框，点一下才打码"
                    RuleState.OFF -> offSummary
                },
                selected = selected == state,
                onClick = { onSelect(state) },
            )
        }
    }
}

/** 文字规则行首的图标，按类型给。 */
internal fun SensitiveKind.settingsIcon(): ImageVector = when (this) {
    SensitiveKind.PHONE -> Icons.Outlined.Phone
    SensitiveKind.EMAIL -> Icons.Outlined.AlternateEmail
    SensitiveKind.PAYMENT_CARD -> Icons.Outlined.CreditCard
    SensitiveKind.IBAN -> Icons.Outlined.AccountBalance
    SensitiveKind.SSN -> Icons.Outlined.Badge
    SensitiveKind.PASSPORT -> Icons.Outlined.FlightTakeoff
    SensitiveKind.TRACKING_NO -> Icons.Outlined.LocalShipping
    SensitiveKind.IP_ADDR -> Icons.Outlined.Lan
    SensitiveKind.MAC_ADDR -> Icons.Outlined.Router
    SensitiveKind.URL -> Icons.Outlined.Link
    SensitiveKind.API_KEY -> Icons.Outlined.Key
    SensitiveKind.LONG_NUMBER -> Icons.Outlined.Numbers
    SensitiveKind.DATETIME -> Icons.Outlined.Schedule
    SensitiveKind.PICKUP_CODE -> Icons.Outlined.Inventory2
    SensitiveKind.POSTAL_ADDRESS -> Icons.Outlined.Place
    SensitiveKind.PERSON_NAME -> Icons.Outlined.Person
    SensitiveKind.ORG_NAME -> Icons.Outlined.Domain
    SensitiveKind.FACE -> Icons.Outlined.Face
    SensitiveKind.BARCODE -> Icons.Outlined.QrCode2
    SensitiveKind.MANUAL -> Icons.Outlined.Edit
}

/** 人脸、条码认出来之后怎么处理。文字规则行里地方小，用的是更短的 [ruleStateLabel]。 */
internal fun handlingLabel(state: RuleState) = when (state) {
    RuleState.MASKED -> "打码"
    RuleState.OUTLINED -> "仅圈出"
    RuleState.OFF -> "关闭"
}

/** 「nano-v3 · 已就绪」：先回答是哪一版，再说模型在不在本机。本机没有就只写「本机没有」。 */
internal fun nanoSummary(nano: NanoStatus?): String = when (nano) {
    null -> "正在检测…"
    is NanoStatus.Unavailable -> "本机没有"
    is NanoStatus.Present -> "${nano.version ?: "版本未知"} · " + when (nano.model) {
        NanoStatus.Model.READY -> "已就绪"
        NanoStatus.Model.DOWNLOADING -> "下载中"
        NanoStatus.Model.NOT_DOWNLOADED -> "未下载"
    }
}

/**
 * 卡片下面那句：这个状态对 AI 复查意味着什么。还没问完时不写。
 *
 * 未下载时不在这里摆下载按钮：开着 AI 复查打开一张图，编辑页探测时就会请 AICore 去下（见 NanoClient.ready）。
 */
internal fun nanoNote(nano: NanoStatus?): String? = when (nano) {
    null -> null
    is NanoStatus.Unavailable ->
        "只有部分机型有 Gemini Nano（如 Pixel 9 及以后）。本机用不了 AI 复查，其余识别照常。" +
            nano.errorCode?.let { "AICore 返回错误码 $it。" }.orEmpty()
    is NanoStatus.Present -> when (nano.model) {
        NanoStatus.Model.READY -> "模型已在本机，AI 复查可以用。"
        NanoStatus.Model.DOWNLOADING -> "系统正在下载模型，下载完就能用 AI 复查。"
        NanoStatus.Model.NOT_DOWNLOADED ->
            "模型还没下载到本机。开着 AI 复查时打开一张图，会请系统下载；下载完，编辑页才出现「AI 复查」按钮。"
    }
}

internal fun textEngineLabel(option: TextEngineOption) = when (option) {
    TextEngineOption.PADDLE -> "PP-OCR"
    TextEngineOption.LATIN -> "ML Kit 英文"
    TextEngineOption.CHINESE -> "ML Kit 中文"
    TextEngineOption.BOTH -> "ML Kit 中英"
}

internal fun faceModeLabel(option: FaceOption) = when (option) {
    FaceOption.FAST -> "快速"
    FaceOption.ACCURATE -> "精确"
}

internal fun barcodeModeLabel(option: BarcodeOption) = when (option) {
    BarcodeOption.STRICT -> "严格"
    BarcodeOption.LOOSE -> "宽松"
}

internal fun scanStyleLabel(style: ScanStyle) = when (style) {
    ScanStyle.SWEEP -> "扫光"
    ScanStyle.FROST -> "磨砂"
}

internal fun themeColorLabel(color: ThemeColor) = when (color) {
    ThemeColor.SYSTEM -> "跟随系统"
    ThemeColor.PURPLE -> "紫色"
    ThemeColor.BLUE -> "蓝色"
    ThemeColor.TEAL -> "青色"
    ThemeColor.GREEN -> "绿色"
    ThemeColor.ORANGE -> "橙色"
    ThemeColor.RED -> "红色"
    ThemeColor.PINK -> "粉色"
}
