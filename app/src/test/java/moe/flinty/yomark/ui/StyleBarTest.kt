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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.StylePanelContent
import moe.flinty.yomark.ui.components.WideEditorPanel
import moe.flinty.yomark.ui.components.EditorBottomBar
import moe.flinty.yomark.ui.components.MinSliderWidth
import moe.flinty.yomark.ui.components.ToolGroup
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 样式的调节面板（spec §7.5 修订）：手机上从底栏长出来的那个、宽屏上从方块长出来占列表位置的那个。面板只发出新的样子，
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
        var collapsed = 0
        val picks = mutableListOf<ColorTarget?>()
        override fun onLookChange(look: MaskLook, inProgress: Boolean) { looks += look to inProgress }
        override fun onLookChangeFinished() { finished++ }
        override fun onReset() { resets++ }
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

    /** 宽屏的面板（从方块长出来的，见 WideEditorPanel）不盖画布，取色时还在：再点一下吸管就收起吸管，面板一直开着。 */
    @Test fun theEyedropperStartsAndStopsPickingInTheWidePanel() {
        val actions = Recorder()
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { WideEditorPanel(atSide = true, styleBar = model, styleActions = actions, list = null) } } }
        compose.onNodeWithContentDescription("从图上取色").performClick()
        model = model.copy(colorPick = ColorTarget.SOLID)
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.onNodeWithContentDescription("从图上取色").performClick()
        assertThat(actions.picks).containsExactly(ColorTarget.SOLID, null).inOrder()
        assertThat(actions.collapsed).isEqualTo(0)
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

    /** 面板开着时点它外面（画布那一块）：和点面板右上角的箭头一样，只收面板，这一组还展开着。 */
    @Test fun tappingOutsideThePanelFoldsJustThePanel() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onRoot().performTouchInput { click(Offset(centerX, 10f)) }
        assertThat(actions.collapsed).isEqualTo(1)
        assertThat(toolGroups).isEmpty()
    }

    /** 点在面板上，没有控件的地方也不收起。 */
    @Test fun tappingThePanelItselfKeepsItOpen() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID), actions)
        compose.onNodeWithText("颜色").performClick()
        assertThat(actions.collapsed).isEqualTo(0)
        assertThat(toolGroups).isEmpty()
    }

    /** 吸管等着取色时面板缩回去了，点的是图：不收起。 */
    @Test fun whileTheEyedropperWaitsTappingTheImageDoesNotFold() {
        val actions = Recorder()
        show(open(MaskStyle.SOLID).copy(colorPick = ColorTarget.SOLID), actions)
        compose.onRoot().performTouchInput { click(Offset(centerX, 10f)) }
        assertThat(actions.collapsed).isEqualTo(0)
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

    // ---------- 文本 ----------

    private class TextRecorder : StyleBarActions {
        val texts = mutableListOf<String>()
        val looks = mutableListOf<MaskLook>()
        var focusTaken = 0
        override fun onTextChange(text: String) { texts += text }
        override fun onTextFocusTaken() { focusTaken++ }
        override fun onLookChange(look: MaskLook, inProgress: Boolean) { looks += look }
    }

    /** 文本的面板：最上面是写的字，下面是颜色、字号、底色。 */
    @Test fun theTextPanelHoldsTheTextAColorASizeAndABackground() {
        val actions = TextRecorder()
        show(open(MaskStyle.TEXT).copy(text = "签收人", textKey = "t1"), actions)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("签收人").assertIsDisplayed()
        compose.onNodeWithContentDescription("红色").assertIsSelected()
        compose.onNodeWithContentDescription("字号").assertIsDisplayed()
        compose.onNodeWithText("底色").performClick()
        assertThat(actions.looks.single().options.textBackground).isTrue()
    }

    @Test fun typingInTheTextFieldReportsEveryChange() {
        val actions = TextRecorder()
        show(open(MaskStyle.TEXT).copy(textKey = "t1"), actions)
        compose.onNode(hasSetTextAction()).performTextInput("Hi")
        assertThat(actions.texts.last()).isEqualTo("Hi")
    }

    /** 点了图上一处要写字、要改选中的文本：输入框拿焦点，拿到了说一声，收起再打开不会又抢一次。 */
    @Test fun theTextFieldTakesFocusWhenAsked() {
        val actions = TextRecorder()
        show(open(MaskStyle.TEXT).copy(textKey = "t1", textFocus = true), actions)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        assertThat(actions.focusTaken).isEqualTo(1)
    }

    /** 手机上打字时输入法占掉下半截：面板只留输入框，看得到字落在图上。 */
    @Test fun whileTypingOnlyTheTextFieldShows() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column { StylePanelContent(open(MaskStyle.TEXT), TextRecorder(), compactWhileTyping = true, imeVisible = true) }
                }
            }
        }
        compose.onNodeWithContentDescription("字号").assertIsDisplayed()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNodeWithContentDescription("字号").assertDoesNotExist()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
    }

    /** 撤销、重做把图上的字改了：打着字的输入框也换成图上的，接着打不会把撤掉的又打回来。 */
    @Test fun theTextFieldFollowsTheImageEvenWhileTyping() {
        var model by mutableStateOf(open(MaskStyle.TEXT).copy(text = "hello", textKey = "t1"))
        val actions = TextRecorder()
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, actions) } } }
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        model = model.copy(text = "hi")
        compose.onNodeWithText("hi").assertIsDisplayed()
        compose.onNodeWithText("hello").assertDoesNotExist()
    }

    /** 只写一行：粘贴进来的换行在输入框里就换成空格，和图上的一样。 */
    @Test fun aPastedLineBreakBecomesASpaceInTheField() {
        val actions = TextRecorder()
        show(open(MaskStyle.TEXT).copy(textKey = "t1"), actions)
        compose.onNode(hasSetTextAction()).performTextInput("a\nb")
        assertThat(actions.texts.last()).isEqualTo("a b")
    }

    /** 改已经写好的字：光标在最后，接着打是接在后面，不是插到最前面。 */
    @Test fun editingATextTypesAfterWhatIsThere() {
        val actions = TextRecorder()
        show(open(MaskStyle.TEXT).copy(text = "Hi", textKey = "t1", textFocus = true), actions)
        compose.onNode(hasSetTextAction()).assertIsFocused().performTextInput("!")
        assertThat(actions.texts.last()).isEqualTo("Hi!")
    }

    /** 画笔的画法有三种：框选、手绘、箭头。马克笔只有前两种。 */
    @Test fun thePenCanDrawAnArrowButTheMarkerCannot() {
        val actions = Recorder()
        show(open(MaskStyle.PEN), actions)
        compose.onNodeWithText("箭头").performClick()
        assertThat(actions.looks.single().first.options.penShape).isEqualTo(StrokeShape.ARROW)
    }

    @Test fun theMarkerHasNoArrow() {
        show(open(MaskStyle.MARKER))
        compose.onNodeWithText("手绘").assertIsDisplayed()
        compose.onNodeWithText("箭头").assertDoesNotExist()
    }

    /**
     * 窄屏上的英文：三段放不下图标加名字时先让开图标，名字也写不全就只画图标、名字给读屏念。
     * 哪一种都不把名字截成「Freeha」。
     */
    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 量字是假的
    fun narrowScreensNeverCutAShapeName() {
        show(open(MaskStyle.PEN))
        listOf("Box", "Freehand", "Arrow").forEach { name ->
            if (compose.onAllNodesWithText(name).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithContentDescription(name).assertIsDisplayed()
                return@forEach
            }
            val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithText(name).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            // 单行加省略号时 hasVisualOverflow 不准（排版宽取的是最大宽），直接看截没截、整个名字放不放得下
            val r = results.single()
            assertThat(r.isLineEllipsized(0)).isFalse()
            assertThat(r.multiParagraph.intrinsics.maxIntrinsicWidth).isAtMost(r.layoutInput.constraints.maxWidth.toFloat())
        }
    }

    /** 手机竖着拿时中文三段都放得下图标加名字。 */
    @Test fun theShapeChoiceShowsNamesOnAPhone() {
        show(open(MaskStyle.PEN))
        listOf("框选", "手绘", "箭头").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    /**
     * 档位的名字写在滑条右边，不折成「Mediu」「m」：窄屏上英文最长的「Fairly coarse」也一行写得下。
     * 那一格按最长的那一档留，拖到哪一档滑条都一样长。
     */
    @Test @Config(qualifiers = "en-w320dp-h640dp-xhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)   // LEGACY 量字是假的
    fun everyLevelNameFitsBesideTheSliderOnANarrowPhone() = assertBeside(everyLevel(ENGLISH_LEVELS))

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chineseLevelNamesFitBesideTheSlider() = assertBeside(everyLevel(CHINESE_LEVELS))

    /** 中文的档位一两个字，字号调到两倍也还在滑条右边的一行里（原先那一格只有 48 dp，「适中」折成两行）。 */
    @Test @Config(fontScale = 2f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chineseLevelNamesFitBesideTheSliderAtDoubleFontSize() = assertBeside(everyLevel(CHINESE_LEVELS))

    /**
     * 窄屏上英文的字号调到两倍：「Fairly coarse」放在滑条右边的话滑条只剩一小截，挪到名字那一行的右头，
     * 滑条独占下面一行。照样一行写得下。
     */
    @Test @Config(qualifiers = "en-w360dp-h800dp-xxhdpi", fontScale = 2f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aDoubledFontOnANarrowPhoneMovesTheLevelAboveTheSlider() {
        everyLevel(ENGLISH_LEVELS).forEach {
            assertThat(it.name.bottom).isAtMost(it.track.top)
            assertThat(it.label.bottom).isAtMost(it.track.top)
            // 滑条有名字到档位名字那一整行宽
            assertThat(it.track.width).isAtLeast(it.name.right - it.label.left)
        }
    }

    /** 马赛克、模糊各有哪几档（和 StyleBar 里 PIXEL_LEVELS、BLUR_LEVELS 一个顺序），[slider] 是滑条的名字。 */
    private class Levels(val style: MaskStyle, val slider: String, val names: List<String>)

    /**
     * 拖到某一档时，滑条的名字、滑条、那一档的名字在哪儿。
     * 滑条是它给读屏的框：Material 给它左右各多加了一截（IncreaseHorizontalSemanticsBounds），比画出来的宽，横向的位置不拿它比。
     */
    private class Seen(val style: MaskStyle, val label: DpRect, val track: DpRect, val name: DpRect)

    /** 马赛克、模糊各拖遍每一档：每一档的名字都一行写得下、没截。 */
    private fun everyLevel(levels: List<Levels>): List<Seen> {
        var model by mutableStateOf(open(MaskStyle.PIXELATE))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, Recorder()) } } }
        return levels.flatMap { l ->
            l.names.mapIndexed { i, name ->
                model = open(l.style, levelOptions(l.style, i, l.names.size))
                Seen(
                    l.style,
                    label = compose.onNodeWithText(l.slider).getUnclippedBoundsInRoot(),
                    track = compose.onNodeWithContentDescription(l.slider).getUnclippedBoundsInRoot(),
                    name = wholeOnOneLine(name),
                )
            }
        }
    }

    /** 第 [i] 档（共 [count] 档）：马赛克从最细往粗数，模糊从最轻往重数。 */
    private fun levelOptions(style: MaskStyle, i: Int, count: Int): MaskOptions {
        val blur = MaskOptions.BLUR_RATIO_RANGE
        return if (style == MaskStyle.PIXELATE) MaskOptions(pixelBlockDivisor = MaskOptions.PIXEL_DIVISOR_RANGE.last - i)
        else MaskOptions(blurRadiusRatio = blur.start + (blur.endInclusive - blur.start) * i / (count - 1))
    }

    /** [text] 写在一行里，整个放得下。 */
    private fun wholeOnOneLine(text: String): DpRect {
        val results = mutableListOf<TextLayoutResult>()
        val node = compose.onNodeWithText(text).assertIsDisplayed()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        val r = results.single()
        assertThat(r.lineCount).isEqualTo(1)
        // 只许写一行，写不下是截掉而不是折行：直接看整个名字放不放得下
        assertThat(r.multiParagraph.intrinsics.maxIntrinsicWidth).isAtMost(r.layoutInput.constraints.maxWidth.toFloat())
        return node.getUnclippedBoundsInRoot()
    }

    /** 档位的名字和滑条同一行，在它右边，中间留给滑条的至少 MinSliderWidth；同一条滑条拖到哪一档都一样长。 */
    private fun assertBeside(seen: List<Seen>) {
        seen.forEach {
            assertThat(it.name.top).isLessThan(it.track.bottom)
            assertThat(it.name.bottom).isGreaterThan(it.track.top)
            assertThat(it.label.top).isLessThan(it.track.bottom)
            assertThat(it.label.bottom).isGreaterThan(it.track.top)
            assertThat(it.name.left - it.label.right).isAtLeast(MinSliderWidth)
        }
        seen.groupBy { it.style }.values.forEach { same ->
            assertThat(same.map { it.name.left }.toSet()).hasSize(1)
            assertThat(same.map { it.track.width }.toSet()).hasSize(1)
        }
    }

    // ---------- 滑条的名字 ----------

    /**
     * 滑条的名字在左边那一列一行写全。原先那一列固定 72 dp，字号调大后「Thickness」「Brightness」折成两行；
     * 现在按这一样面板里最长的名字留，自定义颜色的三条也算上，滑条的头照样对齐。
     */
    @Test @Config(qualifiers = "en-w412dp-h915dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun englishSliderNamesStayWhole() = everyPanelsNames(ENGLISH_SLIDERS, "Custom color")

    @Test @Config(qualifiers = "en-w412dp-h915dp-xxhdpi", fontScale = 1.3f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun englishSliderNamesStayWholeAtALargerFont() = everyPanelsNames(ENGLISH_SLIDERS, "Custom color")

    @Test @Config(qualifiers = "en-w412dp-h915dp-xxhdpi", fontScale = 2f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun englishSliderNamesStayWholeAtDoubleFontSize() = everyPanelsNames(ENGLISH_SLIDERS, "Custom color")

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chineseSliderNamesStayWhole() = everyPanelsNames(CHINESE_SLIDERS, "自定义颜色")

    @Test @Config(fontScale = 1.3f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chineseSliderNamesStayWholeAtALargerFont() = everyPanelsNames(CHINESE_SLIDERS, "自定义颜色")

    @Test @Config(fontScale = 2f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun chineseSliderNamesStayWholeAtDoubleFontSize() = everyPanelsNames(CHINESE_SLIDERS, "自定义颜色")

    /**
     * 宽屏右边那一栏最窄（320 dp）、字号两倍：马克笔的滑条放了名字和值只剩一小截，换成上下两行，
     * 自定义颜色的三条也跟着换，滑条的头还是对齐的。
     */
    @Test @Config(qualifiers = "en-w900dp-h600dp-xhdpi", fontScale = 2f) @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun aDoubledFontStacksEverySliderOfANarrowWidePanel() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp)) { StylePanel(open(MaskStyle.MARKER, MaskOptions(markerShape = StrokeShape.FREEHAND)), Recorder()) }
                }
            }
        }
        compose.onNodeWithContentDescription("Custom color").performClick()
        val marker = ENGLISH_SLIDERS.single { it.style == MaskStyle.MARKER }
        assertThat(compose.assertSliderNamesFit(marker.names)).isFalse()
    }

    /** 一样面板里的滑条有哪几条（名字），[customColor] = 有自定义颜色那三条，要点开。 */
    private class Sliders(val style: MaskStyle, val names: List<String>, val customColor: Boolean = true)

    /** 挨个打开每一样的面板（马克笔是手绘的，有粗细），有颜色的点开自定义，看滑条的名字（见 assertSliderNamesFit）。 */
    private fun everyPanelsNames(panels: List<Sliders>, customColor: String) {
        var model by mutableStateOf(open(MaskStyle.SOLID))
        compose.setContent { MaterialTheme { Surface { PhoneBar(model, Recorder()) } } }
        panels.forEach { p ->
            model = open(p.style, MaskOptions(markerShape = StrokeShape.FREEHAND))
            if (p.customColor) compose.onNodeWithContentDescription(customColor).performClick()
            compose.assertSliderNamesFit(p.names)
        }
    }

    private companion object {
        val ENGLISH_LEVELS = listOf(
            Levels(MaskStyle.PIXELATE, "Grain", listOf("Fine", "Fairly fine", "Medium", "Fairly coarse", "Coarse", "Very coarse")),
            Levels(MaskStyle.BLUR, "Strength", listOf("Light", "Fairly light", "Medium", "Fairly heavy", "Heavy")),
        )
        val CHINESE_LEVELS = listOf(
            Levels(MaskStyle.PIXELATE, "颗粒", listOf("细", "较细", "适中", "较粗", "粗", "很粗")),
            Levels(MaskStyle.BLUR, "强度", listOf("轻", "较轻", "适中", "较重", "重")),
        )

        private val ENGLISH_HSV = listOf("Hue", "Saturation", "Brightness")
        val ENGLISH_SLIDERS = listOf(
            Sliders(MaskStyle.SOLID, ENGLISH_HSV),
            Sliders(MaskStyle.PIXELATE, listOf("Grain"), customColor = false),
            Sliders(MaskStyle.BLUR, listOf("Strength"), customColor = false),
            Sliders(MaskStyle.MARKER, listOf("Opacity", "Thickness") + ENGLISH_HSV),
            Sliders(MaskStyle.PEN, listOf("Thickness") + ENGLISH_HSV),
            Sliders(MaskStyle.TEXT, listOf("Size") + ENGLISH_HSV),
        )
        private val CHINESE_HSV = listOf("色相", "饱和度", "亮度")
        val CHINESE_SLIDERS = listOf(
            Sliders(MaskStyle.SOLID, CHINESE_HSV),
            Sliders(MaskStyle.PIXELATE, listOf("颗粒"), customColor = false),
            Sliders(MaskStyle.BLUR, listOf("强度"), customColor = false),
            Sliders(MaskStyle.MARKER, listOf("浓淡", "粗细") + CHINESE_HSV),
            Sliders(MaskStyle.PEN, listOf("粗细") + CHINESE_HSV),
            Sliders(MaskStyle.TEXT, listOf("字号") + CHINESE_HSV),
        )
    }
}
