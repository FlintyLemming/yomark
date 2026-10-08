package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
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

/** 开源版里去水印按钮打开的对话框：说明全功能免费，给一个打赏入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class FreeEditionDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `it says this is the full free edition`() {
        compose.setContent { MaterialTheme { FreeEditionDialog(onDismiss = {}, openUrl = {}) } }
        compose.onNodeWithText("全功能免费版").assertIsDisplayed()
        compose.onNodeWithText("不带水印", substring = true).assertIsDisplayed()
    }

    @Test fun `donating opens the donation page and closes the dialog`() {
        var opened: String? = null
        var dismissed = false
        compose.setContent {
            MaterialTheme { FreeEditionDialog(onDismiss = { dismissed = true }, openUrl = { opened = it }) }
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
                FreeEditionDialog(onDismiss = { dismissed = true }, openUrl = { error("no browser") })
            }
        }
        compose.onNodeWithText("打赏").performClick()
        compose.onNodeWithText(Edition.DONATE_URL, substring = true).assertIsDisplayed()
        assertThat(dismissed).isFalse()
    }

    @Test fun `close just closes`() {
        var dismissed = false
        compose.setContent { MaterialTheme { FreeEditionDialog(onDismiss = { dismissed = true }, openUrl = {}) } }
        compose.onNodeWithText("关闭").performClick()
        assertThat(dismissed).isTrue()
    }
}
