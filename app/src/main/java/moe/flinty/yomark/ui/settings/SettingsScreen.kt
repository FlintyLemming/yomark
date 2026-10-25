package moe.flinty.yomark.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.R
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.AppLanguage

/**
 * 设置一级页以下的各页。手机上每一页是同一个 [SettingsPageActivity] 带着不同的参数打开的，宽屏上在一级页的右栏里（见 SettingsHost）。
 * TEXT_ENGINE 从「AI」页点进去（2026-10-16 前在「文字」页）；TEXT_RULE 从「文字」页点进去，另带一个规则 id，说明是哪一类。
 * 谁从哪一页点进去见 [parent]。
 */
enum class SettingsPage { TEXT, TEXT_ENGINE, TEXT_RULE, FACE, BARCODE, AI, GENERAL, APPEARANCE, LABS, DEVELOPER, LANGUAGE, ABOUT, DEBUG_TRACE }

/**
 * 设置的一级页（2026-10-07 起，见 docs/superpowers/specs/2026-10-07-yomark-settings-pages-design.md）。
 *
 * 只是一张目录：每一项点进去是一页，一级页上不直接摆选项。原先文字识别、条码、人脸这几根轴的选项
 * 直接铺在一级页上，规则却收在二级页里，层级对不齐；现在一律收进二级页，一级页每一项下面写着它眼下的状态。
 *
 * 第一组是截图里认得出的三样东西：文字、人脸、条码，认出来怎么办都在各自那一页里。
 * 「文字识别」原先单独一项，2026-10-09 并进了「文字」；其中的识别引擎 2026-10-16 又挪去了「AI」。
 * 第二组是 AI（识别引擎，本机的 Gemini Nano 和随包的识别模型）、通用和外观。
 * 「通用」是 2026-10-15 由「编辑」「导出」两项并成的，那两页各只有一项。
 * 最后是关于：版本号、隐私权政策、恢复默认设置（隐私权政策原先单独一行，恢复默认原先是一级页的最后一项，2026-10-11 都挪了进去）。
 * 关于上面是「语言」（2026-10-12 起），小字写着眼下的界面语言；再往下是「帮助」（2026-10-16 起），点了重新放一遍首次引导。
 * 在关于里连点版本号打开调试信息之后（[developer]），语言上面再多一项「开发者」，照着 Android 系统设置里开发者选项的样子。
 * 最后一组打头是「实验室」（2026-10-15 起）：还不够稳、出厂关着的功能，眼下只有聊天记录（原先在第一组）。
 *
 * AI 那一行的小字是固定的，不写本机 Gemini Nano 眼下的情况：那得去问系统的 AICore 服务，
 * 不值得每次打开设置都问一遍，点进去再看。
 *
 * 宽屏上分两栏时（2026-10-17 起，见 SettingsHost）这一页是左栏，[selected] 是右栏开着的那一项，那一行换了底色。
 * 开发者页开在右栏时，在那一页里关掉调试信息，「开发者」这一项先留着，换到别的项才不见，
 * 和手机上「回到一级页才不见」一样。
 */
