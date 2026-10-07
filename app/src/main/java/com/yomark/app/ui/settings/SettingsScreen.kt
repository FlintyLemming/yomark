package com.yomark.app.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Palette
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
import com.yomark.app.engine.RecognitionConfig
import com.yomark.app.engine.RuleState
import com.yomark.app.engine.SemanticOption
import com.yomark.app.rules.RuleCatalog

/** 设置的二级页。每一页是同一个 [SettingsPageActivity] 带着不同的参数打开的。 */
enum class SettingsPage { TEXT_RULES, FACE, BARCODE, TEXT_RECOGNITION, EXPORT, APPEARANCE }

/**
 * 设置的一级页（2026-10-07 起，见 docs/superpowers/specs/2026-10-07-yomark-settings-pages-design.md）。
 *
 * 只是一张目录：每一项点进去是一页，一级页上不直接摆选项。原先文字识别、条码、人脸这几根轴的选项
 * 直接铺在一级页上，规则却收在二级页里，层级对不齐；现在一律收进二级页，一级页每一项下面写着它眼下的状态。
 *
 * 前三项是「识别到了怎么办」：文字、人脸、条码，各自都能设成打码、仅圈出或关闭；
 * 后三项是怎么识别、导出和外观。最后一项恢复默认，点了先问一句。
 */
@Composable
fun SettingsScreen(
    config: RecognitionConfig,
    exportReminder: Boolean,
    onOpen: (SettingsPage) -> Unit,
    onReset: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmReset by rememberSaveable { mutableStateOf(false) }

    SettingsScaffold(title = "设置", onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup {
                NavigationRow(
                    title = "文字",
                    summary = textRulesSummary(config),
                    icon = Icons.Outlined.TextFields,
                    iconColors = SettingsIconColors.blue,
                    onClick = { onOpen(SettingsPage.TEXT_RULES) },
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
                    title = "文字识别",
                    summary = textEngineLabel(config.textEngine) + " · AI 复查" +
                        if (config.semantic == SemanticOption.OFF) "关闭" else "开启",
                    icon = Icons.Outlined.DocumentScanner,
                    iconColors = SettingsIconColors.purple,
                    onClick = { onOpen(SettingsPage.TEXT_RECOGNITION) },
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
            text = { Text("文字、人脸、条码、文字识别和导出的设置都会恢复出厂值。主题色和识别动效不变。") },
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

/** 「打码 11 类 · 圈出 5 类」，有关掉的再加一段「关闭 1 类」。 */
internal fun textRulesSummary(config: RecognitionConfig): String {
    val counts = RuleCatalog.all.groupingBy { RuleCatalog.stateOf(config, it.id) }.eachCount()
    return listOf(RuleState.MASKED to "打码", RuleState.OUTLINED to "圈出", RuleState.OFF to "关闭")
        .mapNotNull { (state, label) -> counts[state]?.let { "$label $it 类" } }
        .joinToString(" · ")
}

/** 人脸、条码那一行：「打码 · 快速」；关掉了就只写「关闭」，模式无所谓了。 */
internal fun detectorSummary(state: RuleState, modeLabel: String): String =
    if (state == RuleState.OFF) handlingLabel(state) else "${handlingLabel(state)} · $modeLabel"
