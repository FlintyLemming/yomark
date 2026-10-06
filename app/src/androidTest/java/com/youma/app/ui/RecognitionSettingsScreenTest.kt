package com.youma.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.TextEngineOption
import com.youma.app.engine.RuleState
import com.youma.app.rules.RuleCatalog
import com.youma.app.ui.canvas.ScanStyle
import com.youma.app.ui.settings.RecognitionSettingsScreen
import com.youma.app.ui.settings.RuleSettingsScreen
import org.junit.Rule
import org.junit.Test

class RecognitionSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun screen(
        config: RecognitionConfig = RecognitionConfig(),
        onChange: (RecognitionConfig) -> Unit = {},
        scanStyle: ScanStyle = ScanStyle.SWEEP,
        onScanStyleChange: (ScanStyle) -> Unit = {},
        onOpenRules: () -> Unit = {},
    ) {
        compose.setContent {
            RecognitionSettingsScreen(
                config = config, onChange = onChange,
                scanStyle = scanStyle, onScanStyleChange = onScanStyleChange,
                onOpenRules = onOpenRules, onNavigateUp = {},
            )
        }
    }

    @Test fun everyAxisIsOnScreen() {
        screen()
        compose.onNodeWithText("文字识别").assertIsDisplayed()
        compose.onNodeWithText("条码").assertIsDisplayed()
        compose.onNodeWithText("人脸").assertIsDisplayed()
    }

    @Test fun pickingAnAxisEmitsTheNewConfig() {
        var latest: RecognitionConfig? = null
        screen(onChange = { latest = it })
        compose.onNodeWithText("英文").performClick()
        assertThat(latest?.textEngine).isEqualTo(TextEngineOption.LATIN)
    }

    @Test fun pickingABarcodeStrategyEmitsTheNewConfig() {
        var latest: RecognitionConfig? = null
        screen(onChange = { latest = it })
        compose.onNodeWithText("宽松").performClick()
        assertThat(latest?.barcode).isEqualTo(BarcodeOption.LOOSE)
    }

    @Test fun pickingTheFrostedScanEffectEmitsIt() {
        var latest: ScanStyle? = null
        screen(onScanStyleChange = { latest = it })
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("磨砂"))
        compose.onNodeWithText("磨砂").performClick()
        assertThat(latest).isEqualTo(ScanStyle.FROST)
    }

    @Test fun rulesLiveOnTheirOwnPage() {
        var opened = 0
        screen(onOpenRules = { opened++ })
        compose.onNodeWithText("规则").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun resettingRulesKeepsTheAxes() {
        var latest: RecognitionConfig? = null
        val config = RecognitionConfig(textEngine = TextEngineOption.LATIN)
            .withRule("phone", RuleState.OFF, RuleCatalog.factoryState("phone"))
        compose.setContent {
            RuleSettingsScreen(
                config, onChange = { latest = it },
                exportReminder = true, onExportReminderChange = {}, onNavigateUp = {},
            )
        }
        compose.onNodeWithText("恢复默认").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(textEngine = TextEngineOption.LATIN))
    }

    @Test fun theExportReminderSwitchSitsOnTopOfTheRulesPage() {
        var latest: Boolean? = null
        compose.setContent {
            RuleSettingsScreen(
                RecognitionConfig(), onChange = {},
                exportReminder = true, onExportReminderChange = { latest = it }, onNavigateUp = {},
            )
        }
        compose.onNodeWithText("导出前提醒").performClick()
        assertThat(latest).isFalse()
    }

    // 规则行的三态覆盖逻辑由 RecognitionConfigTest 的单测守（withRule 的「只存差异」不变量），
    // 这里不重复——LazyColumn 里十三行同名 chip，按文字定位既脆又没多验到东西。

    @Test fun resetClearsEverything() {
        var latest: RecognitionConfig? = null
        var style: ScanStyle? = null
        screen(
            config = RecognitionConfig(textEngine = TextEngineOption.LATIN), onChange = { latest = it },
            scanStyle = ScanStyle.FROST, onScanStyleChange = { style = it },
        )
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("全部恢复默认"))
        compose.onNodeWithText("全部恢复默认").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig())
        assertThat(style).isEqualTo(ScanStyle.SWEEP)
    }
}
