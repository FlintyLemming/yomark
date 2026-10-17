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
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.isSelectable
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
import moe.flinty.yomark.engine.TextEngineOption
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
 * 首次引导：欢迎页之后先演识别动效再落码，点文字那一页点链接打上码，识别引擎按测速选好，最后一页的开关是真的；
 * 跳过、选图、去设置各走各的。识别中的光、框的一明一暗每帧都在走，自动推帧等不到空闲：要动效的用例手动推时间。
 *
 * [advice] 为 null 时是设置里「帮助」重放的那种，没有识别引擎那一页；首次打开的给它一个测速结论。
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
    private var advice by mutableStateOf<EngineAdvice?>(null)
    private var engine by mutableStateOf(TextEngineOption.PADDLE)
    private val chosen = mutableListOf<TextEngineOption>()

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
                    engineAdvice = advice,
                    engine = engine,
                    onEngineChange = {
                        engine = it
                        chosen += it
                    },
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

    /** 点「下一步」翻过去：要动效的推着时间等它翻完，关了动画的一下就到。 */
    private fun next(animate: Boolean = true) {
        compose.onNodeWithText("下一步").performClick()
        if (animate) advance(PAGE_TURN_MS) else compose.waitForIdle()
    }

    /** 从欢迎页翻到「识别引擎」那一页（关了动画）。 */
    private fun toEnginePage() {
        repeat(3) { next(animate = false) }
        compose.onNodeWithContentDescription("第 4 步，共 5 步：识别引擎").assertExists()
    }

    private companion object {
        /** 翻一页的动画（450 ms）加点余量。 */
        const val PAGE_TURN_MS = 600L

        const val MEASURING = "正在测这台手机跑 PP-OCR 有多快"
    }

    private val stage get() = compose.onNodeWithContentDescription(compose.activity.getString(R.string.onboarding_sample))

    private fun stageSays(text: String) = stage.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text))

    private fun engineRow(label: String) = compose.onNode(hasText(label) and isSelectable())

    /** 在样图上点图像坐标 [p]：和手指点一样，经过画布上那层只认点一下的。 */
    private fun tapImage(p: PointF) {
        val size = stage.fetchSemanticsNode().size
        val vp = Viewport.fit(TourSample.WIDTH, TourSample.HEIGHT, size.width, size.height)
        val at = vp.imageToScreen(p)
        stage.performTouchInput { click(Offset(at.x, at.y)) }
        idle()
    }

    @Test fun `the welcome page comes first and the scan waits for its own page`() {
        show()
        // 欢迎页上待多久都不演：没有样图，是原先那一屏的内容
        advance(3_000)
        compose.onNodeWithText("有码").assertIsDisplayed()
        compose.onNodeWithText("自动找出截图里的手机号、地址、人脸并打码").assertIsDisplayed()
        compose.onNodeWithText("默认不联网，图片不离开手机").assertIsDisplayed()
        compose.onNodeWithText("导出时去掉位置、机型等隐藏信息").assertIsDisplayed()
        compose.onNodeWithContentDescription("第 1 步，共 4 步：欢迎").assertExists()
        stage.assertDoesNotExist()
        next()
        advance(400)
        stageSays("识别中…")
        compose.onNodeWithText("自动打码").assertIsDisplayed()
        settle()
        stageSays("遮住了 4 处")
    }

    @Test fun `next during the scan lands the masks at once`() {
        show()
        next()
        advance(200)
        stageSays("识别中…")
        next()
        compose.onNodeWithText("点文字打码").assertIsDisplayed()
        stageSays("遮住了 4 处")
    }

    @Test fun `tapping the outlined link masks it`() {
        show()
        next()
        settle()
        next()
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
        next(animate = false)
        next(animate = false)
        stage.performSemanticsAction(SemanticsActions.OnClick)
        stageSays("遮住了 5 处")
        stage.performSemanticsAction(SemanticsActions.OnClick)
        stageSays("遮住了 4 处")
    }

    @Test fun `back walks the steps and leaves the first one to the system`() {
        show(animate = false)
        val back = compose.activity.onBackPressedDispatcher
        assertThat(back.hasEnabledCallbacks()).isFalse()
        next(animate = false)
        next(animate = false)
        compose.onNodeWithContentDescription("第 3 步，共 4 步：点文字打码").assertExists()
        assertThat(back.hasEnabledCallbacks()).isTrue()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第 2 步，共 4 步：自动打码").assertExists()
        compose.onNodeWithText("自动打码").assertIsDisplayed()
        compose.onNodeWithText("点文字打码").assertDoesNotExist()
        compose.runOnUiThread { back.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("第 1 步，共 4 步：欢迎").assertExists()
        assertThat(back.hasEnabledCallbacks()).isFalse()
    }

    @Test fun `the labs switch is the real setting and masks the chat`() {
        show()
        next()
        settle()
        next()
        next()
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
        repeat(3) {
            compose.onNodeWithText("跳过").performClick()
            compose.onNodeWithText("选择图片").assertDoesNotExist()
            compose.onNodeWithText("打开设置").assertDoesNotExist()
            next(animate = false)
        }
        compose.onNodeWithText("跳过").performClick()
        assertThat(skipped).isEqualTo(4)
        compose.onNodeWithText("选择图片").performClick()
        compose.onNodeWithText("打开设置").performClick()
        assertThat(picked).isEqualTo(1)
        assertThat(opened).isEqualTo(1)
    }

    @Test fun `with animations off there is no scan`() {
        show(animate = false)
        next(animate = false)
        stageSays("遮住了 4 处")
    }

    /** 划开「自动打码」时识别动效还在演：码当场落下，不再从头演一遍。划的是图：图叠在翻页上面，照样翻得动。 */
    @Test fun `swiping away during the scan lands the masks`() {
        show()
        next()
        stageSays("识别中…")
        stage.performTouchInput { swipeLeft() }
        advance(PAGE_TURN_MS)
        compose.onNodeWithText("点文字打码").assertIsDisplayed()
        stageSays("遮住了 4 处")
    }

    /** 快点两下「下一步」：第二下落在正滑进来的最后一页上，不算「选择图片」，引导不能就这么没了。 */
    @Test fun `a quick double tap on next does not leave the tour`() {
        show()
        next()
        settle()
        next()
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
        next(animate = false)
        stageSays("遮住了 9 处")
    }

    @Test fun `leaving hides it from the screen reader and from back`() {
        show(animate = false)
        next(animate = false)
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
        next()
        settle()
        next()
        tapImage(boxes.linkCenter)
        advance(400)
        restore.emulateSavedInstanceStateRestore()
        idle()
        advance(100)
        compose.onNodeWithText("点文字打码").assertIsDisplayed()
        compose.onNodeWithText("识别中…").assertDoesNotExist()
        stageSays("遮住了 5 处")
    }

    /** 测得快：说用了多久，替人选上 PP-OCR。 */
    @Test fun `a fast phone keeps PP-OCR and says how long it took`() {
        advice = EngineAdvice.Fast(1_234)
        show(animate = false)
        assertThat(chosen).isEmpty()
        toEnginePage()
        compose.onNodeWithText("PP-OCR 识别这张图用了 1.2 秒，默认用效果更好的 PP-OCR；觉得慢可以换成 ML Kit").assertIsDisplayed()
        compose.onNodeWithText("设置 › AI › 识别引擎").assertIsDisplayed()
        assertThat(chosen).containsExactly(TextEngineOption.PADDLE)
        engineRow("PP-OCR").assertIsSelected()
        engineRow("ML Kit").assertIsNotSelected()
    }

    /** 测得慢：建议 ML Kit、替人选上，说效果不好去哪儿换回来。 */
    @Test fun `a slow phone gets ML Kit and is told where to switch back`() {
        advice = EngineAdvice.Slow(4_210)
        show(animate = false)
        toEnginePage()
        compose.onNodeWithText("PP-OCR 识别这张图用了 4.3 秒，建议用更快的 ML Kit；效果不好可以在「设置 › AI」里换回")
            .assertIsDisplayed()
        assertThat(chosen).containsExactly(TextEngineOption.MLKIT)
        engineRow("ML Kit").assertIsSelected()
    }

    @Test fun `a phone that cannot run PP-OCR gets ML Kit`() {
        advice = EngineAdvice.Failed
        show(animate = false)
        toEnginePage()
        compose.onNodeWithText("PP-OCR 在这台手机上跑不起来，建议用 ML Kit").assertIsDisplayed()
        assertThat(chosen).containsExactly(TextEngineOption.MLKIT)
    }

    /** 到了这一页还在测：先说在测；3 秒一到就建议 ML Kit，跑完再补上用了多久，选只选一次。 */
    @Test fun `the verdict lands on the page when it comes and is applied once`() {
        advice = EngineAdvice.Measuring
        show(animate = false)
        toEnginePage()
        compose.onNodeWithText(MEASURING).assertIsDisplayed()
        assertThat(chosen).isEmpty()
        advice = EngineAdvice.Slow(null)
        compose.waitForIdle()
        compose.onNodeWithText("PP-OCR 识别这张图超过了 3 秒，建议用更快的 ML Kit；效果不好可以在「设置 › AI」里换回")
            .assertIsDisplayed()
        assertThat(chosen).containsExactly(TextEngineOption.MLKIT)
        advice = EngineAdvice.Slow(5_000)
        compose.waitForIdle()
        compose.onNodeWithText("PP-OCR 识别这张图用了 5.0 秒，建议用更快的 ML Kit；效果不好可以在「设置 › AI」里换回")
            .assertIsDisplayed()
        assertThat(chosen).containsExactly(TextEngineOption.MLKIT)
    }

    /** 没等结论就自己点了一个：结论来了也不替人改。 */
    @Test fun `a pick made before the verdict is kept`() {
        advice = EngineAdvice.Measuring
        show(animate = false)
        toEnginePage()
        engineRow("ML Kit").performClick()
        assertThat(engine).isEqualTo(TextEngineOption.MLKIT)
        advice = EngineAdvice.Fast(800)
        compose.waitForIdle()
        assertThat(chosen).containsExactly(TextEngineOption.MLKIT)
        engineRow("ML Kit").assertIsSelected()
    }

    /** 结论来时不在这一页上（跳过了、翻过去了）：不替人改；翻回来看到了再选。 */
    @Test fun `the verdict waits until the page is seen`() {
        advice = EngineAdvice.Measuring
        show(animate = false)
        toEnginePage()
        next(animate = false)
        advice = EngineAdvice.Slow(null)
        compose.waitForIdle()
        assertThat(chosen).isEmpty()
        assertThat(engine).isEqualTo(TextEngineOption.PADDLE)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertThat(chosen).containsExactly(TextEngineOption.MLKIT)
    }

    /** 测出结论前后字数不一样：图不跟着跳。 */
    @Test fun `the engine page keeps the sample still when the text changes`() {
        advice = EngineAdvice.Measuring
        show(animate = false)
        toEnginePage()
        val before = stage.getBoundsInRoot()
        advice = EngineAdvice.Slow(10_000)
        compose.waitForIdle()
        val after = stage.getBoundsInRoot()
        assertThat(after.top.value).isWithin(0.5f).of(before.top.value)
        assertThat(after.height.value).isWithin(0.5f).of(before.height.value)
    }

    /** 横着放：图在左，说明在右，按钮都在屏幕里。 */
    @Config(qualifiers = "zh-rCN-w915dp-h412dp-xxhdpi")
    @Test fun `landscape puts the sample beside the text`() {
        show(animate = false)
        compose.onNodeWithText("有码").assertIsDisplayed()
        next(animate = false)
        val image = stage.getBoundsInRoot()
        val title = compose.onNodeWithText("自动打码").getBoundsInRoot()
        assertThat(image.right).isAtMost(title.left)
        compose.onNodeWithText("跳过").assertIsDisplayed()
        compose.onNodeWithText("下一步").assertIsDisplayed()
    }

    /** 竖着放：图在上，说明在下；自动打码、点文字打码两页的图一样大、一样高（平移时不上下跳），最后一页说明高，图小一些。 */
    @Test fun `portrait stacks the sample above the text and keeps it level between the two tap pages`() {
        show(animate = false)
        next(animate = false)
        val image = stage.getBoundsInRoot()
        val title = compose.onNodeWithText("自动打码").getBoundsInRoot()
        assertThat(image.bottom).isAtMost(title.top)
        next(animate = false)
        val second = stage.getBoundsInRoot()
        assertThat(second.top.value).isWithin(0.5f).of(image.top.value)
        assertThat(second.height.value).isWithin(0.5f).of(image.height.value)
        next(animate = false)
        assertThat(stage.getBoundsInRoot().height).isLessThan(image.height)
        compose.onNodeWithText("选择图片").assertIsDisplayed()
    }

    /** 「点文字打码」那一页的标题、正文（dp，不按屏幕裁掉）。 */
    private fun tapPageText(): Pair<DpRect, DpRect> = Pair(
        compose.onNodeWithText("点文字打码").getUnclippedBoundsInRoot(),
        compose.onNodeWithText("漏掉的字点一下就遮住，再点一下取消").getUnclippedBoundsInRoot(),
    )

    private fun assertSameBounds(actual: DpRect, expected: DpRect) {
        assertThat(actual.left.value).isWithin(0.5f).of(expected.left.value)
        assertThat(actual.top.value).isWithin(0.5f).of(expected.top.value)
        assertThat(actual.right.value).isWithin(0.5f).of(expected.right.value)
        assertThat(actual.bottom.value).isWithin(0.5f).of(expected.bottom.value)
    }

    /** 翻页途中图只有一块、一点不动，只有说明在滑：进来的那一页从上到下一层比一层落在后面（视差），停下时对齐。 */
    @Test fun `turning a page slides only the text with the lower layers further out`() {
        show()
        next()
        settle()
        val image = stage.getBoundsInRoot()
        val width = compose.onRoot().getBoundsInRoot().width.value
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS / 3)
        compose.onAllNodesWithContentDescription("示例聊天截图").assertCountEquals(1)
        assertSameBounds(stage.getBoundsInRoot(), image)
        // 进来的这一页在右边。停稳时标题、正文的左边都缩进 24 dp，对齐
        val (title, body) = tapPageText()
        assertThat(title.left.value).isLessThan(width)
        assertThat(title.left.value).isGreaterThan(24.5f)
        assertThat(body.left.value).isGreaterThan(title.left.value)
        settle()
        assertSameBounds(stage.getBoundsInRoot(), image)
        val (titleAfter, bodyAfter) = tapPageText()
        assertThat(bodyAfter.left.value).isWithin(0.5f).of(titleAfter.left.value)
    }

    /** 从右往左排的语言里下一页从左边进来：下面几层落在更左边（更后面），不往中间挤；图照样不动。 */
    @Test fun `in right to left layouts the lower layers trail on the left`() {
        direction = LayoutDirection.Rtl
        show()
        next()
        settle()
        val image = stage.getBoundsInRoot()
        val width = compose.onRoot().getBoundsInRoot().width.value
        compose.onNodeWithText("下一步").performClick()
        advance(PAGE_TURN_MS / 3)
        assertSameBounds(stage.getBoundsInRoot(), image)
        val (title, body) = tapPageText()
        assertThat(title.right.value).isGreaterThan(0f)
        assertThat(title.right.value).isLessThan(width - 24.5f)
        assertThat(body.right.value).isLessThan(title.right.value)
    }

    /** 翻进说明更高的一页：图左右不动，下边翻到哪儿往上让到哪儿；那一页的说明贴着图的下边进来，不钻到图底下。 */
    @Test fun `a taller page pushes the sample up as it slides in`() {
        show()
        next()
        settle()
        next()
        val tapPage = stage.getBoundsInRoot()
        compose.onNodeWithText("下一步").performClick()
        // 这一页的图上下还有富余，翻过一半多才开始变小
        advance(PAGE_TURN_MS / 3)
        val turning = stage.getBoundsInRoot()
        val title = compose.onNodeWithText("设置里还有更多").getUnclippedBoundsInRoot()
        settle()
        val morePage = stage.getBoundsInRoot()
        assertThat(morePage.height).isLessThan(tapPage.height)
        assertThat(turning.height).isLessThan(tapPage.height)
        assertThat(turning.height).isGreaterThan(morePage.height)
        assertThat((turning.left + turning.right).value / 2).isWithin(0.5f).of((tapPage.left + tapPage.right).value / 2)
        assertThat(title.top).isAtLeast(turning.bottom)
    }

    /** 英文环境下换成英文的样图和它的框：码的处数是它的，框出的链接点得中。 */
    @Config(qualifiers = "en-rUS-w412dp-h915dp-xxhdpi")
    @Test fun `in english the tour runs on the english sample`() {
        assertThat(boxes).isSameInstanceAs(TourSample.EN)
        val auto = TourSample.EN.auto.size
        show()
        compose.onNodeWithText("Next").performClick()
        advance(PAGE_TURN_MS)
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

    /** 小屏、字放大：每一页的按钮都还在屏幕里，识别引擎那一页也是。 */
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xhdpi", fontScale = 1.3f)
    @Test fun `a small phone with large text still reaches every button`() {
        advice = EngineAdvice.Slow(4_200)
        show(animate = false)
        compose.onNodeWithText("Yomark").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Masked automatically").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Engine").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
        compose.onNodeWithText("Next").performClick()
        compose.onNodeWithText("Choose image").assertIsDisplayed()
        compose.onNodeWithText("Skip").assertIsDisplayed()
    }
}
