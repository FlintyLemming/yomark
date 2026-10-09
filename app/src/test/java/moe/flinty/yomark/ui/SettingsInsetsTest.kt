package moe.flinty.yomark.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.settings.FaceSettingsScreen
import moe.flinty.yomark.ui.settings.SettingsScreen
import moe.flinty.yomark.ui.settings.TextSettingsScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 设置页的系统栏避让（真机反馈：标题压在状态栏里，最后一行被手势导航条盖住）。
 *
 * 应用是边到边的，设置的每一页各是一个 Activity，让开系统栏全靠它们自己（共用 SettingsScaffold）。
 * Robolectric 不画系统栏也不发 insets，这里像系统那样从窗口发一组下去，再量位置。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class SettingsInsetsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 手势导航的手机：状态栏 40 dp，导航条 24 dp。 */
    private val statusBar = 40.dp
    private val navigationBar = 24.dp

    private fun Dp.px() = (value * compose.activity.resources.displayMetrics.density).toInt()

    private fun show(
        screen: @Composable () -> Unit = {
            SettingsScreen(RecognitionConfig(), exportReminder = true, onOpen = {}, onReset = {}, onNavigateUp = {})
        },
    ) {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        compose.setContent {
            MaterialTheme { Surface { screen() } }
        }
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusBar.px(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navigationBar.px()))
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
    }

    @Test fun `the title bar sits below the status bar`() {
        show()
        assertThat(compose.onNodeWithText("设置").getUnclippedBoundsInRoot().top).isAtLeast(statusBar)
    }

    /** 列表可以从导航条底下滚过去，但滚到底时最后一行得整个露在它上面。 */
    @Test fun `the last kind scrolls clear of the navigation bar`() {
        show { TextSettingsScreen(RecognitionConfig(), {}, {}, {}, onNavigateUp = {}) }
        val last = RuleCatalog.inSettingsOrder.last()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey(last.id)
        val row = compose.onNodeWithText(RuleCatalog.label(last)).getUnclippedBoundsInRoot()
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        assertThat(row.bottom).isAtMost(screen.bottom - navigationBar)
    }

    /** 二级页的标题同样让开状态栏。 */
    @Test fun `a sub-page title sits below the status bar`() {
        show { FaceSettingsScreen(RecognitionConfig(), {}, onNavigateUp = {}) }
        assertThat(compose.onNodeWithText("人脸").getUnclippedBoundsInRoot().top).isAtLeast(statusBar)
    }

    /**
     * 各页都不拦系统返回：返回交给 Activity 自己 finish。
     * 有一个常开的回调挂在 dispatcher 上，系统就不放预见式返回的跨 Activity 动画了。
     */
    @Test fun `the settings page leaves system back to the activity`() {
        show()
        assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test fun `the text page leaves system back to the activity`() {
        show { TextSettingsScreen(RecognitionConfig(), {}, {}, {}, onNavigateUp = {}) }
        assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }
}
