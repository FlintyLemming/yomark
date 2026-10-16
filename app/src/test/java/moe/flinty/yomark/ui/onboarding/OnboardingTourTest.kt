package moe.flinty.yomark.ui.onboarding

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.height
import android.graphics.PointF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.R
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.canvas.Viewport
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.YomarkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 首次引导：先演识别动效再落码，第二步点链接打上码，第三步的开关是真的；跳过、选图、去设置各走各的。
 * 识别中的光、框的一明一暗每帧都在走，自动推帧等不到空闲：要动效的用例手动推时间。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class OnboardingTourTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val sample: SourceImage by lazy { TourSample.load(compose.activity.resources) }
    private val boxes: TourSample by lazy { TourSample.of(compose.activity.resources) }

    private var skipped = 0
    private var picked = 0
    private var opened = 0
    private var chat by mutableStateOf(false)
    private var interactive by mutableStateOf(true)

    private var direction = LayoutDirection.Ltr

    private fun content(animate: Boolean): @androidx.compose.runtime.Composable () -> Unit = {
        CompositionLocalProvider(LocalLayoutDirection provides direction) { tour(animate) }
    }

    @androidx.compose.runtime.Composable
    private fun tour(animate: Boolean) {
        YomarkTheme(ThemeColor.BLUE) {
            Surface {
                OnboardingTour(
                    sample = sample,
                    boxes = boxes,
                    scanStyle = ScanStyle.FROST,
                    chatOn = chat,
                    onChatChange = { chat = it },
                    onSkip = { skipped++ },
                    onPickImage = { picked++ },
                    onOpenSettings = { opened++ },
                    interactive = interactive,
                    animate = animate,
                )
            }
        }
    }

    private fun show(animate: Boolean = true) {
        if (animate) compose.mainClock.autoAdvance = false
        compose.setContent(content(animate))
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** 推 [ms] 毫秒，一帧一帧推：画布上的动效、引导自己的等待都按帧走。 */
    private fun advance(ms: Long) {
        val end = compose.mainClock.currentTime + ms
        while (compose.mainClock.currentTime < end) {
            compose.mainClock.advanceTimeByFrame()
            idle()
        }
    }

    /** 等识别动效演完、码落稳。 */
    private fun settle() = advance(SCAN_MS + 1_200)

    private companion object {
        /** 翻一页的动画（450 ms）加点余量。 */
        const val PAGE_TURN_MS = 600L
    }

    private val stage get() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.onboarding_sample))

    private fun stageSays(text: String) = stage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text))

    /** 在样图上点图像坐标 [p]：和手指点一样，经过画布上那层只认点一下的。 */
    private fun tapImage(p: PointF) {
        val size = stage.fetchSemanticsNode().size
        val vp = Viewport.fit(TourSample.WIDTH, TourSample.HEIGHT, size.width, size.height)
        val at = vp.imageToScreen(p)
        stage.performTouchInput { click(Offset(at.x, at.y)) }
        idle()
    }

    @Test fun `the scan plays first and the masks land after it`() {
        show()
        advance(1_000)
        stageSays("识别中…")
        compose.onNodeWithText("自动打码").assertIsDisplayed()
        settle()
        stageSays("遮住了 4 处")
    }

    @Test fun `next during the scan lands the masks at once`() {
        show()
        advance(500)
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("点文字打码").assertIsDisplayed()
        stageSays("遮住了 4 处")
    }

    @Test fun `tapping the outlined link masks it`() {
        show()
        settle()
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("点一下框出的链接试试").assertIsDisplayed()
        tapImage(boxes.linkCenter)
        advance(400)
        stageSays("遮住了 5 处")
        compose.onNodeWithText("点一下框出的链接试试").assertDoesNotExist()
        // 再点一下取消
        tapImage(boxes.linkCenter)
        advance(400)
        stageSays("遮住了 4 处")
    }

    @Test fun `a screen reader can mask the link too`() {
        show(animate = false)
        compose.onNodeWithText("下一步").performClick()
        stage.performSemanticsAction(SemanticsActions.OnClick)
        stageSays("遮住了 5 处")
        stage.performSemanticsAction(SemanticsActions.OnClick)
        stageSays("遮住了 4 处")
    }

    @Test fun `back walks the steps and leaves the first one to the system`() {
        show(animate = false)
        val back = compose.activity.onBackPressedDispatcher
        assertThat(back.hasEnabledCallbacks()).isFalse()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithContentDescription("第 2 步，共 3 步：点文字打码").assertExists()
        assertThat(back.hasEnabledCallbacks()).isTrue()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第 1 步，共 3 步：自动打码").assertExists()
        compose.onNodeWithText("自动打码").assertIsDisplayed()
        compose.onNodeWithText("点文字打码").assertDoesNotExist()
        assertThat(back.hasEnabledCallbacks()).isFalse()
    }

    @Test fun `the labs switch is the real setting and masks the chat`() {
        show()
        settle()
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("设置 › 实验室").assertIsDisplayed()
        compose.onNodeWithText("遮住聊天截图里的头像、昵称和聊天标题，不保证识别完全准确").assertIsDisplayed()
        compose.onNodeWithText("识别聊天记录").performClick()
        assertThat(chat).isTrue()
        advance(boxes.chat.size * CHAT_STAGGER_MS + 600)
        stageSays("遮住了 9 处")
        compose.onNodeWithText("识别聊天记录").performClick()
        assertThat(chat).isFalse()
        advance(400)
        stageSays("遮住了 4 处")
    }

    @Test fun `skip works on every step and the last step offers the picker and settings`() {
        show(animate = false)
        compose.onNodeWithText("跳过").performClick()
        compose.onNodeWithText("选择图片").assertDoesNotExist()
        compose.onNodeWithText("打开设置").assertDoesNotExist()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("跳过").performClick()
        compose.onNodeWithText("下一步").performClick()
        compose.onNodeWithText("跳过").performClick()
        assertThat(skipped).isEqualTo(3)
        compose.onNodeWithText("选择图片").performClick()
        compose.onNodeWithText("打开设置").performClick()
        assertThat(picked).isEqualTo(1)
        assertThat(opened).isEqualTo(1)
    }

    @Test fun `with animations off there is no scan`() {
        show(animate = false)
        stageSays("遮住了 4 处")
    }

    /** 划开第一页时识别动效还在演：码当场落下，下一页的图上不再从头演一遍。 */
    @Test fun `swiping away during the scan lands the masks`() {
        show()
        advance(500)
        stage.performTouchInput { swipeLeft() }
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("点文字打码").assertIsDisplayed()
        stageSays("遮住了 4 处")
    }

    /** 快点两下「下一步」：第二下落在正滑进来的最后一页上，不算「选择图片」，引导不能就这么没了。 */
    @Test fun `a quick double tap on next does not leave the tour`() {
        show()
        settle()
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("下一步").performClick()
        advance(100)
        compose.onNode(hasClickAction() and hasText("选择图片")).performClick()
        advance(PAGE_TURN_MS)
        assertThat(picked).isEqualTo(0)
        compose.onNodeWithText("设置里还有更多").assertIsDisplayed()
        compose.onNodeWithText("选择图片").performClick()
        assertThat(picked).isEqualTo(1)
    }

    @Test fun `chat masks are there from the start when the setting is already on`() {
        chat = true
        show(animate = false)
        stageSays("遮住了 9 处")
    }

    @Test fun `leaving hides it from the screen reader and from back`() {
        show(animate = false)
        compose.onNodeWithText("下一步").performClick()
        interactive = false
        compose.waitForIdle()
        compose.onNodeWithText("跳过").assertDoesNotExist()
        assertThat(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks()).isFalse()
    }

    @Test fun `a recreated tour keeps its step and taps without replaying the scan`() {
        val restore = StateRestorationTester(compose)
        compose.mainClock.autoAdvance = false
        restore.setContent(content(animate = true))
        idle()
        settle()
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS)
        tapImage(boxes.linkCenter)
        advance(400)
        restore.emulateSavedInstanceStateRestore()
        idle()
        advance(100)
        compose.onNodeWithText("点文字打码").assertIsDisplayed()
        compose.onNodeWithText("识别中…").assertDoesNotExist()
        stageSays("遮住了 5 处")
    }

    /** 横着放：图在左，说明在右，按钮都在屏幕里。 */
    @Config(qualifiers = "zh-rCN-w915dp-h412dp-xxhdpi")
    @Test fun `landscape puts the sample beside the text`() {
        show(animate = false)
        val image = stage.getBoundsInRoot()
        val title = compose.onNodeWithText("自动打码").getBoundsInRoot()
        assertThat(image.right).isAtMost(title.left)
        compose.onNodeWithText("跳过").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsDisplayed()
    }

    /** 竖着放：图在上，说明在下；前两页的图一样大、一样高（平移时不上下跳），第三页说明高，图小一些。 */
    @Test fun `portrait stacks the sample above the text and keeps it level between the first two pages`() {
        show(animate = false)
        val image = stage.getBoundsInRoot()
        val title = compose.onNodeWithText("自动打码").getBoundsInRoot()
        assertThat(image.bottom).isAtMost(title.top)
        compose.onNodeWithText("下一步").performClick()
        val second = stage.getBoundsInRoot()
        assertThat(second.top.value).isWithin(0.5f).of(image.top.value)
        assertThat(second.height.value).isWithin(0.5f).of(image.height.value)
        compose.onNodeWithText("下一步").performClick()
        assertThat(stage.getBoundsInRoot().height).isLessThan(image.height)
        compose.onNodeWithText("选择图片").assertIsDisplayed()
    }

    /** 翻页途中进来的那一页上，图、标题、正文各自的左右边（dp，不按屏幕裁掉）。[incoming] 从两块图里挑进来的那一块。 */
    private fun turning(incoming: (List<Float>) -> Int): Triple<ClosedFloatingPointRange<Float>, DpRect, DpRect> {
        val density = compose.activity.resources.displayMetrics.density
        val stages = compose.onAllNodesWithContentDescription("示例聊天截图").fetchSemanticsNodes()
        assertThat(stages).hasSize(2)
        val node = stages[incoming(stages.map { it.positionInRoot.x })]
        val image = node.positionInRoot.x / density..(node.positionInRoot.x + node.size.width) / density
        val title = compose.onNodeWithText("点文字打码").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithText("漏掉的字点一下就遮住，再点一下取消").getUnclippedBoundsInRoot()
        return Triple(image, title, body)
    }

    /** 翻页途中：两页的图都在屏幕上，进来的那一页从上到下一层比一层落在后面（视差），停下时对齐。 */
    @Test fun `turning a page slides it with the lower layers further out`() {
        show()
        settle()
        val width = compose.onRoot().getBoundsInRoot().width.value
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS / 3)
        // 进来的这一页在右边。停稳时图、标题、正文的左边都缩进 24 dp，对齐
        val (image, title, body) = turning { xs -> xs.indexOf(xs.max()) }
        assertThat(image.start).isLessThan(width)
        assertThat(title.left.value).isGreaterThan(image.start)
        assertThat(body.left.value).isGreaterThan(title.left.value)
        settle()
        compose.onAllNodesWithContentDescription("示例聊天截图").assertCountEquals(1)
        assertThat(compose.onNodeWithText("漏掉的字点一下就遮住，再点一下取消").getUnclippedBoundsInRoot().left.value)
            .isWithin(0.5f).of(compose.onNodeWithText("点文字打码").getUnclippedBoundsInRoot().left.value)
    }

    /** 从右往左排的语言里下一页从左边进来：下面几层落在图的更左边（更后面），不往中间挤。 */
    @Test fun `in right to left layouts the lower layers trail on the left`() {
        direction = LayoutDirection.Rtl
        show()
        settle()
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS / 3)
        val (image, title, body) = turning { xs -> xs.indexOf(xs.min()) }
        assertThat(image.endInclusive).isGreaterThan(0f)
        assertThat(title.right.value).isLessThan(image.endInclusive)
        assertThat(body.right.value).isLessThan(title.right.value)
    }

    /** 英文环境下换成英文的样图和它的框：码的处数是它的，框出的链接点得中。 */
    @Config(qualifiers = "en-rUS-w412dp-h915dp-xxhdpi")
    @Test fun `in english the tour runs on the english sample`() {
        assertThat(boxes).isSameInstanceAs(TourSample.EN)
        val auto = TourSample.EN.auto.size
        show()
        settle()
        stageSays("$auto masked")
        compose.onNodeWithText("Next").performClick()
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("Try tapping the outlined link").assertIsDisplayed()
        tapImage(boxes.linkCenter)
        advance(400)
        stageSays("${auto + 1} masked")
        compose.onNodeWithText("Try tapping the outlined link").assertDoesNotExist()
    }

    /** 小屏、字放大：按钮还在屏幕里。 */
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi", fontScale = 1.3f)
    @Test fun `a small phone with large text still reaches every button`() {
        show(animate = false)
        compose.onNodeWithText("Masked automatically").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Choose image").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
    }
}
