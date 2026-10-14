package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.ui.components.EditorToolbar
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.ToolGroup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 手机底栏的工具那一行：收着时胶囊里「打码」「编辑」两组，旁边 AI、按住对比两颗圆钮；点一组胶囊撑满这一行，
 * 那一组变成返回，圆钮让开。圆钮上没有字，按读屏念的那一句找。
 * 换样式、弹面板由 ViewModel 管（见 EditorViewModelTest），这里只看这一行发出什么。
 */
@RunWith(RobolectricTestRunner::class)
// 真的量字：窄屏上名字写不写得下要按真的字宽算
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class EditorToolbarTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder : StyleBarActions {
        val clicked = mutableListOf<MaskStyle>()
        override fun onStyleClick(style: MaskStyle) { clicked += style }
    }

    private var open by mutableStateOf<ToolGroup?>(null)
    private var look by mutableStateOf(MaskLook())
    private var ai by mutableStateOf<AiReview>(AiReview.Hidden)
    private var canCompare by mutableStateOf(true)
    private val compare = MutableInteractionSource()
    private val compareEvents = mutableListOf<Interaction>()
    private val opened = mutableListOf<ToolGroup?>()
    private var purposeClicks = 0
    private var aiClicks = 0

    private fun show(
        actions: StyleBarActions = Recorder(),
        purposeOn: Boolean = false,
        initial: ToolGroup? = null,
    ) {
        open = initial
        compose.setContent {
            MaterialTheme {
                Surface {
                    EditorToolbar(
                        open = open,
                        onOpen = { opened += it; open = it },
                        styleBar = StyleBarModel(look),
                        styleActions = actions,
                        purposeOn = purposeOn,
                        onPurpose = { purposeClicks++ },
                        aiReview = ai,
                        onAiReview = { aiClicks++ },
                        compare = compare,
                        canCompare = canCompare,
                    )
                    LaunchedEffect(compare) { compare.interactions.collect { compareEvents += it } }
                }
            }
        }
    }

    @Test fun collapsedItShowsTheTwoGroupsAndCompare() {
        show()
        listOf("打码", "编辑").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compareButton.assertIsDisplayed()
        listOf("色块", "马克笔", "水印").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    /** 收着时整组居中，圆钮在胶囊右边；展开后胶囊撑满，返回挪到最左边。 */
    @Test fun theRowFloatsInTheMiddleAndOpensToTheFullWidth() {
        ai = AiReview.Ready
        show()
        val width = compose.onRoot().getUnclippedBoundsInRoot().right
        val redact = compose.onNodeWithText("打码").getUnclippedBoundsInRoot()
        val edit = compose.onNodeWithText("编辑").getUnclippedBoundsInRoot()
        val sparkle = compose.onNodeWithContentDescription("AI 增强识别").getUnclippedBoundsInRoot()
        val cmp = compareButton.getUnclippedBoundsInRoot()
        assertThat(sparkle.left).isGreaterThan(edit.right)
        assertThat(cmp.left).isGreaterThan(sparkle.right)
        // 胶囊两头里面各留 8 dp
        assertThat((redact.left - 8.dp - (width - cmp.right)).value).isWithin(1f).of(0f)

        compose.onNodeWithText("打码").performClick()
        // 左边只剩一颗返回箭头，按下去的水波碰不到它右边的分隔线和工具
        val back = backButton.getUnclippedBoundsInRoot()
        assertThat(back.left.value).isWithin(1f).of(20f)
        assertThat(back.width.value).isWithin(1f).of(40f)
        assertThat(compose.onNodeWithText("色块").getUnclippedBoundsInRoot().left).isGreaterThan(back.right)
        assertThat(compose.onNodeWithText("模糊").getUnclippedBoundsInRoot().right).isGreaterThan(width - 40.dp)
    }

    /** 窄屏上字调大了：先去掉小箭头，名字不截断。 */
    @Config(qualifiers = "en-rUS-w360dp-h740dp-xxhdpi", fontScale = 1.5f)
    @Test fun onANarrowScreenTheNamesStayWhole() {
        ai = AiReview.Ready
        show()
        listOf("Redact", "Edit").forEach { name ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertThat(layouts.single().isLineEllipsized(0)).isFalse()
        }
    }

    /** 展开后每一项是小样子和名字并排的一行：名字在小样子右边，和胶囊的中线对齐，选中的还带着小箭头。 */
    @Test fun anUnfoldedToolPutsItsGlyphBesideItsName() {
        show(initial = ToolGroup.REDACT)
        for (name in listOf("色块", "马赛克", "模糊")) {
            val slot = compose.onNodeWithText(name).getUnclippedBoundsInRoot()
            val text = compose.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot()
            // 左边让出小胶囊的边和 24 dp 的小样子
            assertThat((text.left - slot.left).value).isAtLeast(8f + 24f)
            assertThat(((text.top + text.bottom) / 2 - (slot.top + slot.bottom) / 2).value).isWithin(1.5f).of(0f)
        }
        assertThat(compose.onNodeWithText("色块").getUnclippedBoundsInRoot().height.value).isWithin(1f).of(48f)
    }

    /**
     * 窄屏、英文的长名字：哪个名字都不截断。选中的 Marker 写全，短的 Pen 也写全，
     * 写不下的 Watermark 只剩小样子（名字是透明的，伸出格子外，读屏还念得到）。
     */
    @Config(qualifiers = "en-rUS-w360dp-h740dp-xxhdpi")
    @Test fun onANarrowScreenTheChosenToolKeepsItsNameAndLongNamesStepAside() {
        look = MaskLook(MaskStyle.MARKER)
        show(initial = ToolGroup.EDIT)
        listOf("Marker", "Pen", "Watermark").forEach { name ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertThat(layouts.single().isLineEllipsized(0)).isFalse()
        }
        fun overhang(name: String) = compose.onNodeWithText(name, useUnmergedTree = true).getUnclippedBoundsInRoot().right -
            compose.onNodeWithText(name).getUnclippedBoundsInRoot().right
        assertThat(overhang("Marker").value).isLessThan(0f)
        assertThat(overhang("Pen").value).isLessThan(0f)
        assertThat(overhang("Watermark").value).isGreaterThan(0f)
    }

    /**
     * 两组都只是按钮，没有选中态（选中的胶囊看着像「已经在这儿了」，点了也没用）。
     * 画笔在哪一组，那一组就说出在用的样式：马克笔盖不住，用着它的时候说话的是「编辑」。
     */
    @Test fun theGroupHoldingTheBrushSaysWhatItIsUsing() {
        look = MaskLook(MaskStyle.PIXELATE)
        show()
        listOf("打码", "编辑").forEach { compose.onNodeWithText(it).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Selected)) }
        compose.onNodeWithText("打码").assert(stateIs("马赛克"))
        compose.onNodeWithText("编辑").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        look = MaskLook(MaskStyle.MARKER)
        compose.onNodeWithText("打码").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        compose.onNodeWithText("编辑").assert(stateIs("马克笔"))
    }

    /** 点一组是展开，点返回箭头是收起：读屏照着念。 */
    @Test fun aGroupSaysTappingUnfoldsIt() {
        show()
        compose.onNodeWithText("打码").assert(clickLabelIs("展开"))
        compose.onNodeWithText("打码").performClick()
        backButton.assert(clickLabelIs("收起"))
    }

    @Test fun redactUnfoldsTheStylesThatCoverAndHidesTheRest() {
        show()
        compose.onNodeWithText("打码").performClick()
        assertThat(opened).containsExactly(ToolGroup.REDACT)
        listOf("色块", "马赛克", "模糊").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("色块").assertIsSelected()
        // 两组都让开了，「打码」两个字也不留，左边只有返回箭头
        listOf("马克笔", "编辑", "打码").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        backButton.assertIsDisplayed()
        compareButton.assertDoesNotExist()
        compose.onNodeWithText("抹除").assertDoesNotExist()      // 并进了色块，是色块的近似色
    }

    @Test fun tappingAStyleReportsIt() {
        val actions = Recorder()
        show(actions = actions, initial = ToolGroup.REDACT)
        compose.onNodeWithText("马赛克").performClick()
        assertThat(actions.clicked).containsExactly(MaskStyle.PIXELATE)
        assertThat(opened).isEmpty()
    }

    /** 点返回箭头收回去，两组和右边的圆钮回来。 */
    @Test fun theArrowIsTheWayBack() {
        show(initial = ToolGroup.REDACT)
        backButton.performClick()
        assertThat(opened).containsExactly(null)
        compose.onNodeWithText("色块").assertDoesNotExist()
        backButton.assertDoesNotExist()
        listOf("打码", "编辑").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compareButton.assertIsDisplayed()
    }

    private val backButton get() = compose.onNodeWithContentDescription("返回")

    /** 编辑：马克笔、画笔（两种样式，和打码的一样点了换）、水印（点开用途水印）。 */
    @Test fun editHoldsTheMarkerThePenAndTheWatermark() {
        val actions = Recorder()
        show(actions = actions)
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("打码").assertDoesNotExist()
        compose.onNodeWithText("表情").assertDoesNotExist()      // 去掉了
        compose.onNodeWithText("马克笔").assertIsNotSelected().performClick()
        compose.onNodeWithText("画笔").assertIsEnabled().assertIsNotSelected().performClick()
        assertThat(actions.clicked).containsExactly(MaskStyle.MARKER, MaskStyle.PEN).inOrder()
        compose.onNodeWithText("水印").performClick()
        assertThat(purposeClicks).isEqualTo(1)
    }

    // ---------- AI 增强识别：胶囊旁边一颗圆钮 ----------

    @Test fun aiReviewSitsBesideTheGroupsAndRunsOnClick() {
        ai = AiReview.Ready
        show()
        compose.onNodeWithContentDescription("AI 增强识别").assertIsEnabled().performClick()
        assertThat(aiClicks).isEqualTo(1)
    }

    @Test fun aiReviewIsNotOfferedWhereItCannotRun() {
        show()
        compose.onNodeWithContentDescription("AI 增强识别").assertDoesNotExist()
        compareButton.assertIsDisplayed()
    }

    /** 跑着、跑完了都点不动；圆钮上写不下字，读屏念的是结果，新发现几处是角标上的数。没跑成是另一句话，还能点。 */
    @Test fun aiReviewShowsItsProgressAndResultInPlace() {
        ai = AiReview.Running
        show()
        compose.onNodeWithContentDescription("增强识别中…").assertIsNotEnabled()
        ai = AiReview.Done(found = 3)
        compose.onNodeWithContentDescription("新发现 3 处").assertIsDisplayed().assertIsNotEnabled().assert(hasText("3"))
        ai = AiReview.Done(found = 0)
        compose.onNodeWithContentDescription("没有新发现").assertIsDisplayed().assert(!hasText("0"))
        ai = AiReview.Failed
        compose.onNodeWithContentDescription("识别失败，重试").assertIsEnabled().performClick()
        assertThat(aiClicks).isEqualTo(1)
    }

    @Test fun aiReviewStepsAsideWhileAGroupIsOpen() {
        ai = AiReview.Ready
        show(initial = ToolGroup.EDIT)
        compose.onNodeWithContentDescription("AI 增强识别").assertDoesNotExist()
    }

    private fun stateIs(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    private fun clickLabelIs(text: String) = SemanticsMatcher("点击说的是「$text」") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == text
    }

    // ---------- 按住对比 ----------

    private val compareButton get() = compose.onNodeWithContentDescription("按住对比")

    /** 按下去就是在比，一直按着也是，松手就回来；按住的时候不弹提示。 */
    @Test fun holdingComparePressesUntilLetGo() {
        show()
        compose.mainClock.autoAdvance = false
        compareButton.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(compareEvents.last()).isInstanceOf(PressInteraction.Press::class.java)
        compareButton.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertThat(compareEvents.last()).isInstanceOf(PressInteraction.Release::class.java)
        compose.onNodeWithText("按住对比原图").assertDoesNotExist()
    }

    /** 圆钮上写不下「按住」：点一下没比上，就弹个提示说要按住，过一会儿自己收起。 */
    @Test fun tappingCompareSaysToHoldIt() {
        show()
        compose.mainClock.autoAdvance = false
        compareButton.performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("按住对比原图").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithText("按住对比原图").assertDoesNotExist()
    }

    @Test fun nothingToCompareGraysItOut() {
        canCompare = false
        show()
        compareButton.assertIsNotEnabled()
    }

    @Test fun theWatermarkSaysWhenItIsOn() {
        show(purposeOn = true, initial = ToolGroup.EDIT)
        compose.onNodeWithText("水印").assert(stateIs("已开启"))
    }
}
