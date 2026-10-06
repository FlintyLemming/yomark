package com.youma.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import com.youma.app.engine.RecognitionConfig
import com.youma.app.ui.canvas.ScanStyle
import com.youma.app.ui.settings.RecognitionSettingsScreen
import com.youma.app.ui.theme.ThemeColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 设置页最底下的主题色：跟随系统是一个选项，后面是预设色块。 */
@RunWith(RobolectricTestRunner::class)
class SettingsThemeColorTest {

    @get:Rule val compose = createComposeRule()

    private fun show(themeColor: ThemeColor, onThemeColorChange: (ThemeColor) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    RecognitionSettingsScreen(
                        config = RecognitionConfig(), onChange = {},
                        scanStyle = ScanStyle.SWEEP, onScanStyleChange = {},
                        themeColor = themeColor, onThemeColorChange = onThemeColorChange,
                        onOpenRules = {}, onNavigateUp = {},
                    )
                }
            }
        }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("粉色"))
    }

    @Test fun `picking a preset emits it`() {
        var latest: ThemeColor? = null
        show(ThemeColor.SYSTEM) { latest = it }
        compose.onNodeWithContentDescription("绿色").performClick()
        assertThat(latest).isEqualTo(ThemeColor.GREEN)
    }

    @Test fun `going back to following the system emits it`() {
        var latest: ThemeColor? = null
        show(ThemeColor.RED) { latest = it }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("跟随系统"))
        compose.onNodeWithText("跟随系统").performClick()
        assertThat(latest).isEqualTo(ThemeColor.SYSTEM)
    }

    @Test fun `the current preset is marked as selected`() {
        show(ThemeColor.BLUE)
        compose.onNodeWithContentDescription("蓝色").assertIsSelected()
    }
}
