package moe.flinty.yomark.ui.settings

import android.content.res.Resources
import androidx.annotation.StringRes
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
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CarRepair
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.R
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
        title = stringResource(R.string.settings_text),
        onNavigateUp = onNavigateUp,
        modifier = modifier,
        actions = {
            val reset = config.withTextDefaults()
            TextButton(onClick = { onChange(reset) }, enabled = config != reset) { Text(stringResource(R.string.reset_defaults)) }
        },
    ) {
        item {
            SectionHeader(stringResource(R.string.text_recognition), first = true)
            SettingsGroup {
                LinkRow(
                    title = stringResource(R.string.engine),
                    summary = stringResource(textEngineLabel(config.textEngine)),
                    icon = Icons.Outlined.DocumentScanner,
                    onClick = onOpenEngine,
                )
                SwitchRow(
                    title = stringResource(R.string.ai_review),
                    icon = Icons.Outlined.AutoAwesome,
                    checked = config.semantic != SemanticOption.OFF,
                    onCheckedChange = { on ->
                        onChange(config.copy(semantic = if (on) SemanticOption.GEMINI_NANO else SemanticOption.OFF))
                    },
                )
            }
            SectionHeader(stringResource(R.string.sensitive_info))
        }
        val rules = RuleCatalog.inSettingsOrder
        itemsIndexed(rules, key = { _, rule -> rule.id }) { i, rule ->
            LinkRow(
                title = stringResource(RuleCatalog.label(rule)),
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
    SettingsScaffold(title = stringResource(R.string.engine), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup(Modifier.selectableGroup()) {
                TextEngineOption.entries.forEach { option ->
                    RadioRow(
                        title = stringResource(textEngineLabel(option)),
                        summary = stringResource(
                            when (option) {
                                TextEngineOption.PADDLE -> R.string.engine_paddle_summary
                                TextEngineOption.LATIN -> R.string.engine_latin_summary
                                TextEngineOption.CHINESE -> R.string.engine_chinese_summary
                                TextEngineOption.BOTH -> R.string.engine_both_summary
                            },
                        ),
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
    val label = stringResource(RuleCatalog.label(rule))
    val state = RuleCatalog.stateOf(config, rule.id)
    SettingsScaffold(title = label, onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.when_found, label), first = true)
            HandlingGroup(state) {
                onChange(config.withRule(rule.id, it, RuleCatalog.factoryState(rule.id)))
            }
            FooterText(stringResource(RuleCatalog.description(rule)))
        }
        if (rule.id == NAME_RULE) {
            item {
                SectionHeader(stringResource(R.string.guessed_names))
                SettingsGroup {
                    SwitchRow(
                        title = stringResource(R.string.outline_unanchored),
                        summary = stringResource(R.string.outline_unanchored_summary),
                        icon = Icons.Outlined.PersonSearch,
                        checked = config.outlineUnanchoredNames,
                        enabled = state != RuleState.OFF,
                        onCheckedChange = { onChange(config.copy(outlineUnanchoredNames = it)) },
                        modifier = Modifier.testTag("unanchored-names"),
                    )
                }
                FooterText(RuleCatalog.nameAnchorNote(LocalContext.current.resources))
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
    SettingsScaffold(title = stringResource(R.string.settings_face), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.when_found, stringResource(R.string.kind_face)), first = true)
            HandlingGroup(config.faceState) { onChange(config.copy(faceState = it)) }
        }
        item {
            SectionHeader(stringResource(R.string.detect_mode))
            SettingsGroup(Modifier.selectableGroup()) {
                FaceOption.entries.forEach { option ->
                    RadioRow(
                        title = stringResource(faceModeLabel(option)),
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
    SettingsScaffold(title = stringResource(R.string.settings_barcode), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.when_found, stringResource(R.string.kind_barcode)), first = true)
            HandlingGroup(config.barcodeState) { onChange(config.copy(barcodeState = it)) }
        }
        item {
            SectionHeader(stringResource(R.string.detect_mode))
            SettingsGroup(Modifier.selectableGroup()) {
                BarcodeOption.entries.forEach { option ->
                    RadioRow(
                        title = stringResource(barcodeModeLabel(option)),
                        summary = stringResource(
                            when (option) {
                                BarcodeOption.STRICT -> R.string.barcode_strict_summary
                                BarcodeOption.LOOSE -> R.string.barcode_loose_summary
                            },
                        ),
                        selected = config.barcode == option,
                        enabled = config.barcodeState != RuleState.OFF,
                        onClick = { onChange(config.copy(barcode = option)) },
                    )
                }
            }
            FooterText(stringResource(R.string.barcode_footer))
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
    SettingsScaffold(title = stringResource(R.string.settings_ai), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.ai_model), first = true)
            SettingsGroup {
                SettingsRow(
                    title = "Gemini Nano",
                    summary = nanoSummary(nano),
                    leading = { RowIcon(Icons.Outlined.AutoAwesome) },
                )
            }
            FooterText(
                buildAnnotatedString {
                    append(stringResource(R.string.nano_footer))
                    nanoNote(nano)?.let { append("\n\n"); append(it) }
                },
            )
        }
        item {
            SectionHeader(stringResource(R.string.ocr_models))
            SettingsGroup {
                SettingsRow(
                    title = "PP-OCR",
                    summary = "${PpOcrModels.VERSION} · ${stringResource(R.string.bundled)}",
                    leading = { RowIcon(Icons.Outlined.DocumentScanner) },
                )
            }
            FooterText(stringResource(R.string.ppocr_footer))
        }
        item {
            SectionHeader(stringResource(R.string.mlkit_models))
            SettingsGroup {
                SettingsRow(
                    title = "ML Kit",
                    summary = mlKitSummary(
                        res = LocalContext.current.resources,
                        text = BuildConfig.MLKIT_TEXT_VERSION,
                        textChinese = BuildConfig.MLKIT_TEXT_CHINESE_VERSION,
                        face = BuildConfig.MLKIT_FACE_VERSION,
                        barcode = BuildConfig.MLKIT_BARCODE_VERSION,
                    ),
                    leading = { RowIcon(Icons.Outlined.Memory) },
                )
            }
            FooterText(stringResource(R.string.mlkit_footer))
        }
    }
}

/**
 * 导出：导出前提醒和品牌水印的样式。
 *
 * 导出前提醒兜的正是「圈出」这一态——关掉它，圈出的内容导出时就没人再问了。
 * 水印样式是导出时角落那个品牌水印用哪一版字样，没选过时跟着系统语言（见 BrandWatermarkStyle）。
 * 「无水印」只在解锁后出现（买过，或开源版点过去除水印）；解锁了没选过就是它，见 brandWatermarkApplies。
 * 两项都不在识别方案里：改它们编辑器不重跑识别。
 */
@Composable
fun ExportSettingsScreen(
    exportReminder: Boolean,
    onExportReminderChange: (Boolean) -> Unit,
    watermarkStyle: BrandWatermarkStyle,
    onWatermarkStyleChange: (BrandWatermarkStyle) -> Unit,
    /** 能不能去水印。false 时没有「无水印」这一项。 */
    watermarkUnlocked: Boolean,
    /** 导出不加品牌水印。只在 [watermarkUnlocked] 时有意义。 */
    watermarkOff: Boolean,
    onWatermarkOff: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val none = watermarkUnlocked && watermarkOff
    SettingsScaffold(title = stringResource(R.string.settings_export), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.export_reminder),
                    summary = stringResource(R.string.export_reminder_summary),
                    icon = Icons.AutoMirrored.Outlined.FactCheck,
                    checked = exportReminder,
                    onCheckedChange = onExportReminderChange,
                )
            }
        }
        item {
            SectionHeader(stringResource(R.string.watermark_style))
            SettingsGroup(Modifier.selectableGroup()) {
                BrandWatermarkStyle.entries.forEach { style ->
                    RadioRow(
                        title = stringResource(R.string.watermark_style_option, style.text),
                        selected = !none && watermarkStyle == style,
                        onClick = { onWatermarkStyleChange(style) },
                    )
                }
                if (watermarkUnlocked) {
                    RadioRow(title = stringResource(R.string.watermark_none), selected = none, onClick = onWatermarkOff)
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
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.theme_color), first = true)
            SettingsGroup {
                RadioRow(
                    title = stringResource(themeColorLabel(ThemeColor.SYSTEM)),
                    summary = stringResource(if (systemDynamicColorAvailable) R.string.dynamic_color else R.string.needs_android_12),
                    selected = themeColor == ThemeColor.SYSTEM,
                    onClick = { onThemeColorChange(ThemeColor.SYSTEM) },
                )
                SettingsRow(
                    title = stringResource(R.string.preset_colors),
                    below = {
                        FlowRow(
                            Modifier.fillMaxWidth().padding(top = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            presets.forEach { (color, primary) ->
                                ColorSwatch(
                                    color = primary,
                                    name = stringResource(themeColorLabel(color)),
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
            SectionHeader(stringResource(R.string.scan_effect))
            SettingsGroup(Modifier.selectableGroup()) {
                ScanStyle.entries.forEach { style ->
                    RadioRow(
                        title = stringResource(scanStyleLabel(style)),
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
                summary = if (state == RuleState.OUTLINED) stringResource(R.string.handling_outlined_summary) else null,
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
    SensitiveKind.ID_NUMBER -> Icons.Outlined.AssignmentInd
    SensitiveKind.VIN -> Icons.Outlined.CarRepair
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
@Composable
internal fun ruleSummary(config: RecognitionConfig, ruleId: String): String {
    val state = RuleCatalog.stateOf(config, ruleId)
    val handling = handlingLabel(state)
    if (ruleId != NAME_RULE || state == RuleState.OFF) return handling
    return handling + stringResource(if (config.outlineUnanchoredNames) R.string.name_summary_outline else R.string.name_summary_skip)
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
@Composable
internal fun handlingLabel(state: RuleState): String = stringResource(
    when (state) {
        RuleState.MASKED -> R.string.handling_masked
        RuleState.OUTLINED -> R.string.handling_outlined
        RuleState.OFF -> R.string.handling_off
    },
)

/** 「nano-v3 · 已就绪」：先回答是哪一版，再说模型在不在本机。本机没有就只写「本机没有」。 */
@Composable
internal fun nanoSummary(nano: NanoStatus?): String = when (nano) {
    null -> stringResource(R.string.nano_checking)
    is NanoStatus.Unavailable -> stringResource(R.string.nano_absent)
    is NanoStatus.Present -> "${nano.version ?: stringResource(R.string.nano_version_unknown)} · " + stringResource(
        when (nano.model) {
            NanoStatus.Model.READY -> R.string.nano_ready
            NanoStatus.Model.DOWNLOADING -> R.string.nano_downloading
            NanoStatus.Model.NOT_DOWNLOADED -> R.string.nano_not_downloaded
        },
    )
}

/** 哪些机型有 Gemini Nano：Google 的文档。页尾「支持设备列表参考官方文档」那一句指向它。 */
internal const val GENAI_DOCS_URL = "https://developers.google.cn/ml-kit/genai"

/** 「支持设备列表参考官方文档。」，「官方文档」是个链接。 */
@Composable
private fun supportedDevicesSentence(): AnnotatedString = buildAnnotatedString {
    append(stringResource(R.string.supported_devices_prefix))
    append(footerLink(stringResource(R.string.official_docs), GENAI_DOCS_URL))
    append(stringResource(R.string.sentence_end))
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
        nano.errorCode?.let { append(stringResource(R.string.aicore_error, it)) }
    }
    is NanoStatus.Present -> when (nano.model) {
        NanoStatus.Model.NOT_DOWNLOADED -> AnnotatedString(stringResource(R.string.nano_download_hint))
        NanoStatus.Model.READY, NanoStatus.Model.DOWNLOADING -> null
    }
}

/**
 * ML Kit 的几个模型写成一行：「文字 16.0.1 · 人脸 16.1.7 · 条码 17.3.0」。
 * 英文、中文两个文字模型多半同版，同版只写一次，不同才分开写。
 */
internal fun mlKitSummary(res: Resources, text: String, textChinese: String, face: String, barcode: String): String {
    val textVersions = if (text == textChinese) text else res.getString(R.string.mlkit_text_versions, text, textChinese)
    return res.getString(R.string.mlkit_summary, textVersions, face, barcode)
}

@StringRes
internal fun textEngineLabel(option: TextEngineOption): Int = when (option) {
    TextEngineOption.PADDLE -> R.string.engine_paddle
    TextEngineOption.LATIN -> R.string.engine_latin
    TextEngineOption.CHINESE -> R.string.engine_chinese
    TextEngineOption.BOTH -> R.string.engine_both
}

@StringRes
internal fun faceModeLabel(option: FaceOption): Int = when (option) {
    FaceOption.FAST -> R.string.face_fast
    FaceOption.ACCURATE -> R.string.face_accurate
}

@StringRes
internal fun barcodeModeLabel(option: BarcodeOption): Int = when (option) {
    BarcodeOption.STRICT -> R.string.barcode_strict
    BarcodeOption.LOOSE -> R.string.barcode_loose
}

@StringRes
internal fun scanStyleLabel(style: ScanStyle): Int = when (style) {
    ScanStyle.SWEEP -> R.string.scan_sweep
    ScanStyle.FROST -> R.string.scan_frost
}

@StringRes
internal fun themeColorLabel(color: ThemeColor): Int = when (color) {
    ThemeColor.SYSTEM -> R.string.follow_system
    ThemeColor.PURPLE -> R.string.theme_purple
    ThemeColor.BLUE -> R.string.theme_blue
    ThemeColor.TEAL -> R.string.theme_teal
    ThemeColor.GREEN -> R.string.theme_green
    ThemeColor.ORANGE -> R.string.theme_orange
    ThemeColor.RED -> R.string.theme_red
    ThemeColor.PINK -> R.string.theme_pink
}
