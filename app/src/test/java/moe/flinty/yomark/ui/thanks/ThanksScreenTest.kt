package moe.flinty.yomark.ui.thanks

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class ThanksScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(onDone: () -> Unit = {}) {
        compose.setContent { MaterialTheme { Surface { ThanksScreen(onDone) } } }
    }

    @Test fun `it thanks the buyer and says what they bought`() {
        show()
        compose.onNodeWithText("感谢购买").assertIsDisplayed()
        // 买到的只是去掉品牌水印（spec §10），这一页不能把它说成别的
        compose.onNodeWithText("不再带「有码 Yomark」水印", substring = true).assertIsDisplayed()
    }

    @Test fun `ok closes the page`() {
        var done = 0
        show(onDone = { done++ })
        compose.onNodeWithText("好的").performClick()
        assertThat(done).isEqualTo(1)
    }

    /** 和其他页一样不拦系统返回：返回交给 Activity 自己 finish，预见式返回才有跨页的动画。 */
    @Test fun `system back is left to the system`() {
        show()
        assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test fun `the party popper is decoration that the screen reader skips`() {
        show()
        compose.onNodeWithText("🎉").assertDoesNotExist()
    }

    /** 横着拿的手机竖向放不下：滚得到按钮，不会被裁掉。 */
    @Test
    @Config(qualifiers = "w915dp-h412dp-xxhdpi")
    fun `the button is still reachable on a landscape phone`() {
        var done = 0
        show(onDone = { done++ })
        compose.onNodeWithText("好的").performScrollTo().performClick()
        assertThat(done).isEqualTo(1)
    }
}
