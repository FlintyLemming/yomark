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
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.SemanticOption
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.engine.ppocr.PpOcrModels
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.components.ColorSwatch
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.colorScheme
import moe.flinty.yomark.ui.theme.systemDynamicColorAvailable

/*
 * 设置一级页下面的各页：文字（下面还有识别引擎和各类文字自己的页）、人脸、条码、AI、导出、外观。
 * 每根轴下面写清楚它的代价，但**不写「推荐」**：哪个好正是用户要自己看的。
 * 逐项开关，不给命名预设——预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 *
 * 改动即时生效，没有「应用」按钮：写进 DataStore，编辑器的 ViewModel 收到就重跑当前这张图。
 * 外观和导出两页不影响识别，换它们不重跑。AI 页只显示本机的 Gemini Nano 和随包的 PP-OCR，没有可改的。
 */

/**
 * 文字：上面一组是「怎么认字」（识别引擎、AI 增强识别），下面一组是认出来的各类敏感信息，
 * 每类一行、小字写着眼下怎么处理，点进去是这一类自己的页（[TextRuleSettingsScreen]）。
 *
 * 各类原先在行尾直接摆一排「关 / 圈出 / 打码」；人名多了一个只属于它的开关之后，行尾放不下，
 * 一类一页又和人脸、条码的页是同一个样子：先是「识别到时怎么办」，再是这一类自己的选项和认法。
 * 原先单独一页的「文字识别」并进来：认字和认出来怎么处理都是「文字」的事，分在两处找不到。
 *
 * 右上角的「恢复默认」只管这一页和它下面的各页：识别引擎、AI 增强识别、各类的处理方式和人名的开关。
 */
@Composable
fun TextSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onOpenEngine: () -> Unit,
    onOpenRule: (ruleId: String) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title = "文字",
        onNavigateUp = onNavigateUp,
        modifier = modifier,
        actions = {
            val reset = config.withTextDefaults()
            TextButton(onClick = { onChange(reset) }, enabled = config != reset) { Text("恢复默认") }
        },
    ) {
        item {
            SectionHeader("文字识别", first = true)
            SettingsGroup {
                LinkRow(
                    title = "识别引擎",
                    summary = textEngineLabel(config.textEngine),
                    icon = Icons.Outlined.DocumentScanner,
                    onClick = onOpenEngine,
                )
                SwitchRow(
                    title = "AI 增强识别",
                    icon = Icons.Outlined.AutoAwesome,
                    checked = config.semantic != SemanticOption.OFF,
                    onCheckedChange = { on ->
                        onChange(config.copy(semantic = if (on) SemanticOption.GEMINI_NANO else SemanticOption.OFF))
                    },
                )
            }
            SectionHeader("敏感信息")
        }
        val rules = RuleCatalog.inSettingsOrder
        itemsIndexed(rules, key = { _, rule -> rule.id }) { i, rule ->
            LinkRow(
                title = RuleCatalog.label(rule),
                summary = ruleSummary(config, rule.id),
                icon = rule.kind.settingsIcon(),
                onClick = { onOpenRule(rule.id) },
                modifier = Modifier.lazyGroupInset().testTag("rule-${rule.id}"),
                shape = groupItemShape(i, rules.size),
            )
            if (i < rules.lastIndex) RowGapSpacer()
        }
    }
}

/** 识别引擎：OCR 用哪一家。从「文字」页点进来。 */
@Composable
fun TextEngineSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "识别引擎", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
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
    }
}

/**
 * 文字里的一类，样子和人脸、条码的页一样：先是「识别到时」打码 / 仅圈出 / 关闭，再是这一类怎么认的。
 * 人名多一组：没有旁证、只凭字面猜出的名字要不要也圈出。
 */
