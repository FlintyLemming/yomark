package com.yomark.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yomark.app.ui.onboarding.OnboardingScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun states_the_three_core_promises() {
        compose.setContent { OnboardingScreen(onStart = {}) }
        compose.onNodeWithText("不需要任何权限").assertIsDisplayed()
        compose.onNodeWithText("不联网，图片不离开手机").assertIsDisplayed()
        compose.onNodeWithText("导出时去掉位置、机型等隐藏信息").assertIsDisplayed()
    }

    @Test
    fun the_start_button_fires_its_callback() {
        var started = false
        compose.setContent { OnboardingScreen(onStart = { started = true }) }
        compose.onNodeWithText("选择图片").performClick()
        assertThat(started).isTrue()
    }
}
