package moe.flinty.yomark.ui.components

import android.os.Looper
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import moe.flinty.yomark.ui.AiReview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 手机上 AI 增强识别跑完的那一下，画布顶上说一声结果，几秒后收起；不是刚跑完的不说。 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class AiReviewResultPillTest {

    @get:Rule val compose = createComposeRule()

    private var review by mutableStateOf<AiReview>(AiReview.Ready)
    private var finished by mutableIntStateOf(0)

    private fun show() {
        compose.setContent { MaterialTheme { AiReviewResultPill(review, finished) } }
        compose.mainClock.autoAdvance = false
        after(16)
    }

    private fun after(ms: Long) = repeat((ms / 16).toInt().coerceAtLeast(1)) {
        compose.mainClock.advanceTimeByFrame()
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** 跑完一次：ViewModel 把结果和跑完的次数一起改（见 EditorViewModel.runAiReview）。 */
    private fun finish(result: AiReview) {
        review = result
        finished++
    }

    @Test fun `just finished, it says how many it found and then goes away`() {
        show()
        review = AiReview.Running
        after(100)
        finish(AiReview.Done(found = 2))
        after(500)
        compose.onNodeWithText("AI 新发现 2 处").assertIsDisplayed()
        after(4_000)
        compose.onNodeWithText("AI 新发现 2 处").assertDoesNotExist()
    }

    /** 一下子就失败了，界面没来得及看到「跑着」：照样要说，而且和「没有新发现」是两句话。 */
    @Test fun `a failure is told even when it comes at once, and apart from nothing new`() {
        show()
        finish(AiReview.Failed)
        after(500)
        compose.onNodeWithText("AI 增强识别失败").assertIsDisplayed()

        review = AiReview.Running
        after(500)
        compose.onNodeWithText("AI 增强识别失败").assertDoesNotExist()   // 又跑起来了，上一回的结果收起
        finish(AiReview.Done(found = 0))
        after(500)
        compose.onNodeWithText("AI 没有新发现").assertIsDisplayed()
    }

    /** 批量里换回一张早就跑过的图：结果还在圆钮上，这里不再说一遍。换新图时计数从 0 重来，也不说。 */
    @Test fun `a result that did not just come in is not announced`() {
        review = AiReview.Hidden
        finished = 3
        show()
        review = AiReview.Done(found = 2)
        after(500)
        compose.onNodeWithText("AI 新发现 2 处").assertDoesNotExist()
        review = AiReview.Hidden
        finished = 0
        after(500)
        compose.onNodeWithText("AI 新发现 2 处").assertDoesNotExist()
    }
}
