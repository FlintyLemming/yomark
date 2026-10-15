package moe.flinty.yomark.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.components.EditorBottomBar
import moe.flinty.yomark.ui.components.PanelColumn
import moe.flinty.yomark.ui.components.PurposePanel
import moe.flinty.yomark.ui.components.PurposePanelActions
import moe.flinty.yomark.ui.components.PurposePanelContent
import moe.flinty.yomark.ui.components.PurposePanelModel
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.ToolGroup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 用途水印的调节面板，和马克笔、画笔的一个样子：手机上从底栏的胶囊长出来（这里连底栏一起摆出来），宽屏上占列表的位置。
 * 面板只发出新的文字、样子；写进 ViewModel、和样式面板换着开的规矩见 EditorViewModelWatermarkTest，
 * 在真的编辑器里走一遍见 EditorScreenLayoutTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class PurposePanelTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder : PurposePanelActions {
        var toggles = 0
        val texts = mutableListOf<String>()
        val styles = mutableListOf<PurposeWatermarkStyle>()
        var resets = 0
        val applies = mutableListOf<Boolean>()
        var collapsed = 0
        val picks = mutableListOf<Boolean>()
        override fun onToggle() { toggles++ }
        override fun onTextChange(text: String) { texts += text }
        override fun onStyleChange(style: PurposeWatermarkStyle) { styles += style }
        override fun onReset() { resets++ }
        override fun onApply(on: Boolean) { applies += on }
        override fun onCollapse() { collapsed++ }
        override fun onPickColor(pick: Boolean) { picks += pick }
    }

    /** 底栏要收起（null）或展开哪一组。 */
    private val toolGroups = mutableListOf<ToolGroup?>()

    /** 手机的底栏，展开着「编辑」：水印在这一组里，面板就是从它里面长出来的。 */
    @Composable
    private fun PhoneBar(model: PurposePanelModel, actions: PurposePanelActions, styleBar: StyleBarModel = StyleBarModel(MaskLook())) {
        EditorBottomBar(
            toolGroup = ToolGroup.EDIT,
            onToolGroup = { toolGroups += it },
            styleBar = styleBar,
            styleActions = object : StyleBarActions {},
            compare = remember { MutableInteractionSource() },
            canCompare = false,
            purpose = model,
            purposeActions = actions,
        )
    }

    private fun show(model: PurposePanelModel, actions: PurposePanelActions = Recorder()) {
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions) } } }
    }

    private val opened = PurposePanelModel(text = "仅供办理签证使用", open = true)

    private val field get() = compose.onNode(hasSetTextAction())

    @Test fun thePanelStaysOutOfTheWayUntilOpened() {
        show(opened.copy(open = false))
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /** 和样式面板一样从胶囊往上长出来，顶上写着「用途水印」，工具那一行不挪地方。 */
    @Test fun thePanelGrowsOutOfTheBarLikeTheStylePanels() {
        var model by mutableStateOf(opened.copy(open = false))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, Recorder()) } } }
        val tool = compose.onNodeWithText("水印").getUnclippedBoundsInRoot()
        model = opened
        val title = compose.onNodeWithText("用途水印").assertIsDisplayed().getUnclippedBoundsInRoot()
        val apply = compose.onNodeWithText("应用").assertIsDisplayed().getUnclippedBoundsInRoot()
        // 选中的水印撑宽了（见 EditorToolbarTest），这一行上下没挪
        val picked = compose.onNodeWithText("水印").getUnclippedBoundsInRoot()
        assertThat(picked.top to picked.bottom).isEqualTo(tool.top to tool.bottom)
        assertThat(title.bottom).isLessThan(tool.top)
        assertThat(apply.bottom).isAtMost(tool.top)
        field.assert(hasText("仅供办理签证使用"))
        listOf("文字", "颜色").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("角度", "不透明度", "密度").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed() }
    }

    /** 底栏上的水印就是开关：面板开着时再点一下是收起。 */
    @Test fun tappingTheWatermarkToolTogglesThePanel() {
        val actions = Recorder()
        show(opened, actions)
        compose.onNodeWithText("水印").performClick()
        assertThat(actions.toggles).isEqualTo(1)
    }

    @Test fun theCollapseButtonFoldsThePanel() {
        val actions = Recorder()
        show(opened, actions)
        compose.onNodeWithContentDescription("收起水印面板").performClick()
        assertThat(actions.collapsed).isEqualTo(1)
    }

    // ---------- 文字 ----------

    /** 边打字边发出去，图上的水印跟着变。 */
    @Test fun typingChangesTheText() {
        val actions = Recorder()
        show(opened, actions)
        field.performTextReplacement("仅供入职使用")
        assertThat(actions.texts).containsExactly("仅供入职使用")
    }

    /** 清空了框里就是空的，不跳回上一句。 */
    @Test fun clearingTheTextLeavesTheFieldEmpty() {
        val actions = Recorder()
        var model by mutableStateOf(opened)
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions) } } }
        field.performTextClearance()
        model = model.copy(text = "")
        assertThat(actions.texts).containsExactly("")
        field.assert(!hasText("仅供办理签证使用"))
    }

    /** 手机上打字时输入法占掉下半截：面板只留文字那一格，图还露得出来；输入法收起，面板再摊开。 */
    @Test fun whileTypingOnAPhoneOnlyTheTextStays() {
        var ime by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                Surface { PanelColumn { PurposePanelContent(opened, Recorder(), compactWhileTyping = true, imeVisible = ime) } }
            }
        }
        field.requestFocus()
        compose.onNodeWithText("颜色").assertIsDisplayed()      // 输入法还没弹出来
        ime = true
        compose.onNodeWithText("颜色").assertDoesNotExist()
        field.assertIsFocused()
        ime = false
        compose.onNodeWithText("颜色").assertIsDisplayed()
    }

    // ---------- 颜色和滑条 ----------

    @Test fun pickingAPresetColorChangesTheColor() {
        val actions = Recorder()
        show(opened, actions)
        compose.onNodeWithContentDescription("黑色").assertIsSelected()
        compose.onNodeWithContentDescription("红色").performClick()
        assertThat(actions.styles).containsExactly(PurposeWatermarkStyle(color = PurposeWatermarkStyle.PALETTE[3]))
    }

    /** 色板外的颜色也行，和马克笔一样有自定义：当前颜色不在色板上时「自定义」是选中的，亮度拖到底就是黑。 */
    @Test fun aColorOffThePaletteIsACustomColor() {
        val actions = Recorder()
        show(opened.copy(style = PurposeWatermarkStyle(color = 0xFF123456.toInt())), actions)
        compose.onNodeWithContentDescription("自定义颜色").assertIsSelected().performClick()
        compose.onNodeWithContentDescription("亮度").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertThat(actions.styles.last().color and 0xFFFFFF).isEqualTo(0)
    }

    /** 角度 15° 一档，拖到 4° 落在放平那一档上；不透明度、密度照拖到的值发出去，别的不动。 */
    @Test fun theSlidersChangeAngleOpacityAndDensity() {
        val actions = Recorder()
        show(opened, actions)
        listOf("-30°", "18%", "适中").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("角度").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        compose.onNodeWithContentDescription("不透明度").performSemanticsAction(SemanticsActions.SetProgress) { it(0.6f) }
        compose.onNodeWithContentDescription("密度").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        val (angle, opacity, density) = actions.styles
        assertThat(angle).isEqualTo(PurposeWatermarkStyle(angle = 0f))
        assertThat(opacity.opacity).isWithin(0.001f).of(0.6f)
        assertThat(density.density).isWithin(0.001f).of(2f)
    }

    /** 吸管要点的是底下的图：等着取色时面板先缩回底栏，取完色（或取消了）再长出来。 */
    @Test fun thePanelStepsAsideWhileTheEyedropperWaits() {
        val actions = Recorder()
        var model by mutableStateOf(opened)
        var bar by mutableStateOf(StyleBarModel(MaskLook()))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions, bar) } } }
        compose.onNodeWithContentDescription("从图上取色").performClick()
        assertThat(actions.picks).containsExactly(true)
        // 吸管等着给谁取色，底栏都是看 colorPick 让开的
        model = model.copy(picking = true)
        bar = bar.copy(colorPick = ColorTarget.PURPOSE)
        compose.onNodeWithText("用途水印").assertDoesNotExist()
        model = model.copy(picking = false)
        bar = bar.copy(colorPick = null)
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        assertThat(actions.collapsed).isEqualTo(0)
    }

    // ---------- 恢复默认、应用 ----------

    @Test fun resetIsOnlyOfferedOnceSomethingWasChanged() {
        show(opened)
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test fun resetReports() {
        val actions = Recorder()
        show(opened.copy(style = PurposeWatermarkStyle(angle = 0f)), actions)
        compose.onNodeWithText("恢复默认").assertIsEnabled().performClick()
        assertThat(actions.resets).isEqualTo(1)
    }

    /** 「应用」是开关，点一下翻过来：关着点是加上，开着点是去掉。 */
    @Test fun theApplySwitchFlips() {
        val actions = Recorder()
        var model by mutableStateOf(opened)
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions) } } }
        compose.onNodeWithText("应用").assertIsOff().performClick()
        model = model.copy(on = true)
        compose.onNodeWithText("应用").assertIsOn().performClick()
        assertThat(actions.applies).containsExactly(true, false).inOrder()
    }

    // ---------- 手机上和样式面板同一块地方 ----------

    /** 面板开着时点它外面（画布那一块）：和点左下角的返回一样，面板连这一组一起收起。点在面板上不收。 */
    @Test fun tappingOutsideThePanelFoldsEverything() {
        show(opened)
        compose.onNodeWithText("颜色").performClick()
        assertThat(toolGroups).isEmpty()
        compose.onRoot().performTouchInput { click(Offset(centerX, 10f)) }
        assertThat(toolGroups).containsExactly(null)
    }

    /** 马克笔的面板开着时点水印：同一块面板换上水印的东西，跟着变高变矮，不先缩回底栏再长出来。 */
    @Test fun switchingFromTheMarkerPanelKeepsThePanelUp() {
        var model by mutableStateOf(opened.copy(open = false))
        var bar by mutableStateOf(StyleBarModel(MaskLook(MaskStyle.MARKER), panelOpen = true))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, Recorder(), bar) } } }
        fun headerTop(label: String) = compose.onNodeWithContentDescription(label).getUnclippedBoundsInRoot().top
        val before = headerTop("收起样式面板")
        compose.mainClock.autoAdvance = false
        model = model.copy(open = true)
        bar = bar.copy(panelOpen = false)
        compose.waitForIdle()
        val tops = List(30) {
            compose.mainClock.advanceTimeByFrame()
            headerTop("收起水印面板")
        }
        compose.mainClock.autoAdvance = true
        val after = headerTop("收起水印面板")
        compose.onNodeWithContentDescription("收起样式面板").assertDoesNotExist()
        assertThat(tops.max()).isAtMost(maxOf(before, after) + 1.dp)
    }

    // ---------- 宽屏 ----------

    /** 宽屏的面板不盖画布，东西和手机上的一样；取色时还在，再点一下吸管就收起它。 */
    @Test fun theWidePanelHasTheSameControlsAndKeepsItsEyedropper() {
        val actions = Recorder()
        var model by mutableStateOf(opened)
        compose.setContent { MaterialTheme { Surface { PurposePanel(model, actions) } } }
        listOf("用途水印", "文字", "颜色", "恢复默认", "应用").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithContentDescription("从图上取色").performClick()
        model = model.copy(picking = true)
        compose.onNodeWithContentDescription("从图上取色").performClick()
        assertThat(actions.picks).containsExactly(true, false).inOrder()
    }
}
