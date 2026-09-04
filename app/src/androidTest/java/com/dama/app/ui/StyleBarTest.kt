package com.dama.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.dama.app.core.model.MaskStyle
import com.dama.app.ui.components.StyleBar
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class StyleBarTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun all_six_styles_are_offered() {
        compose.setContent { StyleBar(MaskStyle.SOLID, {}, null) }
        listOf("实色块", "像素化", "模糊", "马克笔", "Emoji", "抹除").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    @Test
    fun selecting_a_style_fires_the_callback() {
        var picked: MaskStyle? = null
        compose.setContent { StyleBar(MaskStyle.SOLID, { picked = it }, null) }
        compose.onNodeWithText("像素化").performClick()
        assertThat(picked).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun blur_shows_the_not_secure_warning() {
        compose.setContent { StyleBar(MaskStyle.BLUR, {}, null) }
        compose.onNodeWithText("模糊是外观优先，非安全手段——可能被还原").assertIsDisplayed()
    }

    @Test
    fun marker_shows_the_annotation_only_warning() {
        compose.setContent { StyleBar(MaskStyle.MARKER, {}, null) }
        compose.onNodeWithText("马克笔仅标记、不遮蔽，底下的内容仍然可见").assertIsDisplayed()
    }

    @Test
    fun erase_degradation_note_is_shown_when_present() {
        compose.setContent { StyleBar(MaskStyle.ERASE, {}, "3 处背景过于复杂，抹除已自动降级为实色块") }
        compose.onNodeWithText("3 处背景过于复杂，抹除已自动降级为实色块").assertIsDisplayed()
    }
}
