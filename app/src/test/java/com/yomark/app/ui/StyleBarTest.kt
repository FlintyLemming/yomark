package com.yomark.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import com.yomark.app.core.model.MaskLook
import com.yomark.app.core.model.MaskOptions
import com.yomark.app.core.model.MaskStyle
import com.yomark.app.ui.components.StyleBar
import com.yomark.app.ui.components.StyleBarActions
import com.yomark.app.ui.components.StyleBarModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 样式栏与它弹出的调节面板（spec §7.5 修订）。面板只发出新的样子，写进画笔、改选中的框由 ViewModel 管，
 * 那一段见 EditorViewModelTest。原先在 androidTest 里的样式栏测试挪到了这里：放在 androidTest 里 CI 不跑。
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

    @Test fun allSixStylesAreOffered() {
        show(StyleBarModel(MaskLook()))
        listOf("色块", "表情", "抹除", "马赛克", "模糊", "马克笔").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
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

    @Test fun eraseDegradationNoteIsShownWhenPresent() {
        show(StyleBarModel(MaskLook(MaskStyle.ERASE), degradeNote = "3 处背景太复杂，已改用色块"))
        compose.onNodeWithText("3 处背景太复杂，已改用色块").assertIsDisplayed()
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

    @Test fun theEyedropperStartsAndStopsPicking() {
        val actions = Recorder()
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { StyleBar(model, actions) } } }
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

    @Test fun eraseOffersTheColorItFallsBackTo() {
        val actions = Recorder()
        show(open(MaskStyle.ERASE), actions)
        compose.onNodeWithText("改用色块时的颜色").assertIsDisplayed()
        compose.onNodeWithContentDescription("黑色").performClick()
        val look = actions.looks.single().first
        assertThat(look.style).isEqualTo(MaskStyle.ERASE)
        assertThat(look.options.solidColor).isEqualTo(0xFF000000.toInt())
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
}
