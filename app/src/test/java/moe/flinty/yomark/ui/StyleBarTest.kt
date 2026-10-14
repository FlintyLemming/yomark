package moe.flinty.yomark.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
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
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.StylePanel
import moe.flinty.yomark.ui.components.EditorBottomBar
import moe.flinty.yomark.ui.components.ToolGroup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 样式的调节面板（spec §7.5 修订）：手机上弹出来的那个、宽屏上占列表位置的那个。面板只发出新的样子，
 * 写进画笔、改选中的框由 ViewModel 管，那一段见 EditorViewModelTest。底栏上挑样式的那一行见 EditorToolbarTest。
 * 原先在 androidTest 里的样式栏测试挪到了这里：放在 androidTest 里 CI 不跑。
 *
 * 手机上的面板是底栏的胶囊往上长出来的那一块（见 EditorBottomBar），这里连底栏一起摆出来。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class StyleBarTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder : StyleBarActions {
        val looks = mutableListOf<Pair<MaskLook, Boolean>>()
        var finished = 0
        var resets = 0
        var appliedToAll = 0
        var collapsed = 0
        val picks = mutableListOf<ColorTarget?>()
        override fun onLookChange(look: MaskLook, inProgress: Boolean) { looks += look to inProgress }
        override fun onLookChangeFinished() { finished++ }
        override fun onReset() { resets++ }
        override fun onApplyToAll() { appliedToAll++ }
        override fun onCollapse() { collapsed++ }
        override fun onPickColor(target: ColorTarget?) { picks += target }
    }

    private fun show(model: StyleBarModel, actions: StyleBarActions = Recorder()) {
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions) } } }
    }

    /** 底栏要收起（null）或展开哪一组。 */
    private val toolGroups = mutableListOf<ToolGroup?>()

    /** 手机的底栏，展开着当前样式所在的那一组：面板就是从它里面长出来的。 */
    @Composable
    private fun PhoneBar(model: StyleBarModel, actions: StyleBarActions) {
        EditorBottomBar(
            toolGroup = ToolGroup.of(model.look.style),
            onToolGroup = { toolGroups += it },
            styleBar = model,
            styleActions = actions,
            compare = remember { MutableInteractionSource() },
            canCompare = false,
        )
    }

    private fun open(style: MaskStyle, options: MaskOptions = MaskOptions()) =
        StyleBarModel(MaskLook(style, options), panelOpen = true)

    @Test fun thePanelStaysOutOfTheWayUntilOpened() {
        show(StyleBarModel(MaskLook()))
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    // ---------- 面板 ----------

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

    /** 吸管要点的是底下的图：等着取色时面板先缩回底栏，取完色（或取消了）再长出来。 */
    @Test fun thePanelStepsAsideWhileTheEyedropperWaits() {
        val actions = Recorder()
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions) } } }
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

    /** 开着近似色时选中的是它，色板上哪一格都不算选中（连「自定义」也不算）。 */
    @Test fun nearColorIsTheOnlySelectedSwatchWhileOn() {
        show(open(MaskStyle.SOLID, MaskOptions(solidNear = true)).copy(nearColors = listOf(0xFFFFFFFF.toInt())))
        compose.onNodeWithText("近似色").assertIsSelected()
        compose.onNodeWithContentDescription("天蓝").assertIsNotSelected()
        compose.onNodeWithContentDescription("自定义颜色").assertIsNotSelected()
    }

    @Test fun nearColorIsNotSelectedWhileOff() {
        show(open(MaskStyle.SOLID))
        compose.onNodeWithText("近似色").assertIsNotSelected()
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

    /** 近似色只有色块有：马克笔的颜色是一种固定的颜色。 */
    @Test fun onlyTheSolidStyleOffersNearColor() {
        show(open(MaskStyle.MARKER))
        compose.onNodeWithText("近似色").assertDoesNotExist()
    }

    @Test fun mosaicAndBlurHaveTheirSliders() {
        show(open(MaskStyle.PIXELATE))
        compose.onNodeWithContentDescription("颗粒").assertIsDisplayed()
        compose.onNodeWithText("适中").assertIsDisplayed()
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

    // ---------- 马克笔、画笔 ----------

    /** 马克笔可以拉框、也可以手画；手画时才有笔宽。 */
    @Test fun theMarkerDrawsABoxOrFreehand() {
        val actions = Recorder()
        show(open(MaskStyle.MARKER), actions)
        compose.onNodeWithText("框选").assertIsSelected()
        compose.onNodeWithContentDescription("粗细").assertDoesNotExist()
        compose.onNodeWithText("手绘").performClick()
        assertThat(actions.looks.single().first.options.markerShape).isEqualTo(StrokeShape.FREEHAND)
    }

    @Test fun aFreehandMarkerHasAWidth() {
        show(open(MaskStyle.MARKER, MaskOptions(markerShape = StrokeShape.FREEHAND)))
        compose.onNodeWithText("手绘").assertIsSelected()
        compose.onNodeWithContentDescription("粗细").assertIsDisplayed()
    }

    /** 画笔出厂是手画的红线：能换成拉框，换颜色、调粗细。 */
    @Test fun thePenHasAShapeAColorAndAWidth() {
        val actions = Recorder()
        show(open(MaskStyle.PEN), actions)
        compose.onNodeWithText("手绘").assertIsSelected()
        compose.onNodeWithContentDescription("红色").assertIsSelected()
        compose.onNodeWithContentDescription("粗细").assertIsDisplayed()
        compose.onNodeWithText("框选").performClick()
        compose.onNodeWithContentDescription("蓝色").performClick()
        val (shape, color) = actions.looks.map { it.first.options }
        assertThat(shape.penShape).isEqualTo(StrokeShape.BOX)
        assertThat(color.penColor).isEqualTo(0xFF1E88E5.toInt())
    }

    // ---------- 手机上从底栏长出来 ----------

    /** 面板从底栏的胶囊往上长出来，顶上是当前样式的名字（底栏那一组里也有这个名字），工具那一行不挪地方。 */
    @Test fun thePanelGrowsOutOfTheBarTitledWithTheStyle() {
        var model by mutableStateOf(StyleBarModel(MaskLook()))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, Recorder()) } } }
        val tool = compose.onNodeWithText("色块").getUnclippedBoundsInRoot()
        model = open(MaskStyle.SOLID)
        val reset = compose.onNodeWithText("恢复默认").assertIsDisplayed().getUnclippedBoundsInRoot()
        val names = compose.onAllNodesWithText("色块").fetchSemanticsNodes().map { it.boundsInRoot }
        assertThat(names).hasSize(2)
        // 名字在面板顶上，工具还在原地，面板的东西都在工具上面
        val (title, stillTool) = names.sortedBy { it.top }
        assertThat(stillTool.top).isWithin(0.5f).of(with(compose.density) { tool.top.toPx() })
        assertThat(title.bottom).isLessThan(stillTool.top)
        assertThat(reset.bottom).isAtMost(tool.top)
    }

    /** 面板开着时点它外面（画布那一块）：和点左下角的返回一样，面板连这一组一起收起。 */
    @Test fun tappingOutsideThePanelFoldsEverything() {
        show(open(MaskStyle.SOLID))
        compose.onRoot().performTouchInput { click(Offset(centerX, 10f)) }
        assertThat(toolGroups).containsExactly(null)
    }

    /** 点在面板上，没有控件的地方也不收起。 */
    @Test fun tappingThePanelItselfKeepsItOpen() {
        show(open(MaskStyle.SOLID))
        compose.onNodeWithText("颜色").performClick()
        assertThat(toolGroups).isEmpty()
    }

    /** 吸管等着取色时面板缩回去了，点的是图：不收起。 */
    @Test fun whileTheEyedropperWaitsTappingTheImageDoesNotFold() {
        show(open(MaskStyle.SOLID).copy(colorPick = ColorTarget.SOLID))
        compose.onRoot().performTouchInput { click(Offset(centerX, 10f)) }
        assertThat(toolGroups).isEmpty()
    }

    /** 收起时面板缩回去，之后面板不在了。 */
    @Test fun foldingThePanelShrinksItBackIntoTheBar() {
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, Recorder()) } } }
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        model = model.copy(panelOpen = false)
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }
}
