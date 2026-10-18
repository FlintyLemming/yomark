package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsNotSelected
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
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.settings.AppearanceSettingsScreen
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 设置「外观」页：深色模式三选一；主题色里跟随系统是一个单选项，下面是预设色块。 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class SettingsThemeColorTest {

    @get:Rule val compose = createComposeRule()

    private fun show(
        themeColor: ThemeColor = ThemeColor.SYSTEM,
        onThemeColorChange: (ThemeColor) -> Unit = {},
        themeMode: ThemeMode = ThemeMode.SYSTEM,
        onThemeModeChange: (ThemeMode) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    AppearanceSettingsScreen(
                        themeMode = themeMode, onThemeModeChange = onThemeModeChange,
                        themeColor = themeColor, onThemeColorChange = onThemeColorChange,
                        scanStyle = ScanStyle.FROST, onScanStyleChange = {},
                        onNavigateUp = {},
                    )
                }
            }
        }
    }

    private fun showColors(themeColor: ThemeColor, onThemeColorChange: (ThemeColor) -> Unit = {}) {
        show(themeColor, onThemeColorChange)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("粉色"))
    }

    /** 主题色里的「跟随系统」：深色模式那组也有一个，这一个下面写着取色。 */
    private val followSystemColor = hasText("跟随系统") and hasText("随壁纸取色")

    @Test fun `picking dark mode emits it`() {
        var latest: ThemeMode? = null
        show(onThemeModeChange = { latest = it })
        compose.onNodeWithText("深色").performClick()
        assertThat(latest).isEqualTo(ThemeMode.DARK)
    }

    @Test fun `going back to the system's dark mode emits it`() {
        var latest: ThemeMode? = null
        show(themeMode = ThemeMode.LIGHT, onThemeModeChange = { latest = it })
        compose.onNode(hasText("跟随系统") and !hasText("随壁纸取色")).performClick()
        assertThat(latest).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `the current dark mode is marked as selected`() {
        show(themeMode = ThemeMode.LIGHT)
        compose.onNodeWithText("浅色").assertIsSelected()
        compose.onNodeWithText("深色").assertIsNotSelected()
    }

    @Test fun `picking a preset emits it`() {
        var latest: ThemeColor? = null
        showColors(ThemeColor.SYSTEM) { latest = it }
        compose.onNodeWithContentDescription("绿色").performClick()
        assertThat(latest).isEqualTo(ThemeColor.GREEN)
    }

    @Test fun `going back to following the system emits it`() {
        var latest: ThemeColor? = null
        showColors(ThemeColor.RED) { latest = it }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(followSystemColor)
        compose.onNode(followSystemColor).performClick()
        assertThat(latest).isEqualTo(ThemeColor.SYSTEM)
    }

    @Test fun `the current preset is marked as selected`() {
        showColors(ThemeColor.BLUE)
        compose.onNodeWithContentDescription("蓝色").assertIsSelected()
    }
}
