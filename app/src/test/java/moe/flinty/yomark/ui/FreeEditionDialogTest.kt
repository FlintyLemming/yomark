package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.ui.components.FreeEditionDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 开源版里去水印按钮打开的对话框：没解锁时给「去除水印」，两种状态都给打赏入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class FreeEditionDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `locked, it offers to remove the watermark and stays open`() {
        var unlocked = false
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                FreeEditionDialog(unlocked = false, onUnlock = { unlocked = true }, onDismiss = { dismissed = true }, openUrl = {})
            }
        }
        compose.onNodeWithText("全功能免费版").assertIsDisplayed()
        compose.onNodeWithText("去除水印").performClick()
        assertThat(unlocked).isTrue()
        assertThat(dismissed).isFalse()
    }

    @Test fun `unlocking switches the dialog to the done state`() {
        var unlocked by mutableStateOf(false)
        compose.setContent {
            MaterialTheme { FreeEditionDialog(unlocked = unlocked, onUnlock = { unlocked = true }, onDismiss = {}, openUrl = {}) }
        }
        compose.onNodeWithText("去除水印").performClick()
        compose.onNodeWithText("水印已去除", substring = true).assertIsDisplayed()
        compose.onNodeWithText("去除水印").assertDoesNotExist()
    }

    @Test fun `donating opens the donation page and closes the dialog`() {
        var opened: String? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                FreeEditionDialog(unlocked = false, onUnlock = {}, onDismiss = { dismissed = true }, openUrl = { opened = it })
            }
        }
        compose.onNodeWithText("打赏").performClick()
        assertThat(opened).isEqualTo("https://pay.mitsea.com")
        assertThat(Edition.DONATE_URL).isEqualTo("https://pay.mitsea.com")
        assertThat(dismissed).isTrue()
    }

    @Test fun `without a browser it spells out the address and stays open`() {
        var dismissed = false
        compose.setContent {
            MaterialTheme {
                FreeEditionDialog(unlocked = true, onUnlock = {}, onDismiss = { dismissed = true }, openUrl = { error("no browser") })
            }
        }
        compose.onNodeWithText("打赏").performClick()
        compose.onNodeWithText(Edition.DONATE_URL, substring = true).assertIsDisplayed()
        assertThat(dismissed).isFalse()
    }

    @Test fun `once unlocked, close just closes`() {
        var dismissed = false
        compose.setContent {
            MaterialTheme { FreeEditionDialog(unlocked = true, onUnlock = {}, onDismiss = { dismissed = true }, openUrl = {}) }
        }
        compose.onNodeWithText("关闭").performClick()
        assertThat(dismissed).isTrue()
    }
}