@Composable
fun SettingsScreen(
    config: RecognitionConfig,
    onOpen: (SettingsPage) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    onReplayTour: () -> Unit = {},
    developer: Boolean = false,
    language: AppLanguage = AppLanguage.SYSTEM,
    selected: SettingsPage? = null,
) {
    // 不分栏（selected 为 null）时各行只是能点，分栏时才有选中不选中
    fun selects(page: SettingsPage): Boolean? = selected?.let { it == page }
    SettingsScaffold(title = stringResource(R.string.settings), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                NavigationRow(
                    title = stringResource(R.string.settings_text),
                    summary = textSummary(config),
                    icon = Icons.Outlined.TextFields,
                    iconColors = SettingsIconColors.blue,
                    onClick = { onOpen(SettingsPage.TEXT) },
                    selected = selects(SettingsPage.TEXT),
                )
                NavigationRow(
                    title = stringResource(R.string.settings_face),
                    summary = detectorSummary(config.faceState, stringResource(faceModeLabel(config.face))),
                    icon = Icons.Outlined.Face,
                    iconColors = SettingsIconColors.orange,
                    onClick = { onOpen(SettingsPage.FACE) },
                    selected = selects(SettingsPage.FACE),
                )
                NavigationRow(
                    title = stringResource(R.string.settings_barcode),
                    summary = detectorSummary(config.barcodeState, stringResource(barcodeModeLabel(config.barcode))),
                    icon = Icons.Outlined.QrCode2,
                    iconColors = SettingsIconColors.green,
                    onClick = { onOpen(SettingsPage.BARCODE) },
                    selected = selects(SettingsPage.BARCODE),
                )
            }
        }

        item {
            GroupSpacer()
            SettingsGroup {
                NavigationRow(
                    title = stringResource(R.string.settings_ai),
                    summary = stringResource(R.string.settings_ai_summary),
                    icon = Icons.Outlined.AutoAwesome,
                    iconColors = SettingsIconColors.yellow,
                    onClick = { onOpen(SettingsPage.AI) },
                    selected = selects(SettingsPage.AI),
                )
                NavigationRow(
                    title = stringResource(R.string.settings_general),
                    summary = stringResource(R.string.settings_general_summary),
                    icon = Icons.Outlined.Tune,
                    iconColors = SettingsIconColors.purple,
                    onClick = { onOpen(SettingsPage.GENERAL) },
                    selected = selects(SettingsPage.GENERAL),
                )
                NavigationRow(
                    title = stringResource(R.string.settings_appearance),
                    summary = stringResource(R.string.settings_appearance_summary),
                    icon = Icons.Outlined.Palette,
                    iconColors = SettingsIconColors.pink,
                    onClick = { onOpen(SettingsPage.APPEARANCE) },
                    selected = selects(SettingsPage.APPEARANCE),
                )
            }
        }

        item {
            GroupSpacer()
            SettingsGroup {
                NavigationRow(
                    title = stringResource(R.string.settings_labs),
                    summary = stringResource(R.string.settings_labs_summary),
                    icon = Icons.Outlined.Science,
                    iconColors = SettingsIconColors.teal,
                    onClick = { onOpen(SettingsPage.LABS) },
                    selected = selects(SettingsPage.LABS),
                )
                if (developer || selected == SettingsPage.DEVELOPER) {
                    NavigationRow(
                        title = stringResource(R.string.settings_developer),
                        summary = stringResource(R.string.settings_developer_summary),
                        icon = Icons.Outlined.Code,
                        iconColors = SettingsIconColors.purple,
                        onClick = { onOpen(SettingsPage.DEVELOPER) },
                        selected = selects(SettingsPage.DEVELOPER),
                    )
                }
                NavigationRow(
                    title = stringResource(R.string.settings_language),
                    summary = languageLabel(language),
                    icon = Icons.Outlined.Language,
                    iconColors = SettingsIconColors.indigo,
                    onClick = { onOpen(SettingsPage.LANGUAGE) },
                    selected = selects(SettingsPage.LANGUAGE),
                )
                NavigationRow(
                    title = stringResource(R.string.settings_help),
                    summary = stringResource(R.string.settings_help_summary),
                    icon = Icons.AutoMirrored.Outlined.HelpOutline,
                    iconColors = SettingsIconColors.blue,
                    onClick = onReplayTour,
                )
                NavigationRow(
                    title = stringResource(R.string.settings_about),
                    summary = stringResource(R.string.settings_about_summary, BuildConfig.VERSION_NAME),
                    icon = Icons.Outlined.Info,
                    iconColors = SettingsIconColors.gray,
                    onClick = { onOpen(SettingsPage.ABOUT) },
                    selected = selects(SettingsPage.ABOUT),
                )
            }
        }
    }
}

/** 「打码 15 类 · 关闭 5 类」：没有关掉的就只写打码几类。识别引擎原先也写在最后，2026-10-16 跟着挪去了「AI」。 */
@Composable
internal fun textSummary(config: RecognitionConfig): String {
    val counts = RuleCatalog.all.groupingBy { RuleCatalog.stateOf(config, it.id) }.eachCount()
    return listOf(
        RuleState.MASKED to R.string.summary_masked,
        RuleState.OFF to R.string.summary_off,
    ).mapNotNull { (state, label) ->
        counts[state]?.let { stringResource(R.string.count_kinds, it, stringResource(label)) }
    }.joinToString(" · ")
}

/** 人脸、条码那一行：「打码 · 快速」；关掉了就只写「关闭」，模式无所谓了。 */
@Composable
internal fun detectorSummary(state: RuleState, modeLabel: String): String =
    if (state == RuleState.OFF) handlingLabel(state) else "${handlingLabel(state)} · $modeLabel"
