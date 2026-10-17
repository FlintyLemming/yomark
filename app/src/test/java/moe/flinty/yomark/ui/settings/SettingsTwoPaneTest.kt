package moe.flinty.yomark.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.rules.RuleCatalog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 宽屏上设置分左右两栏（见 SettingsHost）：左栏一级页，右栏点开的那一页，右栏里点进去的页叠在右栏里。
 * 窗口不够宽时照旧一页一页走；分栏时点开过的页，窗口变窄后原地占满整屏。
 *
 * 右栏里的页这里用一张假的（标题写着它是哪一页，一行「点进去」打开文字里的一类），只看怎么摆、怎么走；
 * 各页自己的内容见 SettingsScreenTest。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w1280dp-h800dp-xhdpi")
class SettingsTwoPaneTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val rule = RuleCatalog.inSettingsOrder.first().id
    private val ruleKey = "TEXT_RULE/$rule"

    private var opened by mutableStateOf<List<SettingsDestination>?>(null)
    private val launched = mutableListOf<SettingsPage>()

    private fun host(
        twoPane: Boolean = true,
        developer: Boolean = false,
        initial: List<SettingsDestination>? = null,
        page: @Composable (SettingsDestination, (SettingsDestination) -> Unit, (() -> Unit)?) -> Unit = { d, onOpen, up ->
            FakePage(d, onOpen, up)
        },
    ) {
        opened = initial
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsHost(
                        twoPane = twoPane,
                        opened = opened,
                        onOpenedChange = { opened = it },
                        onLaunch = { launched += it },
                        directory = { selected, onOpen ->
                            SettingsScreen(RecognitionConfig(), onOpen, onNavigateUp = {}, developer = developer, selected = selected)
                        },
                        page = page,
                    )
                }
            }
        }
    }

    /** 和真的页一样：DataStore 读到之前那一帧什么都不画。 */
    @Composable
    private fun LoadingPage(destination: SettingsDestination, onOpen: (SettingsDestination) -> Unit, onNavigateUp: (() -> Unit)?) {
        var ready by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            ready = true
        }
        if (ready) FakePage(destination, onOpen, onNavigateUp)
    }

    @Composable
    private fun FakePage(destination: SettingsDestination, onOpen: (SettingsDestination) -> Unit, onNavigateUp: (() -> Unit)?) {
        SettingsScaffold(title = "页面 ${destination.key}", onNavigateUp = onNavigateUp) {
            item {
                PageTopSpacer()
                SettingsGroup {
                    SettingsRow(
                        title = "点进去",
                        interaction = Modifier.clickable { onOpen(SettingsDestination(SettingsPage.TEXT_RULE, rule)) },
                    )
                }
            }
        }
    }

    private fun page(key: String): SemanticsNodeInteraction = compose.onNodeWithText("页面 $key")

    private fun backButtons() = compose.onAllNodesWithContentDescription("返回")

    /** 右栏标题栏上的返回：比左栏那个靠右。 */
    private fun rightPaneBack(): SemanticsNodeInteraction {
        val right = backButtons().fetchSemanticsNodes().maxBy { it.boundsInRoot.left }
        return compose.onNode(SemanticsMatcher("right pane back") { it.id == right.id })
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun backIsLeftToTheActivity(): Boolean {
        compose.waitForIdle()
        return !compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()
    }

    // ---------- 分栏 ----------

    /** 还没点过时右栏放「文字」，左栏「文字」那一行选中；右栏最底下那一页没有返回，只有左栏的退出设置。 */
    @Test fun theDirectoryAndTheFirstTopicSitSideBySide() {
        host()
        compose.onNodeWithText("设置").assertIsDisplayed()
        page("TEXT").assertIsDisplayed()
        compose.onNodeWithText("文字").assertIsSelected()
        compose.onNodeWithText("人脸").assertIsNotSelected()
        backButtons().assertCountEquals(1)
        val list = compose.onNodeWithText("文字").getUnclippedBoundsInRoot()
        val detail = compose.onNodeWithText("点进去").getUnclippedBoundsInRoot()
        assertThat(detail.left).isGreaterThan(list.right)
        assertThat(launched).isEmpty()
    }

    @Test fun pickingATopicShowsItOnTheRight() {
        host()
        compose.onNodeWithText("人脸").performClick()
        page("FACE").assertIsDisplayed()
        page("TEXT").assertDoesNotExist()
        compose.onNodeWithText("人脸").assertIsSelected()
        compose.onNodeWithText("文字").assertIsNotSelected()
        assertThat(opened).containsExactly(SettingsDestination(SettingsPage.FACE))
        assertThat(launched).isEmpty()
    }

    /** 右栏里点进去的页叠在右栏里，标题栏多一个返回，点了退回上一页。 */
    @Test fun goingDeeperStacksInTheRightPane() {
        host()
        compose.onNodeWithText("点进去").performClick()
        page(ruleKey).assertIsDisplayed()
        compose.onNodeWithText("文字").assertIsSelected()
        backButtons().assertCountEquals(2)
        rightPaneBack().performClick()
        page("TEXT").assertIsDisplayed()
        page(ruleKey).assertDoesNotExist()
        assertThat(opened).containsExactly(SettingsDestination(SettingsPage.TEXT))
    }

    /** 系统返回先退右栏里叠着的页；退到最底下那一页就不拦了，交给 Activity 退出设置（跨 Activity 的预见式返回）。 */
    @Test fun systemBackPopsTheRightPaneThenLeavesTheSettings() {
        host()
        assertThat(backIsLeftToTheActivity()).isTrue()
        compose.onNodeWithText("点进去").performClick()
        assertThat(backIsLeftToTheActivity()).isFalse()
        back()
        page("TEXT").assertIsDisplayed()
        assertThat(opened).containsExactly(SettingsDestination(SettingsPage.TEXT))
        assertThat(backIsLeftToTheActivity()).isTrue()
    }

    /**
     * 真的页在 DataStore 读到之前第一帧是空的。换页时旧的一页只往旁边滑、淡掉，不上下走：
     * 默认的 SizeTransform 会把容器往空页的 0×0 缩，缩小的那一块被摆在正中，旧的一页跟着往右下角漂（真机上看得出来）。
     */
    @Test fun aPageStillLoadingDoesNotDragTheOldOneDown() {
        host(page = { d, onOpen, up -> LoadingPage(d, onOpen, up) })
        val top = { compose.onAllNodesWithText("页面 TEXT").fetchSemanticsNodes().map { it.boundsInRoot.top } }
        val before = top()
        assertThat(before).isNotEmpty()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("点进去").performClick()
        repeat(20) {
            compose.mainClock.advanceTimeByFrame()
            top().forEach { assertThat(it).isEqualTo(before.first()) }
        }
        compose.mainClock.autoAdvance = true
        page(ruleKey).assertIsDisplayed()
    }

    /** 再点一下正开着的那一项，右栏退回它最上面那一页。 */
    @Test fun pickingTheOpenTopicAgainGoesBackToItsTop() {
        host(initial = listOf(SettingsDestination(SettingsPage.TEXT), SettingsDestination(SettingsPage.TEXT_RULE, rule)))
        page(ruleKey).assertIsDisplayed()
        compose.onNodeWithText("文字").performClick()
        page("TEXT").assertIsDisplayed()
        assertThat(opened).containsExactly(SettingsDestination(SettingsPage.TEXT))
    }

    /** 开发者页开着时在里面关掉调试信息，这一项先留着；换到别的项才不见。 */
    @Test fun theDeveloperTopicStaysWhileItsPageIsOpen() {
        host(developer = false, initial = listOf(SettingsDestination(SettingsPage.DEVELOPER)))
        compose.onNodeWithText("开发者").assertIsSelected()
        // 800 dp 高的窗口里「关于」在左栏下面，先滚出来
        compose.onNodeWithText("关于").performScrollTo().performClick()
        page("ABOUT").assertIsDisplayed()
        compose.onNodeWithText("开发者").assertDoesNotExist()
    }

    /** 帮助不是一页：点了放引导，右栏不动。 */
    @Test fun helpIsNotATopicOfTheRightPane() {
        var replays = 0
        opened = null
        compose.setContent {
            MaterialTheme {
                Surface {
                    SettingsHost(
                        twoPane = true,
                        opened = opened,
                        onOpenedChange = { opened = it },
                        onLaunch = { launched += it },
                        directory = { selected, onOpen ->
                            SettingsScreen(RecognitionConfig(), onOpen, onNavigateUp = {}, onReplayTour = { replays++ }, selected = selected)
                        },
                        page = { destination, onOpen, onNavigateUp -> FakePage(destination, onOpen, onNavigateUp) },
                    )
                }
            }
        }
        compose.onNodeWithText("帮助").performScrollTo().assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
            .performClick()
        assertThat(replays).isEqualTo(1)
        page("TEXT").assertIsDisplayed()
        assertThat(opened).isNull()
    }

    // ---------- 不分栏 ----------

    /** 手机上：只有一级页，点一项开一个新的 SettingsPageActivity，不在这里打开。 */
    @Test fun withoutTwoPanesATopicOpensItsOwnActivity() {
        host(twoPane = false)
        compose.onNodeWithText("人脸").performClick()
        assertThat(launched).containsExactly(SettingsPage.FACE)
        assertThat(opened).isNull()
        page("FACE").assertDoesNotExist()
        // 只是能点的一行，没有选中不选中
        compose.onNodeWithText("文字").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected))
        assertThat(backIsLeftToTheActivity()).isTrue()
    }

    /** 分栏时点开的页，窗口变窄后原地占满整屏：最底下那一页也有返回，一页一页退回一级页。 */
    @Test fun pagesOpenedSideBySideStayOpenWhenTheWindowNarrows() {
        host(twoPane = false, initial = listOf(SettingsDestination(SettingsPage.TEXT), SettingsDestination(SettingsPage.TEXT_RULE, rule)))
        page(ruleKey).assertIsDisplayed()
        compose.onNodeWithText("设置").assertDoesNotExist()
        back()
        page("TEXT").assertIsDisplayed()
        backButtons().assertCountEquals(1)
        backButtons()[0].performClick()
        compose.onNodeWithText("设置").assertIsDisplayed()
        page("TEXT").assertDoesNotExist()
        assertThat(opened).isNull()
        assertThat(backIsLeftToTheActivity()).isTrue()
    }

    // ---------- 系统栏避让 ----------

    private fun Dp.px() = (value * compose.activity.resources.displayMetrics.density).toInt()

    /**
     * 左边有挖孔、右边是三键导航时，左栏让开左边的、右栏让开右边的，各不管对面那一侧。
     * 左栏 360 dp（1280 的 40% 超过上限），卡片两侧各留 16 dp。
     */
    @Test fun eachPaneKeepsClearOfItsOwnSide() {
        compose.runOnUiThread { compose.activity.enableEdgeToEdge() }
        host()
        compose.runOnUiThread {
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 24.dp.px(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(48.dp.px(), 0, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 32.dp.px(), 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(compose.activity.window.decorView, insets)
        }
        compose.waitForIdle()
        val list = compose.onNodeWithText("文字").getUnclippedBoundsInRoot()
        val detail = compose.onNodeWithText("点进去").getUnclippedBoundsInRoot()
        assertThat(list.left.value).isWithin(1f).of(48f + 16f)
        assertThat(list.right.value).isWithin(1f).of(360f - 16f)
        assertThat(detail.left.value).isWithin(1f).of(360f + 16f)
        assertThat(detail.right.value).isWithin(1f).of(1280f - 32f - 16f)
    }

    // ---------- 窗口变宽时的页 ----------

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** 折叠屏在文字里的一类上展开：这一页连同「文字」交回一级页的右栏，自己关掉。 */
    @Test fun aPageActivityHandsItselfToTheTwoPanesOnAWideWindow() {
        val destination = SettingsDestination(SettingsPage.TEXT_RULE, rule)
        val activity = Robolectric.buildActivity(
            SettingsPageActivity::class.java,
            SettingsPageActivity.intent(context, destination),
        ).create().get()
        assertThat(activity.isFinishing).isTrue()
        val started = shadowOf(activity).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(SettingsActivity::class.java.name)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
        assertThat(started.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
        val handed = started.extras?.keySet()?.single()?.let {
            IntentCompat.getParcelableArrayListExtra(started, it, SettingsDestination::class.java)
        }
        assertThat(handed).containsExactly(SettingsDestination(SettingsPage.TEXT), destination).inOrder()
    }

    @Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
    @Test fun aPageActivityStaysOnAPhone() {
        val activity = Robolectric.buildActivity(
            SettingsPageActivity::class.java,
            SettingsPageActivity.intent(context, SettingsDestination(SettingsPage.FACE)),
        ).create().get()
        assertThat(activity.isFinishing).isFalse()
        assertThat(shadowOf(activity).nextStartedActivity).isNull()
    }
}