@Composable
fun TextRuleSettingsScreen(
    ruleId: String,
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rule = RuleCatalog.all.first { it.id == ruleId }
    val label = RuleCatalog.label(rule)
    val state = RuleCatalog.stateOf(config, rule.id)
    SettingsScaffold(title = label, onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader("识别到${label}时", first = true)
            HandlingGroup(state) {
                onChange(config.withRule(rule.id, it, RuleCatalog.factoryState(rule.id)))
            }
            FooterText(RuleCatalog.description(rule))
        }
        if (rule.id == NAME_RULE) {
            item {
                SectionHeader("从字面猜出的人名")
                SettingsGroup {
                    SwitchRow(
                        title = "没有旁证的也圈出",
                        summary = "会多圈到药名、品牌名",
                        icon = Icons.Outlined.PersonSearch,
                        checked = config.outlineUnanchoredNames,
                        enabled = state != RuleState.OFF,
                        onCheckedChange = { onChange(config.copy(outlineUnanchoredNames = it)) },
                        modifier = Modifier.testTag("unanchored-names"),
                    )
                }
                FooterText(RuleCatalog.nameAnchorNote())
            }
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
            HandlingGroup(config.faceState) { onChange(config.copy(faceState = it)) }
        }
        item {
            SectionHeader("识别模式")
            SettingsGroup(Modifier.selectableGroup()) {
                FaceOption.entries.forEach { option ->
                    RadioRow(
                        title = faceModeLabel(option),
                        selected = config.face == option,
                        enabled = config.faceState != RuleState.OFF,
                        onClick = { onChange(config.copy(face = option)) },
                    )
                }
            }
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
            HandlingGroup(config.barcodeState) { onChange(config.copy(barcodeState = it)) }
        }
        item {
            SectionHeader("识别模式")
            SettingsGroup(Modifier.selectableGroup()) {
                BarcodeOption.entries.forEach { option ->
                    RadioRow(
                        title = barcodeModeLabel(option),
                        summary = when (option) {
                            BarcodeOption.STRICT -> "只认能解码的条码"
                            BarcodeOption.LOOSE -> "解不出的疑似条码也圈出，可能误圈"
                        },
                        selected = config.barcode == option,
                        enabled = config.barcodeState != RuleState.OFF,
                        onClick = { onChange(config.copy(barcode = option)) },
                    )
                }
            }
            FooterText("包括二维码和条形码。")
        }
    }
}

/**
 * AI：本机用到的模型——端侧大模型 Gemini Nano（有没有、是哪一版、下好了没有），
 * 随包的 PP-OCR 和 ML Kit（版本都是打包时定的；ML Kit 几个模型并成一行）。都只显示，没有可设的项。
 * AI 增强识别的开关和识别引擎的选择都在「文字」页的「文字识别」一组里。
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
            SectionHeader("大模型", first = true)
            SettingsGroup {
                SettingsRow(
                    title = "Gemini Nano",
                    summary = nanoSummary(nano),
                    leading = { RowIcon(Icons.Outlined.AutoAwesome) },
                )
            }
            FooterText(
                buildAnnotatedString {
                    append("Google 的端侧大模型。")
                    nanoNote(nano)?.let { append("\n\n"); append(it) }
                },
            )
        }
        item {
            SectionHeader("文字识别模型")
            SettingsGroup {
                SettingsRow(
                    title = "PP-OCR",
                    summary = "${PpOcrModels.VERSION} · 随安装包内置",
                    leading = { RowIcon(Icons.Outlined.DocumentScanner) },
                )
            }
            FooterText("百度飞桨的开源文字识别模型。")
        }
        item {
            SectionHeader("ML Kit 模型")
            SettingsGroup {
                SettingsRow(
                    title = "ML Kit",
                    summary = mlKitSummary(
                        text = BuildConfig.MLKIT_TEXT_VERSION,
                        textChinese = BuildConfig.MLKIT_TEXT_CHINESE_VERSION,
                        face = BuildConfig.MLKIT_FACE_VERSION,
                        barcode = BuildConfig.MLKIT_BARCODE_VERSION,
                    ),
                    leading = { RowIcon(Icons.Outlined.Memory) },
                )
            }
            FooterText("Google 的端侧机器学习工具包。")
        }
    }
}

/**
 * 导出：导出前提醒和品牌水印的样式。
 *
 * 导出前提醒兜的正是「圈出」这一态——关掉它，圈出的内容导出时就没人再问了。
 * 水印样式是免费版导出时角落那个水印用哪一版字样，没选过时跟着系统语言（见 BrandWatermarkStyle）。
 * 两项都不在识别方案里：改它们编辑器不重跑识别。
 */
@Composable
fun ExportSettingsScreen(
    exportReminder: Boolean,
    onExportReminderChange: (Boolean) -> Unit,
    watermarkStyle: BrandWatermarkStyle,
    onWatermarkStyleChange: (BrandWatermarkStyle) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = "导出", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SwitchRow(
                    title = "导出前提醒",
                    summary = "有圈出但未打码的内容时",
                    icon = Icons.AutoMirrored.Outlined.FactCheck,
                    checked = exportReminder,
                    onCheckedChange = onExportReminderChange,
                )
            }
        }
        item {
            SectionHeader("水印样式")
            SettingsGroup(Modifier.selectableGroup()) {
                BrandWatermarkStyle.entries.forEach { style ->
                    RadioRow(
                        title = "图标 + ${style.text}",
                        selected = watermarkStyle == style,
                        onClick = { onWatermarkStyleChange(style) },
                    )
                }
            }
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
                    summary = if (systemDynamicColorAvailable) "随壁纸取色" else "需要 Android 12 及以上",
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
                        selected = scanStyle == style,
                        onClick = { onScanStyleChange(style) },
                    )
                }
            }
        }
    }
}

