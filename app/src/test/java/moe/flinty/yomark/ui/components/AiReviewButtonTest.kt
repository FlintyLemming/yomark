package moe.flinty.yomark.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.ui.AiReview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class AiReviewButtonTest {

    @get:Rule val compose = createComposeRule()

    private var clicks = 0

    private fun show(state: AiReview) {
        compose.setContent { MaterialTheme { AiReviewButton(state) { clicks++ } } }
    }

    @Test
    fun `ready says what it does and runs on click`() {
        show(AiReview.Ready)
        compose.onNodeWithText("AI 增强识别").assertIsEnabled().performClick()
        assertThat(clicks).isEqualTo(1)
    }

    @Test
    fun `running cannot be clicked again`() {
        show(AiReview.Running)
        compose.onNodeWithText("增强识别中…").assertIsNotEnabled()
    }

    @Test
    fun `the result sits on the button in a few words`() {
        show(AiReview.Done(found = 3))
        compose.onNodeWithText("新发现 3 处").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `nothing new is said plainly`() {
        show(AiReview.Done(found = 0))
        compose.onNodeWithText("没有新发现").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun `a failure is not passed off as nothing new, and the button retries`() {
        show(AiReview.Failed)
        compose.onNodeWithText("识别失败，点击重试").assertHasClickAction().assertIsEnabled().performClick()
        assertThat(clicks).isEqualTo(1)
    }
}
