package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.ui.components.StyleBar
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.StylePanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 样式栏与它弹出的调节面板（spec §7.5 修订）。面板只发出新的样子，写进画笔、改选中的框由 ViewModel 管，
 * 那一段见 EditorViewModelTest。原先在 androidTest 里的样式栏测试挪到了这里：放在 androidTest 里 CI 不跑。
 *
 * 手机上的面板是弹出来的底部动作条，在自己的对话框窗口里；查找节点时各个窗口都会找到。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class StyleBarTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder : StyleBarActions {
        val clicked = mutableListOf<MaskStyle>()
        val looks = mutableListOf<Pair<MaskLook, Boolean>>()
        var finished = 0
        var resets = 0
        var appliedToAll = 0
        var collapsed = 0
        val picks = mutableListOf<ColorTarget?>()
        override fun onStyleClick(style: MaskStyle) { clicked += style }
        override fun onLookChange(look: MaskLook, inProgress: Boolean) { looks += look to inProgress }
        override fun onLookChangeFinished() { finished++ }
        override fun onReset() { resets++ }
        override fun onApplyToAll() { appliedToAll++ }
        override fun onCollapse() { collapsed++ }
        override fun onPickColor(target: ColorTarget?) { picks += target }
    }

    private fun show(model: StyleBarModel, actions: StyleBarActions = Recorder()) {
        compose.setContent { MaterialTheme { Surface { StyleBar(model, actions) } } }
    }

    private fun open(style: MaskStyle, options: MaskOptions = MaskOptions()) =
        StyleBarModel(MaskLook(style, options), panelOpen = true)

    // ---------- 样式那一排 ----------

    @Test fun allFiveStylesAreOffered() {
        show(StyleBarModel(MaskLook()))
        listOf("色块", "表情", "马赛克", "模糊", "马克笔").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("抹除").assertDoesNotExist()      // 并进了色块，是色块的近似色
        compose.onNodeWithText("色块").assertIsSelected()
    }

    @Test fun tappingAStyleReportsIt() {
        val actions = Recorder()
        show(StyleBarModel(MaskLook()), actions)
        compose.onNodeWithText("马赛克").performClick()
        assertThat(actions.clicked).containsExactly(MaskStyle.PIXELATE)
    }

    @Test fun blurShowsTheNotSecureWarning() {
        show(StyleBarModel(MaskLook(MaskStyle.BLUR)))
        compose.onNodeWithText("模糊能被还原，不适合遮挡敏感信息").assertIsDisplayed()
    }

    @Test fun markerShowsTheAnnotationOnlyWarning() {
        show(StyleBarModel(MaskLook(MaskStyle.MARKER)))
        compose.onNodeWithText("马克笔只做标记，盖不住下面的内容").assertIsDisplayed()
    }

    @Test fun thePanelStaysOutOfTheWayUntilOpened() {
        show(StyleBarModel(MaskLook()))
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    // ---------- 面板 ----------

    @Test fun thePanelSaysWhatItChanges() {
        show(open(MaskStyle.SOLID))
        compose.onNodeWithText("之后打码都用这个样式，打好的不变").assertIsDisplayed()
    }

    @Test fun withABoxSelectedThePanelSaysItChangesThatBox() {
        show(open(MaskStyle.SOLID).copy(editingSelection = true))
        compose.onNodeWithText("改的是选中的框，之后打码也用它").assertIsDisplayed()
    }

    @Test fun pickingAPresetColorChangesTheSolidColor() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onNodeWithContentDescription("天蓝").assertIsSelected()
        compose.onNodeWithContentDescription("黑色").performClick()
        val (look, inProgress) = actions.looks.single()
        assertThat(look.style).isEqualTo(MaskStyle.SOLID)
        assertThat(look.options.solidColor).isEqualTo(0xFF000000.toInt())
        assertThat(inProgress).isFalse()
    }

    @Test fun aColorOffThePaletteSelectsTheCustomSwatch() {
        show(open(MaskStyle.SOLID, MaskOptions(solidColor = 0xFF123456.toInt())))
        compose.onNodeWithContentDescription("自定义颜色").assertIsSelected()
    }

    /** 自定义颜色：拖滑条时一路发「还没松手」，松手再收尾，这一段在 ViewModel 那边只记一步撤销。 */
    @Test fun customColorSlidersReportProgressThenFinish() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onNodeWithContentDescription("自定义颜色").performClick()
        compose.onNodeWithContentDescription("亮度").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        val (look, inProgress) = actions.looks.single()
        assertThat(look.options.solidColor).isEqualTo(0xFF000000.toInt())   // 亮度拖到底就是黑
        assertThat(inProgress).isTrue()
        assertThat(actions.finished).isEqualTo(1)
    }

    /** 手指在轨道上拖：一路都是「还没松手」，抬手收尾一次；拖的是滑条，不是外面那层能上下滚的面板。 */
    @Test fun draggingAColorTrackReportsEveryStepAndFinishesOnce() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onNodeWithContentDescription("自定义颜色").performClick()
        compose.onNodeWithContentDescription("色相").performTouchInput { swipeRight(startX = left + 10f, endX = right - 10f) }
        assertThat(actions.looks.size).isAtLeast(2)
        assertThat(actions.looks.all { it.second }).isTrue()
        assertThat(actions.finished).isEqualTo(1)
        // 从天蓝往右拖到头，色相转了一圈回到红色附近
        val last = actions.looks.last().first.options.solidColor
        assertThat((last shr 16) and 0xFF).isGreaterThan(200)
    }

    /** 吸管要点的是底下的图：等着取色时弹出的面板先让开，取完色（或取消了）再弹回来。 */
    @Test fun theSheetStepsAsideWhileTheEyedropperWaits() {
        val actions = Recorder()
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { StyleBar(model, actions) } } }
        compose.onNodeWithContentDescription("从图上取色").performClick()
        assertThat(actions.picks).containsExactly(ColorTarget.SOLID)
        model = model.copy(colorPick = ColorTarget.SOLID)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        model = model.copy(colorPick = null)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        assertThat(actions.collapsed).isEqualTo(0)
    }

    /** 宽屏的面板不盖画布，取色时还在：再点一下吸管就收起它。 */
    @Test fun theEyedropperStartsAndStopsPickingInTheWidePanel() {
        val actions = Recorder()
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { StylePanel(model, actions) } } }
        compose.onNodeWithContentDescription("从图上取色").performClick()
        model = model.copy(colorPick = ColorTarget.SOLID)
        compose.onNodeWithContentDescription("从图上取色").performClick()
        assertThat(actions.picks).containsExactly(ColorTarget.SOLID, null).inOrder()
    }

    @Test fun emojiPanelPicksAnEmojiAndTheLayout() {
        val actions = Recorder()
        show(open(MaskStyle.EMOJI), actions)
        compose.onNodeWithContentDescription("🐱").performClick()
        compose.onNodeWithText("排满").performClick()
        compose.onNodeWithContentDescription("浅黄").performClick()
        val looks = actions.looks.map { it.first.options }
        assertThat(looks[0].emoji).isEqualTo("🐱")
        assertThat(looks[1].emojiTiled).isTrue()
        assertThat(looks[2].emojiBackground).isEqualTo(0xFFFFE082.toInt())
    }

    // ---------- 近似色 ----------

    /** 近似色排在色块的色板最前面；点了还是色块，只是打开近似色，固定的颜色记着不动。 */
    @Test fun pickingNearColorKeepsTheSolidStyle() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID, MaskOptions(solidColor = 0xFF000000.toInt())), actions)
        compose.onNodeWithContentDescription("黑色").assertIsSelected()
        compose.onNodeWithText("近似色").performClick()
        val (look, inProgress) = actions.looks.single()
        assertThat(look.style).isEqualTo(MaskStyle.SOLID)
        assertThat(look.options.solidNear).isTrue()
        assertThat(look.options.solidColor).isEqualTo(0xFF000000.toInt())
        assertThat(inProgress).isFalse()
    }

    /** 开着近似色时选中的是它，色板上哪一格都不算选中（连「自定义」也不算），下面说一句它是怎么取色的。 */
    @Test fun nearColorIsTheOnlySelectedSwatchWhileOn() {
        show(open(MaskStyle.SOLID, MaskOptions(solidNear = true)).copy(nearColors = listOf(0xFFFFFFFF.toInt())))
        compose.onNodeWithText("近似色").assertIsSelected()
        compose.onNodeWithContentDescription("天蓝").assertIsNotSelected()
        compose.onNodeWithContentDescription("自定义颜色").assertIsNotSelected()
        compose.onNodeWithText("每一块取它四周的颜色填上，纯色背景上几乎看不出打过码").assertIsDisplayed()
    }

    @Test fun theNearColorHintStaysHiddenWhileOff() {
        show(open(MaskStyle.SOLID))
        compose.onNodeWithText("近似色").assertIsNotSelected()
        compose.onNodeWithText("每一块取它四周的颜色填上，纯色背景上几乎看不出打过码").assertDoesNotExist()
    }

    /** 开着近似色时点色板上的颜色：换回固定的颜色。 */
    @Test fun pickingAPresetColorTurnsNearColorOff() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID, MaskOptions(solidNear = true)), actions)
        compose.onNodeWithContentDescription("黑色").performClick()
        val options = actions.looks.single().first.options
        assertThat(options.solidNear).isFalse()
        assertThat(options.solidColor).isEqualTo(0xFF000000.toInt())
    }

    /** 近似色只有色块有：表情的底色、马克笔的颜色都是一种固定的颜色。 */
    @Test fun onlyTheSolidStyleOffersNearColor() {
        show(open(MaskStyle.EMOJI))
        compose.onNodeWithText("近似色").assertDoesNotExist()
    }

    @Test fun mosaicAndBlurHaveTheirSliders() {
        show(open(MaskStyle.PIXELATE))
        compose.onNodeWithContentDescription("颗粒").assertIsDisplayed()
        compose.onNodeWithText("细").assertIsDisplayed()
    }

    @Test fun markerChangesColorButKeepsItsOpacity() {
        val actions = Recorder()
        show(open(MaskStyle.MARKER), actions)
        compose.onNodeWithContentDescription("浓淡").assertIsDisplayed()
        compose.onNodeWithContentDescription("蓝色").performClick()
        val options = actions.looks.single().first.options
        assertThat(options.markerColor and 0xFFFFFF).isEqualTo(0x448AFF)
        assertThat(options.markerAlpha).isWithin(0.01f).of(MaskOptions().markerAlpha)
    }

    @Test fun resetIsOnlyOfferedOnceSomethingWasChanged() {
        show(open(MaskStyle.SOLID))
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test fun resetReportsWhenChanged() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID, MaskOptions(solidColor = 0xFF000000.toInt())), actions)
        compose.onNodeWithText("恢复默认").assertIsEnabled().performClick()
        assertThat(actions.resets).isEqualTo(1)
    }

    @Test fun applyToAllSaysHowManyItWouldChange() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID).copy(applicable = 3), actions)
        compose.onNodeWithText("应用到全部 3 处").performClick()
        assertThat(actions.appliedToAll).isEqualTo(1)
    }

    @Test fun applyToAllIsOffWhenThereIsNothingToChange() {
        show(open(MaskStyle.SOLID))
        compose.onNodeWithText("应用到全部").assertIsNotEnabled()
    }

    @Test fun theCollapseButtonFoldsThePanel() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onNodeWithContentDescription("收起样式面板").performClick()
        assertThat(actions.collapsed).isEqualTo(1)
    }

    // ---------- 手机上弹出来 ----------

    /** 面板弹在自己的窗口里，盖在画布上，不占底栏的地方；顶上是当前样式的名字。 */
    @Test fun thePanelPopsUpAsASheetTitledWithTheStyle() {
        show(open(MaskStyle.SOLID))
        compose.onNode(hasText("恢复默认") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText("色块") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    /** 往下拖、点面板外面、按返回、读屏的「关闭」，走的都是这一条：滑下去再报收起，之后面板不在了。 */
    @Test fun dismissingTheSheetFoldsThePanel() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss))
            .performSemanticsAction(SemanticsActions.Dismiss)
        compose.waitForIdle()
        assertThat(actions.collapsed).isEqualTo(1)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /** 样式栏下面的安全性说明被面板盖住了，面板上再说一遍。 */
    @Test fun theSheetRepeatsTheSafetyNoteItCovers() {
        show(open(MaskStyle.PIXELATE))
        val note = "截图上的马赛克能被逐字还原，不适合遮挡敏感信息"
        compose.onAllNodesWithText(note).assertCountEquals(2)
        compose.onNode(hasText(note) and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }
}