/** 人脸、条码、文字的各类共用：打码 / 仅圈出 / 关闭，打码排第一。 */
@Composable
private fun HandlingGroup(selected: RuleState, onSelect: (RuleState) -> Unit) {
    SettingsGroup(Modifier.selectableGroup()) {
        listOf(RuleState.MASKED, RuleState.OUTLINED, RuleState.OFF).forEach { state ->
            RadioRow(
                title = handlingLabel(state),
                summary = if (state == RuleState.OUTLINED) "只画虚线框，点一下才打码" else null,
                selected = selected == state,
                onClick = { onSelect(state) },
            )
        }
    }
}

/** 文字各类行首的图标，按类型给。 */
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
    SensitiveKind.BIRTH_DATE -> Icons.Outlined.Cake
    SensitiveKind.PICKUP_CODE -> Icons.Outlined.Inventory2
    SensitiveKind.POSTAL_ADDRESS -> Icons.Outlined.Place
    SensitiveKind.PERSON_NAME -> Icons.Outlined.Person
    SensitiveKind.ORG_NAME -> Icons.Outlined.Domain
    SensitiveKind.FACE -> Icons.Outlined.Face
    SensitiveKind.BARCODE -> Icons.Outlined.QrCode2
    SensitiveKind.MANUAL -> Icons.Outlined.Edit
}

/** 人名一类的规则 id：它的页上多一个开关，「文字」页上它那一行的小字也多一截。 */
private const val NAME_RULE = "name"

/**
 * 「文字」页上一类的小字：怎么处理。人名再写上没有旁证的那些怎么办——
 * 不写的话，聊天里的人名不圈了，用户只看得到「打码」两个字。
 */
internal fun ruleSummary(config: RecognitionConfig, ruleId: String): String {
    val state = RuleCatalog.stateOf(config, ruleId)
    val handling = handlingLabel(state)
    if (ruleId != NAME_RULE || state == RuleState.OFF) return handling
    return handling + if (config.outlineUnanchoredNames) " · 没有旁证的只圈出" else " · 没有旁证的不圈"
}

/**
 * 「文字」页右上角的「恢复默认」：这一页和它下面各页的项回到出厂值，人脸、条码不动。
 * 出厂值取自 [RecognitionConfig] 的缺省值，不另抄一份。
 */
internal fun RecognitionConfig.withTextDefaults(): RecognitionConfig {
    val factory = RecognitionConfig()
    return copy(
        textEngine = factory.textEngine,
        semantic = factory.semantic,
        outlineUnanchoredNames = factory.outlineUnanchoredNames,
        ruleOverrides = emptyMap(),
    )
}

/** 打码 / 仅圈出 / 关闭。人脸、条码、文字的各类共用。 */
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

/** 哪些机型有 Gemini Nano：Google 的文档。页尾「支持设备列表参考官方文档」那一句指向它。 */
internal const val GENAI_DOCS_URL = "https://developers.google.cn/ml-kit/genai"

/** 「支持设备列表参考官方文档。」，「官方文档」是个链接。 */
@Composable
private fun supportedDevicesSentence(): AnnotatedString = buildAnnotatedString {
    append("支持设备列表参考")
    append(footerLink("官方文档", GENAI_DOCS_URL))
    append("。")
}

/**
 * 卡片下面那句：本机没有就指去看哪些机型有，模型还没下载就说怎么让系统去下。
 * 已就绪、下载中的小字已经说清了，不再重复；还没问完时不写。
 *
 * 未下载时不在这里摆下载按钮：开着 AI 增强识别打开一张图，编辑页探测时就会请 AICore 去下（见 NanoClient.ready）。
 */
@Composable
internal fun nanoNote(nano: NanoStatus?): AnnotatedString? = when (nano) {
    null -> null
    is NanoStatus.Unavailable -> buildAnnotatedString {
        append(supportedDevicesSentence())
        nano.errorCode?.let { append("AICore 返回错误码 $it。") }
    }
    is NanoStatus.Present -> when (nano.model) {
        NanoStatus.Model.NOT_DOWNLOADED -> AnnotatedString("开着 AI 增强识别打开一张图，会请系统下载。")
        NanoStatus.Model.READY, NanoStatus.Model.DOWNLOADING -> null
    }
}

/**
 * ML Kit 的几个模型写成一行：「文字 16.0.1 · 人脸 16.1.7 · 条码 17.3.0」。
 * 英文、中文两个文字模型多半同版，同版只写一次，不同才分开写。
 */
internal fun mlKitSummary(text: String, textChinese: String, face: String, barcode: String): String {
    val textVersions = if (text == textChinese) text else "$text（英文）/ $textChinese（中文）"
    return "文字 $textVersions · 人脸 $face · 条码 $barcode"
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
