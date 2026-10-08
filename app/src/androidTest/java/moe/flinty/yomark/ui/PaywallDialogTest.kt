package moe.flinty.yomark.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import moe.flinty.yomark.ui.components.PaywallDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PaywallDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun says_it_is_one_time_and_that_no_privacy_feature_is_paywalled() {
        compose.setContent { PaywallDialog(onBuy = {}, onDismiss = {}) }
        compose.onNodeWithText("一次付费，永久有效，不是订阅。").assertIsDisplayed()
        // 这句是 spec §10 的立场，不能在改文案时被顺手删掉
        compose.onNodeWithText("其他功能免费版都有", substring = true).assertIsDisplayed()
    }

    @Test
    fun buying_starts_the_purchase_and_closes_the_dialog() {
        var bought = false
        var dismissed = false
        compose.setContent { PaywallDialog(onBuy = { bought = true }, onDismiss = { dismissed = true }) }

        compose.onNodeWithText("购买").performClick()
        assertThat(bought).isTrue()
        // 编辑器里曾经漏了关框：买完从 Play 回来，购买框还开着
        assertThat(dismissed).isTrue()
    }

    @Test
    fun cancel_just_closes() {
        var bought = false
        var dismissed = false
        compose.setContent { PaywallDialog(onBuy = { bought = true }, onDismiss = { dismissed = true }) }

        compose.onNodeWithText("取消").performClick()
        assertThat(dismissed).isTrue()
        assertThat(bought).isFalse()
    }
}
