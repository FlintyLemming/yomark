package moe.flinty.yomark.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.rules.RuleCatalog

/**
 * 隐私权政策，Play 要求商品详情和应用内都能打开。页面源文件在 site/privacy/index.html，和落地页一起由 GitHub Pages 发布。
 * 交给系统浏览器打开，应用本身仍然没有网络权限。
 */
const val PRIVACY_POLICY_URL = "https://yomark.flinty.moe/privacy/"

/**
 * 设置一级页以下的各页。每一页是同一个 [SettingsPageActivity] 带着不同的参数打开的。
 * TEXT_ENGINE 与 TEXT_RULE 从「文字」页点进去；TEXT_RULE 另带一个规则 id，说明是哪一类。
 */
enum class SettingsPage { TEXT, TEXT_ENGINE, TEXT_RULE, FACE, BARCODE, AI, EXPORT, APPEARANCE }

/**
 * 设置的一级页（2026-10-07 起，见 docs/superpowers/specs/2026-10-07-yomark-settings-pages-design.md）。
 *
 * 只是一张目录：每一项点进去是一页，一级页上不直接摆选项。原先文字识别、条码、人脸这几根轴的选项
 * 直接铺在一级页上，规则却收在二级页里，层级对不齐；现在一律收进二级页，一级页每一项下面写着它眼下的状态。
 *
 * 第一组是截图里认得出的三样东西：文字、人脸、条码。怎么认、认出来怎么办，都在各自那一页里——
 * 「文字识别」原先单独一项，2026-10-09 并进了「文字」。第二组是 AI（本机的 Gemini Nano）、导出和外观。
 * 再往下是隐私权政策，最后一项恢复默认，点了先问一句。
 *
 * AI 那一行的小字是固定的，不写本机 Gemini Nano 眼下的情况：那得去问系统的 AICore 服务，
 * 不值得每次打开设置都问一遍，点进去再看。
 *
 * 隐私权政策那一行把网址写在摘要里：手机上没有浏览器能接时（[openUrl] 抛异常），用户照着抄也能打开。
 */
@Composable
fun SettingsScreen(
    config: RecognitionConfig,
    exportReminder: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onReset: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    openUrl: (String) -> Unit = LocalUriHandler.current::openUri,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }

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
                    summary = "Gemini Nano 与 PP-OCR 模型",
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
                NavigationRow(
                    title = "隐私权政策",
                    summary = PRIVACY_POLICY_URL.removePrefix("https://"),
                    icon = Icons.Outlined.Policy,
                    iconColors = SettingsIconColors.gray,
                    onClick = { runCatching { openUrl(PRIVACY_POLICY_URL) } },
                )
            }
        }

        item {
            GroupSpacer()
            SettingsGroup {
                NavigationRow(
                    title = "恢复默认设置",
                    summary = "除外观外，全部恢复出厂值",
                    icon = Icons.Outlined.SettingsBackupRestore,
                    iconColors = SettingsIconColors.gray,
                    onClick = { confirmReset = true },
                )
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
            title = { Text("恢复默认设置？") },
            text = { Text("文字、人脸、条码和导出的设置都会恢复出厂值。主题色和识别动效不变。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    onReset()
                }) { Text("恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } },
        )
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
