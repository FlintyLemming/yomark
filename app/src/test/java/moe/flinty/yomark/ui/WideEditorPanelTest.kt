package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.ui.bigbang.BigBangPage
import moe.flinty.yomark.ui.components.BigBangPanelModel
import moe.flinty.yomark.ui.components.PurposePanelModel
import moe.flinty.yomark.ui.components.StyleBarActions
import moe.flinty.yomark.ui.components.StyleBarModel
import moe.flinty.yomark.ui.components.WideEditorPanel
import moe.flinty.yomark.ui.components.morphRect
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 宽屏画布旁边的那一块（2026-10-16）：所有功能平铺成方块，和手机底栏里的一样多；面板从点的那一块长出来、缩回那一块，
 * 落在列表的位置（手机横着拿没有列表时盖满这一栏，大爆炸盖满整块），落到的那一块点不着。
 * 面板里的东西怎么用见 StyleBarTest、PurposePanelTest，用真的 ViewModel 走一遍见 EditorScreenLayoutTest。
 */
@RunWith(RobolectricTestRunner::class)
// 真的画一遍、真的量字
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class WideEditorPanelTest {

    @get:Rule val compose = createComposeRule()

    private class Recorder : StyleBarActions {
        val clicked = mutableListOf<MaskStyle>()
        override fun onStyleClick(style: MaskStyle) { clicked += style }
    }

    private var model by mutableStateOf(StyleBarModel(MaskLook()))
    private var purpose by mutableStateOf(PurposePanelModel(text = "仅供办理签证使用"))
    private var ai by mutableStateOf<AiReview>(AiReview.Hidden)
    private var bang by mutableStateOf<BigBangPanelModel?>(null)
    private var canCompare by mutableStateOf(true)
    private val compare = MutableInteractionSource()
    private val compareEvents = mutableListOf<Interaction>()
    private var aiClicks = 0
    private var bigBangClicks = 0
    private var listClicks = 0
    private lateinit var view: View
    private var fill = 0

    /** 这一块量出来多高（竖着拿时它跟着面板长高），像素。 */
    private var height = 0

    private var aiOffered by mutableStateOf(false)

    private fun show(atSide: Boolean = true, listed: Boolean = true, batch: Boolean = false, actions: StyleBarActions = Recorder()) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    view = LocalView.current
                    fill = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                    Layout(content = {
                        WideEditorPanel(
                            atSide = atSide,
                            styleBar = model,
                            styleActions = actions,
                            list = if (listed) ({ Box(it.testTag("list").clickable { listClicks++ }) { Text("框出 2 处") } }) else null,
                            purpose = purpose,
                            aiReview = ai,
                            aiOffered = aiOffered,
                            onAiReview = { aiClicks++ },
                            bigBangReady = true,
                            onBigBang = { bigBangClicks++ },
                            bigBang = bang,
                            compare = compare,
                            canCompare = canCompare,
                            filmstrip = if (batch) ({ Box(it.height(40.dp).testTag("film")) }) else null,
                        )
                    }) { measurables, constraints ->
                        val panel = measurables.single().measure(constraints.copy(minHeight = 0))
                        height = panel.height
                        layout(panel.width, panel.height) { panel.place(0, 0) }
                    }
                    LaunchedEffect(compare) { compare.interactions.collect { compareEvents += it } }
                }
            }
        }
    }

    private fun px(dp: Dp) = with(compose.density) { dp.roundToPx() }
    private fun hex(argb: Int) = "%08X".format(argb)
    private fun draw(): Bitmap = compose.runOnUiThread {
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    private val compareTile get() = compose.onNodeWithContentDescription("按住对比")

    // ---------- 方块 ----------

    /** 手机底栏里的功能这里都有，平铺着，三个一行：色块、马赛克、模糊，马克笔、画笔、文本，水印、AI、大爆炸，对比。 */
    @Test fun everyFunctionIsATileThreeToARow() {
        ai = AiReview.Ready
        show()
        val rows = listOf(
            listOf("色块", "马赛克", "模糊"),
            listOf("马克笔", "画笔", "文本"),
            listOf("水印", "AI 增强识别", "大爆炸"),
        ).map { row -> row.map { compose.onNodeWithText(it).assertIsDisplayed().getUnclippedBoundsInRoot() } }
        rows.forEach { row ->
            row.zipWithNext().forEach { (a, b) ->
                assertThat(b.top).isEqualTo(a.top)
                assertThat(b.left).isGreaterThan(a.right)
            }
        }
        rows.zipWithNext().forEach { (upper, lower) -> assertThat(lower.first().top).isGreaterThan(upper.first().bottom) }
        val cmp = compareTile.assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(cmp.top).isGreaterThan(rows.last().first().bottom)
        assertThat(cmp.left).isEqualTo(rows.first().first().left)
        // 不收进几组：没有「打码」「编辑」「工具」
        listOf("打码", "编辑", "工具").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    /** 用不了 AI 时没有 AI 那一块，正好三行。 */
    @Test fun withoutAiThereIsNoAiTile() {
        show()
        compose.onNodeWithText("AI 增强识别").assertDoesNotExist()
        val bigBang = compose.onNodeWithText("大爆炸").getUnclippedBoundsInRoot()
        assertThat(compareTile.getUnclippedBoundsInRoot().top).isEqualTo(bigBang.top)
    }

    /** 方块是手机底栏圆钮的那种底子：里面是胶囊的底色，两块之间露着底下的颜色。 */
    @Test fun eachTileIsItsOwnFloatingSurface() {
        show()
        val mosaic = compose.onNodeWithText("马赛克").getUnclippedBoundsInRoot()
        val blur = compose.onNodeWithText("模糊").getUnclippedBoundsInRoot()
        val shot = draw()
        val y = px(mosaic.top) + px(6.dp)
        assertThat(hex(shot.getPixel(px((mosaic.left + mosaic.right) / 2), y))).isEqualTo(hex(fill))
        assertThat(hex(shot.getPixel(px((mosaic.right + blur.left) / 2), px((mosaic.top + mosaic.bottom) / 2)))).isNotEqualTo(hex(fill))
    }

    /** 选中的那一块名字加粗、着主题色，和手机上展开后选中的那一项一样；点一块报出它的样式。 */
    @Test fun theChosenTileStandsOutAndTappingReportsTheStyle() {
        val actions = Recorder()
        show(actions = actions)
        compose.onNodeWithText("色块").assertIsSelected()
        compose.onNodeWithText("马赛克").assertIsNotSelected()
        assertThat(weight("色块")).isEqualTo(FontWeight.Bold)
        assertThat(weight("马赛克")).isNotEqualTo(FontWeight.Bold)
        compose.onNodeWithText("马赛克").performClick()
        assertThat(actions.clicked).containsExactly(MaskStyle.PIXELATE)
    }

    private fun weight(name: String): FontWeight? {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(name, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single().layoutInput.style.fontWeight
    }

    /** 水印那一块：设了（开了「应用」、写了字）才念「已开启」；面板开着时选中的是它（名字加粗），样式都不算选中。 */
    @Test fun theWatermarkTileSaysWhenItIsOnAndIsPickedWhileItsPanelIsOpen() {
        show()
        compose.onNodeWithText("水印").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        assertThat(weight("水印")).isNotEqualTo(FontWeight.Bold)
        purpose = purpose.copy(on = true)
        compose.onNodeWithText("水印").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已开启"))
        purpose = purpose.copy(text = " ")
        compose.onNodeWithText("水印").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        purpose = purpose.copy(open = true)
        compose.waitForIdle()
        compose.onNodeWithText("色块").assertIsNotSelected()
        assertThat(weight("水印")).isEqualTo(FontWeight.Bold)
    }

    /**
     * 用得了 AI 的设备上 AI 那一块一直留着（2026-10-16）：这张图还没识别完时灰着、点不动，方块不跟着识别一会儿三行一会儿四行。
     */
    @Test fun theAiTileKeepsItsPlaceWhileTheImageIsStillBeingRecognised() {
        aiOffered = true
        show()
        val compareAt = compareTile.getUnclippedBoundsInRoot()
        compose.onNodeWithText("AI 增强识别").assertIsDisplayed().assertIsNotEnabled()
        ai = AiReview.Ready
        compose.onNodeWithText("AI 增强识别").assertIsEnabled()
        assertThat(compareTile.getUnclippedBoundsInRoot()).isEqualTo(compareAt)
    }

    // ---------- AI 增强识别 ----------

    @Test fun aiReviewSaysWhatItDoesAndRunsOnClick() {
        ai = AiReview.Ready
        show()
        compose.onNodeWithText("AI 增强识别").assertIsEnabled().performClick()
        assertThat(aiClicks).isEqualTo(1)
    }

    @Test fun aiReviewCannotBeTappedAgainWhileRunning() {
        ai = AiReview.Running
        show()
        compose.onNodeWithText("增强识别中…").assertIsNotEnabled()
    }

    /** 结果就写在这一块上：新发现几处、没有新发现，点不动；没跑成还能点，再试一次。 */
    @Test fun aiReviewShowsItsResultInPlace() {
        ai = AiReview.Done(found = 3)
        show()
        compose.onNodeWithText("新发现 3 处").assertIsDisplayed().assertIsNotEnabled()
        ai = AiReview.Done(found = 0)
        compose.onNodeWithText("没有新发现").assertIsDisplayed().assertIsNotEnabled()
        ai = AiReview.Failed
        compose.onNodeWithText("识别失败，重试").assertHasClickAction().assertIsEnabled().performClick()
        assertThat(aiClicks).isEqualTo(1)
    }

    /** 手机横着拿那一栏（320 dp 宽、三百多 dp 高）上「识别失败，重试」写不下一行，折成两行，「重试」不截掉。 */
    @Config(qualifiers = "zh-rCN-w860dp-h340dp-xxhdpi")
    @Test fun aLongAiResultWrapsInsteadOfLosingItsEnd() {
        ai = AiReview.Failed
        show(listed = false)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("识别失败，重试", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertThat(layouts.single().hasVisualOverflow).isFalse()
    }

    // ---------- 大爆炸、对比 ----------

    @Test fun bigBangOpensFromItsTile() {
        show()
        compose.onNodeWithText("大爆炸").assertIsEnabled().performClick()
        assertThat(bigBangClicks).isEqualTo(1)
    }

    @Test fun holdingComparePressesUntilLetGo() {
        show()
        compose.mainClock.autoAdvance = false
        compareTile.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(1_000)
        assertThat(compareEvents.last()).isInstanceOf(PressInteraction.Press::class.java)
        compareTile.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(100)
        assertThat(compareEvents.last()).isInstanceOf(PressInteraction.Release::class.java)
    }

    /** 点一下没比上：弹个提示说要按住，和手机上那颗圆钮一样。 */
    @Test fun tappingCompareSaysToHoldIt() {
        show()
        compose.mainClock.autoAdvance = false
        compareTile.performClick()
        compose.mainClock.advanceTimeBy(300)
        compose.onNodeWithText("按住对比原图").assertIsDisplayed()
    }

    @Test fun nothingToCompareGraysItOut() {
        canCompare = false
        show()
        compareTile.assertIsNotEnabled()
    }

    // ---------- 面板从方块长出来 ----------

    /** 面板的四条边从那一块方块走到落定的地方，左右跟着横着那一条、上下跟着竖着那一条。 */
    @Test fun thePanelMovesFromTheTileToWhereItLands() {
        val tile = IntRect(10, 10, 110, 80)
        val landed = IntRect(10, 120, 410, 520)
        assertThat(morphRect(tile, landed, 0f, 0f)).isEqualTo(tile)
        assertThat(morphRect(tile, landed, 1f, 1f)).isEqualTo(landed)
        // 收回去时竖着的先落回来：上下离方块近，左右还撑得开
        val folding = morphRect(tile, landed, 0.8f, 0.3f)
        assertThat(folding.right).isEqualTo(350)
        assertThat(folding.top).isEqualTo(43)
        assertThat(folding.bottom).isEqualTo(212)
    }

    /**
     * 点开样式面板：刚开始的几帧面板还贴着那一块（色块和马赛克之间那条缝先被盖上，列表那儿还没到），落定在列表的位置；
     * 收起后缩回那一块，那条缝又和原来一样。
     */
    @Test fun theStylePanelGrowsOutOfItsTileAndShrinksBackIntoIt() {
        show()
        val solid = compose.onNodeWithText("色块").getUnclippedBoundsInRoot()
        val mosaic = compose.onNodeWithText("马赛克").getUnclippedBoundsInRoot()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        val besideSolid = px((solid.right + mosaic.left) / 2) to px(solid.bottom) - px(6.dp)
        // 列表的顶上，面板落定后盖着这儿（「框出 2 处」在左边，取正中）
        val inTheList = px((list.left + list.right) / 2) to px(list.top) + px(24.dp)
        fun Bitmap.at(p: Pair<Int, Int>) = hex(getPixel(p.first, p.second))
        val before = draw()

        compose.mainClock.autoAdvance = false
        model = model.copy(panelOpen = true)
        compose.waitForIdle()
        // 那一块旁边的缝先变了（面板从那儿长出来；那一块自己的投影落在面板上，不是纯的底色），列表底下那儿还没变
        val early = (1..12).firstNotNullOfOrNull {
            compose.mainClock.advanceTimeByFrame()
            draw().takeIf { shot -> shot.at(besideSolid) != before.at(besideSolid) }
        }
        assertThat(early).isNotNull()
        assertThat(early!!.at(inTheList)).isEqualTo(before.at(inTheList))
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val reset = compose.onNodeWithText("恢复默认").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertThat(reset.top).isGreaterThan(compose.onNodeWithText("文本").getUnclippedBoundsInRoot().bottom)
        assertThat(draw().at(inTheList)).isEqualTo(hex(fill))   // 落定了：列表顶上是面板

        model = model.copy(panelOpen = false)
        compose.waitForIdle()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
        assertThat(draw().at(besideSolid)).isEqualTo(before.at(besideSolid))
    }

    /** 面板落在列表的位置：开着时列表淡掉、点不着（面板短，下面空着的那截也点不着），收起后列表回来。方块一直在。 */
    @Test fun theListUnderAnOpenPanelIsGoneAndUntouchable() {
        show()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        model = model.copy(look = MaskLook(MaskStyle.BLUR), panelOpen = true)   // 模糊只有一根滑条，面板矮
        compose.waitForIdle()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        compose.onNodeWithText("色块").assertIsDisplayed()
        compose.onRoot().performTouchInput { click(Offset(center.x / 2, (list.bottom - 10.dp).toPx())) }
        assertThat(listClicks).isEqualTo(0)

        model = model.copy(panelOpen = false)
        compose.waitForIdle()
        compose.onNodeWithText("框出 2 处").assertIsDisplayed()
        compose.onNodeWithTag("list").performClick()
        assertThat(listClicks).isEqualTo(1)
    }

    /** 手机横着拿没有列表：面板盖满这一栏，方块淡掉、点不着，收起后回来。 */
    @Test fun withoutAListThePanelCoversTheTiles() {
        show(listed = false)
        model = model.copy(panelOpen = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收起样式面板").assertIsDisplayed()
        compose.onNodeWithText("马克笔").assertDoesNotExist()
        model = model.copy(panelOpen = false)
        compose.waitForIdle()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
    }

    /** 大爆炸的面板盖满整块：方块、列表都点不着，开着时也不该再去点别的功能；字少也盖满，底下不留一截空着。 */
    @Test fun bigBangCoversTheTilesAndTheList() {
        show()
        val list = compose.onNodeWithTag("list").getUnclippedBoundsInRoot()
        bang = BigBangPanelModel(BigBangState(page), emptySet())
        compose.waitForIdle()
        compose.onNodeWithText("已选 0 字").assertIsDisplayed()
        compose.onNodeWithText("马克笔").assertDoesNotExist()
        compose.onNodeWithText("框出 2 处").assertDoesNotExist()
        // 只有一行字，字块那一截很矮；面板照样盖到这一栏的底
        assertThat(hex(draw().getPixel(px((list.left + list.right) / 2), px(list.bottom) - px(24.dp)))).isEqualTo(hex(fill))
        bang = null
        compose.waitForIdle()
        compose.onNodeWithText("已选 0 字").assertDoesNotExist()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
    }

    /**
     * 开着样式面板又打开大爆炸（点大爆炸那一块，或者长按图上的字）：样式面板先缩回色块那一块，缩到底再从大爆炸那一块长出来，
     * 不从列表那儿一下跳成盖满整块。
     */
    @Test fun switchingToBigBangFoldsTheStylePanelFirst() {
        model = model.copy(panelOpen = true)
        show()
        compose.onNodeWithText("恢复默认").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        model = model.copy(panelOpen = false)
        bang = BigBangPanelModel(BigBangState(page), emptySet())
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
        // 还在缩样式面板：大爆炸的面板还没出来，方块也还在
        compose.onNodeWithText("已选 0 字").assertDoesNotExist()
        compose.onNodeWithText("马克笔").assertExists()
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithText("已选 0 字").assertIsDisplayed()
        compose.onNodeWithText("恢复默认").assertDoesNotExist()
    }

    /** 开着样式面板换一种样式：落在同一个地方，就地换成新样式的面板，不先缩回去。 */
    @Test fun switchingStylesWithThePanelOpenSwapsInPlace() {
        model = model.copy(panelOpen = true)
        show()
        val header = compose.onNodeWithContentDescription("收起样式面板").getUnclippedBoundsInRoot()
        compose.mainClock.autoAdvance = false
        model = model.copy(look = MaskLook(MaskStyle.BLUR))
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        assertThat(compose.onNodeWithContentDescription("收起样式面板").getUnclippedBoundsInRoot()).isEqualTo(header)
        // 一个是那一块，一个是面板顶上换成的名字
        compose.onAllNodesWithText("模糊").assertCountEquals(2)
    }

    /**
     * 手机横着拿、矮（360 dp 宽的手机横过来，三百出头高），还在批量里、用得了 AI：三个一行放不下就四个一行，
     * 方块不压到胶片条底下，都在这一栏里。
     */
    @Config(qualifiers = "zh-rCN-w745dp-h252dp-xxhdpi")
    @Test fun aShortLandscapePhoneNeverPushesTilesUnderTheFilmstrip() {
        ai = AiReview.Ready
        show(listed = false, batch = true)
        val film = compose.onNodeWithTag("film").getUnclippedBoundsInRoot()
        val cmp = compareTile.getUnclippedBoundsInRoot()
        assertThat(cmp.bottom).isAtMost(film.top)
        assertThat(cmp.height).isAtLeast(48.dp)
        // 四个一行：马克笔挤上了第一行
        assertThat(compose.onNodeWithText("马克笔").getUnclippedBoundsInRoot().top)
            .isEqualTo(compose.onNodeWithText("色块").getUnclippedBoundsInRoot().top)
    }

    /**
     * 平板横着拿、弹着输入法这类矮的一栏：列表那一截落不下面板（不到 200 dp），样式、水印的面板就落满整一栏，盖住方块，
     * 不挤成一条缝。
     */
    @Config(qualifiers = "zh-rCN-w1280dp-h400dp-xhdpi")
    @Test fun aShortColumnLetsThePanelCoverTheTiles() {
        ai = AiReview.Ready
        show()
        model = model.copy(panelOpen = true)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收起样式面板").assertIsDisplayed()
        compose.onNodeWithText("马克笔").assertDoesNotExist()
        model = model.copy(panelOpen = false)
        compose.waitForIdle()
        compose.onNodeWithText("马克笔").assertIsDisplayed()
    }

    /** 阔折叠竖着拿、批量、用得了 AI：方块四行也不低于 48 dp，这一块跟着高一点，画布还留得够。 */
    @Config(qualifiers = "zh-rCN-w528dp-h848dp-xhdpi")
    @Test fun anUprightWideFoldKeepsTilesTallEnoughToTap() {
        ai = AiReview.Ready
        show(atSide = false, batch = true)
        assertThat(compareTile.getUnclippedBoundsInRoot().height).isAtLeast(48.dp)
        assertThat(compareTile.getUnclippedBoundsInRoot().bottom).isAtMost(compose.onNodeWithTag("film").getUnclippedBoundsInRoot().top)
        assertThat(height).isAtMost(px(848.dp - 200.dp))
    }

    /** 竖着拿：面板比这一块高时这一块往上长（画布跟着变矮），收起后缩回原来的高。 */
    @Config(qualifiers = "zh-rCN-w800dp-h1280dp-xhdpi")
    @Test fun onAnUprightScreenATallPanelGrowsTheBlock() {
        show(atSide = false)
        val resting = height
        purpose = purpose.copy(open = true)
        compose.waitForIdle()
        compose.onNodeWithText("用途水印").assertIsDisplayed()
        assertThat(height).isGreaterThan(resting)
        assertThat(height).isAtMost(px(520.dp))
        purpose = purpose.copy(open = false)
        compose.waitForIdle()
        assertThat(height).isEqualTo(resting)
    }

    /** 「收货人张三」一行，一字一个 element（和 PP-OCR 一样）。 */
    private val page = run {
        val text = "收货人张三"
        val els = text.indices.map { i ->
            TextElement(Quad.fromRect(RectF(40f + i * 20f, 100f, 60f + i * 20f, 120f)), text[i].toString(), i..i)
        }
        BigBangPage.of(listOf(TextLine(Quad.fromRect(RectF(40f, 100f, 140f, 120f)), text, 0.95f, els)))
    }
}
