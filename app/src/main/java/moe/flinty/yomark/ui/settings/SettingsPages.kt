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
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.AssignmentInd
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Cake
import androidx.compose.material.icons.outlined.CarRepair
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Domain
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FlightTakeoff
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonSearch
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
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.engine.ppocr.PpOcrModels
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.components.ColorSwatch
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.ThemeMode
import moe.flinty.yomark.ui.theme.colorScheme
import moe.flinty.yomark.ui.theme.isDark
import moe.flinty.yomark.ui.theme.systemDynamicColorAvailable

/*
 * 设置一级页下面的各页：文字（下面还有各类文字自己的页）、人脸、条码、AI（下面还有识别引擎）、通用、外观、实验室。
 * 每根轴下面写清楚它的代价，但**不写「推荐」**：哪个好正是用户要自己看的。
 * 逐项开关，不给命名预设——预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 *
 * 改动即时生效，没有「应用」按钮：写进 DataStore，编辑器的 ViewModel 收到就重跑当前这张图。
 * 通用和外观两页不影响识别，换它们不重跑。AI 页上选识别引擎，下面显示本机的 Gemini Nano 和随包的 PP-OCR、ML Kit。
 *
 * 行下的小字、页尾说明都不带句号（2026-10-15 起），几句话并成一句，中间用逗号、分号隔开。
 */

/**
 * 文字：认出来的各类敏感信息，每类一行、小字写着眼下怎么处理，点进去是这一类自己的页（[TextRuleSettingsScreen]）。
 *
 * 各类原先在行尾直接摆一排「关 / 圈出 / 打码」；人名多了一个只属于它的开关之后，行尾放不下，
 * 一类一页又和人脸、条码的页是同一个样子：先是「识别到时怎么办」，再是这一类自己的选项和认法。
 * 上面原先还有「文字识别」一组（2026-10-09 由单独一页并进来）：识别引擎，和 AI 增强识别的开关（2026-10-15 去掉了，
 * 它总是开着，见 refinersFor）。识别引擎 2026-10-16 挪去了「AI」页，和它选的 PP-OCR、ML Kit 两个模型放在一起。
 *
 * 右上角的「恢复默认」只管这一页和它下面的各页：各类的处理方式和人名的开关。
 */
@Composable
fun TextSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onOpenRule: (ruleId: String) -> Unit,
    onNavigateUp: (() -> Unit)?,
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
        item { SectionHeader(stringResource(R.string.sensitive_info), first = true) }
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

/** 识别引擎：OCR 用 PP-OCR 还是 ML Kit。从「AI」页点进来。 */
@Composable
fun TextEngineSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: (() -> Unit)?,
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
                                TextEngineOption.MLKIT -> R.string.engine_mlkit_summary
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
 * 文字里的一类，样子和人脸、条码的页一样：先是「识别到时」打码 / 关闭，再是这一类怎么认的。
 * 人名多一组：没有旁证、只凭字面猜出的名字要不要也打码。
 */
@Composable
fun TextRuleSettingsScreen(
    ruleId: String,
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: (() -> Unit)?,
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
                        title = stringResource(R.string.mask_unanchored),
                        summary = stringResource(R.string.mask_unanchored_summary),
                        icon = Icons.Outlined.PersonSearch,
                        checked = config.maskUnanchoredNames,
                        enabled = state != RuleState.OFF,
                        onCheckedChange = { onChange(config.copy(maskUnanchoredNames = it)) },
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
    onNavigateUp: (() -> Unit)?,
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

/** 条码：同人脸。宽松模式下解不出内容的疑似条码也打码。 */
@Composable
fun BarcodeSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: (() -> Unit)?,
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
 * 实验室：还不够稳、出厂关着的功能，一项一个开关，说明写在各自的页脚。
 *
 * 识别聊天记录（2026-10-15 从一级页挪进来，原先叫「聊天记录」）：认出是聊天截图之后，遮住头像、昵称、聊天标题。
 * 三类共用一个开关：用户要遮的是「这段对话是谁和谁」，分开设没有意义。开着就是打码。
 * 页脚只说遮什么和一句局限（认不全、可能认错），怎么认的、适用哪些应用不写。
 *
 * 原先还有一项「外部模型」（2026-10-15）：AI 增强识别改用用户自己配置的大模型。2026-10-18 删掉了，应用要能声明没有联网权限。
 */
@Composable
fun LabsSettingsScreen(
    config: RecognitionConfig,
    onChange: (RecognitionConfig) -> Unit,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.settings_labs), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.settings_chat),
                    icon = Icons.Outlined.Forum,
                    checked = config.chatState != RuleState.OFF,
                    onCheckedChange = { on -> onChange(config.copy(chatState = if (on) RuleState.MASKED else RuleState.OFF)) },
                )
            }
            FooterText(stringResource(R.string.chat_footer))
        }
    }
}

