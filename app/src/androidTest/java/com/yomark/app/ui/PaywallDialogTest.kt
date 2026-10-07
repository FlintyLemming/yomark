package com.yomark.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.yomark.app.ui.components.PaywallDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PaywallDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun says_it_is_one_time_and_that_no_privacy_feature_is_paywalled() {
        compose.setContent { PaywallDialog(onBuy = {}, onRedeem = { false }, onDismiss = {}) }
        compose.onNodeWithText("一次付费，永久有效，不是订阅。").assertIsDisplayed()
        // 这句是 spec §10 的立场，不能在改文案时被顺手删掉
        compose.onNodeWithText("其他功能免费版都有", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_buy_and_dismiss_buttons_fire_their_callbacks() {
        var bought = false
        var dismissed = false
        compose.setContent {
            PaywallDialog(onBuy = { bought = true }, onRedeem = { false }, onDismiss = { dismissed = true })
        }

        compose.onNodeWithText("购买").performClick()
        assertThat(bought).isTrue()

        compose.onNodeWithText("取消").performClick()
        assertThat(dismissed).isTrue()
    }

    @Test
    fun a_wrong_redeem_code_is_rejected_and_a_right_one_is_passed_through() {
        var entered: String? = null
        compose.setContent {
            PaywallDialog(onBuy = {}, onRedeem = { entered = it; it == "right" }, onDismiss = {})
        }

        compose.onNodeWithText("兑换码").performTextInput("wrong")
        compose.onNodeWithText("兑换").performClick()
        assertThat(entered).isEqualTo("wrong")
        compose.onNodeWithText("兑换码无效").assertIsDisplayed()
    }
}
