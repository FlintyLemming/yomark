package moe.flinty.yomark.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.rules.RuleCatalog

/**
 * 设置一级页以下的各页。每一页是同一个 [SettingsPageActivity] 带着不同的参数打开的。
 * TEXT_ENGINE 与 TEXT_RULE 从「文字」页点进去；TEXT_RULE 另带一个规则 id，说明是哪一类。
 */
enum class SettingsPage { TEXT, TEXT_ENGINE, TEXT_RULE, FACE, BARCODE, AI, EXPORT, APPEARANCE, DEVELOPER, ABOUT, DEBUG_TRACE }

/**
 * 设置的一级页（2026-10-07 起，见 docs/superpowers/specs/2026-10-07-yomark-settings-pages-design.md）。
 *
 * 只是一张目录：每一项点进去是一页，一级页上不直接摆选项。原先文字识别、条码、人脸这几根轴的选项
 * 直接铺在一级页上，规则却收在二级页里，层级对不齐；现在一律收进二级页，一级页每一项下面写着它眼下的状态。
 *
 * 第一组是截图里认得出的三样东西：文字、人脸、条码。怎么认、认出来怎么办，都在各自那一页里——
 * 「文字识别」原先单独一项，2026-10-09 并进了「文字」。第二组是 AI（本机的 Gemini Nano）、导出和外观。
 * 最后是关于：版本号、隐私权政策、恢复默认设置（隐私权政策原先单独一行，恢复默认原先是一级页的最后一项，2026-10-11 都挪了进去）。
 * 在关于里连点版本号打开调试信息之后（[developer]），关于上面多一项「开发者」，照着 Android 系统设置里开发者选项的样子。
 *
 * AI 那一行的小字是固定的，不写本机 Gemini Nano 眼下的情况：那得去问系统的 AICore 服务，
 * 不值得每次打开设置都问一遍，点进去再看。
 */
@Composable
fun SettingsScreen(
    config: RecognitionConfig,
    exportReminder: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    developer: Boolean = false,
) {
    SettingsScaffold(title = "设置", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                NavigationRow(
                    title = "文字",
                    summary = textSummary(config),
                    icon = Icons.Outlined.TextFields,
                    iconColors = SettingsIconColors.blue,
                    onClick = { onOpen(SettingsPage.TEXT) },
                )
                NavigationRow(
                    title = "人脸",
                    summary = detectorSummary(config.faceState, faceModeLabel(config.face) + "识别"),
                    icon = Icons.Outlined.Face,
                    iconColors = SettingsIconColors.orange,
                    onClick = { onOpen(SettingsPage.FACE) },
                )
                NavigationRow(
                    title = "条码",
                    summary = detectorSummary(config.barcodeState, barcodeModeLabel(config.barcode) + "识别"),
                    icon = Icons.Outlined.QrCode2,
                    iconColors = SettingsIconColors.green,
                    onClick = { onOpen(SettingsPage.BARCODE) },
                )
            }
        }

        item {
            GroupSpacer()
            SettingsGroup {
                NavigationRow(
                    title = "AI",
                    summary = "Gemini Nano、PP-OCR、ML Kit",
                    icon = Icons.Outlined.AutoAwesome,
                    iconColors = SettingsIconColors.yellow,
                    onClick = { onOpen(SettingsPage.AI) },
                )
                NavigationRow(
                    title = "导出",
                    summary = if (exportReminder) "导出前提醒已开启" else "导出前提醒已关闭",
                    icon = Icons.Outlined.IosShare,
                    iconColors = SettingsIconColors.teal,
                    onClick = { onOpen(SettingsPage.EXPORT) },
                )
                NavigationRow(
                    title = "外观",
                    summary = "主题色、识别动效",
                    icon = Icons.Outlined.Palette,
                    iconColors = SettingsIconColors.pink,
                    onClick = { onOpen(SettingsPage.APPEARANCE) },
                )
            }
        }

        item {
            GroupSpacer()
            SettingsGroup {
                if (developer) {
                    NavigationRow(
                        title = "开发者",
                        summary = "调试信息、调试记录",
                        icon = Icons.Outlined.Code,
                        iconColors = SettingsIconColors.purple,
                        onClick = { onOpen(SettingsPage.DEVELOPER) },
                    )
                }
                NavigationRow(
                    title = "关于",
                    summary = "版本 ${BuildConfig.VERSION_NAME} · 隐私权政策 · 恢复默认设置",
                    icon = Icons.Outlined.Info,
                    iconColors = SettingsIconColors.gray,
                    onClick = { onOpen(SettingsPage.ABOUT) },
                )
            }
        }
    }
}

/** 「打码 11 类 · 圈出 5 类 · PP-OCR」：有关掉的再加一段「关闭 1 类」，最后是识别引擎。 */
internal fun textSummary(config: RecognitionConfig): String {
    val counts = RuleCatalog.all.groupingBy { RuleCatalog.stateOf(config, it.id) }.eachCount()
    return (
        listOf(RuleState.MASKED to "打码", RuleState.OUTLINED to "圈出", RuleState.OFF to "关闭")
            .mapNotNull { (state, label) -> counts[state]?.let { "$label $it 类" } } +
            textEngineLabel(config.textEngine)
        ).joinToString(" · ")
}

/** 人脸、条码那一行：「打码 · 快速」；关掉了就只写「关闭」，模式无所谓了。 */
internal fun detectorSummary(state: RuleState, modeLabel: String): String =
    if (state == RuleState.OFF) handlingLabel(state) else "${handlingLabel(state)} · $modeLabel"
