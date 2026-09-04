package com.youma.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.youma.app.ui.components.PaywallDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PaywallDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun says_it_is_one_time_and_that_no_privacy_feature_is_paywalled() {
        compose.setContent { PaywallDialog(onBuy = {}, onDismiss = {}) }
        compose.onNodeWithText("一次性买断，不是订阅。").assertIsDisplayed()
        // 这句是 spec §10 的立场，不能在改文案时被顺手删掉
        compose.onNodeWithText("免费版的隐私能力一项都不缺", substring = true).assertIsDisplayed()
    }

    @Test
    fun the_buy_and_dismiss_buttons_fire_their_callbacks() {
        var bought = false
        var dismissed = false
        compose.setContent { PaywallDialog(onBuy = { bought = true }, onDismiss = { dismissed = true }) }

        compose.onNodeWithText("购买").performClick()
        assertThat(bought).isTrue()

        compose.onNodeWithText("以后再说").performClick()
        assertThat(dismissed).isTrue()
    }
}
