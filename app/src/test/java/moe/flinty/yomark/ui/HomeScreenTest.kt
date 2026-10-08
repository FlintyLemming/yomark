package moe.flinty.yomark.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.ui.home.HomeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class HomeScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(
        onPickImage: () -> Unit = {},
        onSettings: () -> Unit = {},
        onRemoveWatermark: (() -> Unit)? = {},
    ) {
        compose.setContent {
            MaterialTheme { Surface { HomeScreen(onPickImage, onSettings, onRemoveWatermark = onRemoveWatermark) } }
        }
    }

    @Test fun `the big button picks an image`() {
        var picked = 0
        show(onPickImage = { picked++ })
        compose.onNodeWithText("选择图片").performClick()
        assertThat(picked).isEqualTo(1)
    }

    @Test fun `settings sit in the top bar`() {
        var opened = 0
        show(onSettings = { opened++ })
        compose.onNodeWithContentDescription("设置").performClick()
        assertThat(opened).isEqualTo(1)
    }

    @Test fun `the remove-watermark entry is gone once bought`() {
        show(onRemoveWatermark = null)
        compose.onNodeWithContentDescription("去除水印").assertDoesNotExist()
        compose.onNodeWithContentDescription("全功能免费版").assertDoesNotExist()
    }

    /** 开源版也留着这个按钮，只是它说的是免费版与打赏，不再是「去除水印」。 */
    @Test fun `the entry names the edition it opens`() {
        var opened = 0
        show(onRemoveWatermark = { opened++ })
        compose.onNodeWithContentDescription(if (Edition.isFree) "全功能免费版" else "去除水印").performClick()
        assertThat(opened).isEqualTo(1)
    }

    /** 首页是任务的根：返回由系统处理（回桌面），预见式返回才有回桌面的动画。 */
    @Test fun `the home page leaves system back to the system`() {
        show()
        assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }
}