/**
 * AI：最上面是识别引擎（2026-10-16 从「文字」页挪来），点进去选 PP-OCR 还是 ML Kit（[TextEngineSettingsScreen]）。
 * 下面是本机用到的模型——端侧大模型 Gemini Nano（有没有、是哪一版、下好了没有），
 * 随包的 PP-OCR 和 ML Kit（版本都是打包时定的；ML Kit 几个模型并成一行）。这几样只显示。
 * AI 增强识别没有开关，总是开着。
 *
 * Gemini Nano 卡片下面是一段话：先说它是什么，再接着说本机这边要知道的（[nanoNote]），不换行。
 *
 * @param nano 由 Activity 进页时问一次 AICore 得来；null 是还没问完。
 */
@Composable
fun AiSettingsScreen(
    nano: NanoStatus?,
    textEngine: TextEngineOption,
    onOpenEngine: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.settings_ai), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.text_recognition), first = true)
            SettingsGroup {
                LinkRow(
                    title = stringResource(R.string.engine),
                    summary = stringResource(textEngineLabel(textEngine)),
                    icon = Icons.Outlined.DocumentScanner,
                    onClick = onOpenEngine,
                    modifier = Modifier.testTag("engine"),
                )
            }
        }
        item {
            SectionHeader(stringResource(R.string.ai_model))
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
                    nanoNote(nano)?.let { append(stringResource(R.string.clause_separator)); append(it) }
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
 * 通用（2026-10-15 起）：原先的「编辑」「导出」两页各只有一项，并成这一页，一项一组。
 * 导出那一组的标题是「水印样式」不是「导出」：几个选项只写了「图标 + 有码」，不说是水印就看不懂。
 * 都不在识别方案里：改它们编辑器不重跑识别。
 *
 * 编辑：「换样式时一起换」，出厂开着（见 SettingsStore.restyleAllMasks）。
 *
 * 识别：「按原分辨率识别」，出厂关着（见 SettingsStore.fullResolutionOcr）。不在识别页：它管的是快慢，不是认什么。
 *
 * 导出：品牌水印用哪一版字样，没选过时跟着系统语言（见 BrandWatermarkStyle）。
 * 「无水印」只在解锁后出现（买过，或开源版点过去除水印）；解锁了没选过就是它，见 brandWatermarkApplies。
 * 原先还有一项「导出前提醒」，兜的是「圈出」这一态，2026-10-14 和圈出一起去掉了。
 */
@Composable
fun GeneralSettingsScreen(
    restyleAll: Boolean,
    onRestyleAllChange: (Boolean) -> Unit,
    fullResolution: Boolean,
    onFullResolutionChange: (Boolean) -> Unit,
    watermarkStyle: BrandWatermarkStyle,
    onWatermarkStyleChange: (BrandWatermarkStyle) -> Unit,
    /** 能不能去水印。false 时没有「无水印」这一项。 */
    watermarkUnlocked: Boolean,
    /** 导出不加品牌水印。只在 [watermarkUnlocked] 时有意义。 */
    watermarkOff: Boolean,
    onWatermarkOff: () -> Unit,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val none = watermarkUnlocked && watermarkOff
    SettingsScaffold(title = stringResource(R.string.settings_general), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.settings_edit), first = true)
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.restyle_all),
                    summary = stringResource(R.string.restyle_all_summary),
                    icon = Icons.Outlined.DoneAll,
                    checked = restyleAll,
                    onCheckedChange = onRestyleAllChange,
                )
            }
        }
        item {
            SectionHeader(stringResource(R.string.settings_recognize))
            SettingsGroup {
                SwitchRow(
                    title = stringResource(R.string.full_resolution_ocr),
                    summary = stringResource(R.string.full_resolution_ocr_summary),
                    icon = Icons.Outlined.HighQuality,
                    checked = fullResolution,
                    onCheckedChange = onFullResolutionChange,
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
 * 外观：深色模式、主题色与识别动效，都只是样子。
 *
 * 深色模式不在 SettingsStore 里，由调用方从 AppThemeMode 读、交给它改。
 *
 * 主题色：「跟随系统」（莫奈取色）是一个单选项，下面一排是预设色。
 * 色块画的是选它之后的主色，不是种子色——按钮、选中态最后就是这个颜色，深色界面里就是深色那套的主色。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeColor: ThemeColor,
    onThemeColorChange: (ThemeColor) -> Unit,
    scanStyle: ScanStyle,
    onScanStyleChange: (ScanStyle) -> Unit,
    onNavigateUp: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val dark = MaterialTheme.colorScheme.isDark
    val presets = remember(context, dark) {
        ThemeColor.entries.filter { it != ThemeColor.SYSTEM }.associateWith { it.colorScheme(context, dark).primary }
    }
    SettingsScaffold(title = stringResource(R.string.settings_appearance), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            SectionHeader(stringResource(R.string.theme_mode), first = true)
            SettingsGroup(Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    RadioRow(
                        title = stringResource(themeModeLabel(mode)),
                        selected = themeMode == mode,
                        onClick = { onThemeModeChange(mode) },
                    )
                }
            }
        }
        item {
            SectionHeader(stringResource(R.string.theme_color))
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

/** 人脸、条码、文字的各类共用：打码 / 关闭，打码排第一。 */
@Composable
private fun HandlingGroup(selected: RuleState, onSelect: (RuleState) -> Unit) {
    SettingsGroup(Modifier.selectableGroup()) {
        listOf(RuleState.MASKED, RuleState.OFF).forEach { state ->
            RadioRow(
                title = handlingLabel(state),
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
    SensitiveKind.IP_ADDR -> Icons.Outlined.Lan
    SensitiveKind.MAC_ADDR -> Icons.Outlined.Router
    SensitiveKind.URL -> Icons.Outlined.Link
    SensitiveKind.API_KEY -> Icons.Outlined.Key
    SensitiveKind.LONG_NUMBER -> Icons.Outlined.Numbers
    SensitiveKind.DATETIME -> Icons.Outlined.Schedule
    SensitiveKind.BIRTH_DATE -> Icons.Outlined.Cake
    SensitiveKind.PICKUP_CODE -> Icons.Outlined.Inventory2
    SensitiveKind.LICENSE_PLATE -> Icons.Outlined.DirectionsCar
    SensitiveKind.POSTAL_ADDRESS -> Icons.Outlined.Place
    SensitiveKind.PERSON_NAME -> Icons.Outlined.Person
    SensitiveKind.ORG_NAME -> Icons.Outlined.Domain
    SensitiveKind.ID_NUMBER -> Icons.Outlined.AssignmentInd
    SensitiveKind.VIN -> Icons.Outlined.CarRepair
    SensitiveKind.CHAT_AVATAR -> Icons.Outlined.AccountCircle
    SensitiveKind.CHAT_NAME -> Icons.Outlined.ChatBubbleOutline
    SensitiveKind.CHAT_TITLE -> Icons.Outlined.Forum
    SensitiveKind.FACE -> Icons.Outlined.Face
    SensitiveKind.BARCODE -> Icons.Outlined.QrCode2
    SensitiveKind.MANUAL -> Icons.Outlined.Edit
}

/** 人名一类的规则 id：它的页上多一个开关，「文字」页上它那一行的小字也多一截。 */
private const val NAME_RULE = "name"

/**
 * 「文字」页上一类的小字：怎么处理。人名再写上没有旁证的那些怎么办——
 * 不写的话，聊天里的人名不打了，用户只看得到「打码」两个字。
 */
@Composable
internal fun ruleSummary(config: RecognitionConfig, ruleId: String): String {
    val state = RuleCatalog.stateOf(config, ruleId)
    val handling = handlingLabel(state)
    if (ruleId != NAME_RULE || state == RuleState.OFF) return handling
    return handling + stringResource(if (config.maskUnanchoredNames) R.string.name_summary_mask else R.string.name_summary_skip)
}

/**
 * 「文字」页右上角的「恢复默认」：这一页和它下面各页的项回到出厂值，人脸、条码不动，
 * 识别引擎（2026-10-16 挪去了「AI」页）也不动。出厂值取自 [RecognitionConfig] 的缺省值，不另抄一份。
 */
internal fun RecognitionConfig.withTextDefaults(): RecognitionConfig {
    val factory = RecognitionConfig()
    return copy(
        maskUnanchoredNames = factory.maskUnanchoredNames,
        ruleOverrides = emptyMap(),
    )
}

/** 打码 / 关闭。人脸、条码、文字的各类共用。 */
@Composable
internal fun handlingLabel(state: RuleState): String = stringResource(
    when (state) {
        RuleState.MASKED -> R.string.handling_masked
        RuleState.OFF -> R.string.handling_off
    },
)

/** 「nano-v3 · 已就绪」：先回答是哪一版，再说模型在不在本机。用不了就只写「不可用」。 */
@Composable
internal fun nanoSummary(nano: NanoStatus?): String = when (nano) {
    null -> stringResource(R.string.nano_checking)
    is NanoStatus.Unavailable -> stringResource(R.string.nano_unavailable)
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

/** 「支持设备列表参考官方文档」，「官方文档」是个链接，放在最后。 */
@Composable
private fun supportedDevicesClause(): AnnotatedString = buildAnnotatedString {
    append(stringResource(R.string.supported_devices_prefix))
    append(footerLink(stringResource(R.string.official_docs), GENAI_DOCS_URL))
}

/**
 * 卡片下面那段话里，接在「Google 的端侧大模型」后面的半句：用不了就指去看哪些机型有（AICore 报了错再带上错误码），
 * 模型还没下载就说系统什么时候下。已就绪、下载中的小字已经说清了，不再重复；还没问完时不写。
 *
 * 未下载时不在这里摆下载按钮：打开一张图，编辑页探测时就会请 AICore 去下（见 NanoClient.ready）。
 */
@Composable
internal fun nanoNote(nano: NanoStatus?): AnnotatedString? = when (nano) {
    null -> null
    is NanoStatus.Unavailable -> buildAnnotatedString {
        append(supportedDevicesClause())
        nano.errorCode?.let {
            append(stringResource(R.string.clause_separator))
            append(stringResource(R.string.aicore_error, it))
        }
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
    TextEngineOption.MLKIT -> R.string.engine_mlkit
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
    ScanStyle.FROST -> R.string.scan_frost
    ScanStyle.SWEEP -> R.string.scan_sweep
}

@StringRes
internal fun themeModeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.follow_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
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
