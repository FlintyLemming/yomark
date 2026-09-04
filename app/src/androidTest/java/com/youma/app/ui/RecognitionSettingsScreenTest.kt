package com.youma.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.TextEngineOption
import com.youma.app.ui.settings.RecognitionSettingsScreen
import org.junit.Rule
import org.junit.Test

class RecognitionSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun screen(
        config: RecognitionConfig = RecognitionConfig(),
        onChange: (RecognitionConfig) -> Unit = {},
    ) {
        compose.setContent {
            RecognitionSettingsScreen(config = config, onChange = onChange, onBack = {})
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
        compose.onNodeWithText("拉丁").performClick()
        assertThat(latest?.textEngine).isEqualTo(TextEngineOption.LATIN)
    }

    @Test fun pickingABarcodeStrategyEmitsTheNewConfig() {
        var latest: RecognitionConfig? = null
        screen(onChange = { latest = it })
        compose.onNodeWithText("严格").performClick()
        assertThat(latest?.barcode).isEqualTo(BarcodeOption.STRICT)
    }

    // 规则行的三态覆盖逻辑由 RecognitionConfigTest 的单测守（withRule 的「只存差异」不变量），
    // 这里不重复——LazyColumn 里十三行同名 chip，按文字定位既脆又没多验到东西。

    @Test fun resetClearsEverything() {
        var latest: RecognitionConfig? = null
        screen(config = RecognitionConfig(textEngine = TextEngineOption.LATIN)) { latest = it }
        compose.onNodeWithText("全部恢复默认").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig())
    }
}
