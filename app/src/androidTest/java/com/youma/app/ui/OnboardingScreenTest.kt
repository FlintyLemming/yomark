package com.youma.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.youma.app.ui.onboarding.OnboardingScreen
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class OnboardingScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun states_the_three_core_promises() {
        compose.setContent { OnboardingScreen(onStart = {}) }
        compose.onNodeWithText("不申请任何权限").assertIsDisplayed()
        compose.onNodeWithText("识别与编辑全程不联网").assertIsDisplayed()
        compose.onNodeWithText("导出自动清除元数据").assertIsDisplayed()
    }

    @Test
    fun the_start_button_fires_its_callback() {
        var started = false
        compose.setContent { OnboardingScreen(onStart = { started = true }) }
        compose.onNodeWithText("选择图片").performClick()
        assertThat(started).isTrue()
    }
}
