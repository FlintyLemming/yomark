package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/**
 * 手机底栏的工具那一行：收着时「打码」「编辑」两组，点一组展开它的工具，那一组变成返回。
 * 换样式、弹面板由 ViewModel 管（见 EditorViewModelTest），这里只看这一行发出什么。
 */
@RunWith(RobolectricTestRunner::class)
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
                    ) { Text("导出") }
                }
            }
        }
    }

    @Test fun collapsedItShowsTheTwoGroupsAndTheButtonsOnTheRight() {
        show()
        listOf("打码", "编辑", "导出").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("色块", "马克笔", "水印").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    /** 马克笔盖不住：用着它的时候亮的是「编辑」，不是「打码」。 */
    @Test fun theGroupHoldingTheBrushIsTheSelectedOne() {
        look = MaskLook(MaskStyle.PIXELATE)
        show()
        compose.onNodeWithText("打码").assertIsSelected()
        compose.onNodeWithText("编辑").assertIsNotSelected()
        look = MaskLook(MaskStyle.MARKER)
        compose.onNodeWithText("打码").assertIsNotSelected()
        compose.onNodeWithText("编辑").assertIsSelected()
    }

    @Test fun redactUnfoldsTheStylesThatCoverAndHidesTheRest() {
        show()
        compose.onNodeWithText("打码").performClick()
        assertThat(opened).containsExactly(ToolGroup.REDACT)
        listOf("色块", "马赛克", "模糊").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("色块").assertIsSelected()
        listOf("马克笔", "编辑", "导出").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("抹除").assertDoesNotExist()      // 并进了色块，是色块的近似色
    }

    @Test fun tappingAStyleReportsIt() {
        val actions = Recorder()
        show(actions = actions, initial = ToolGroup.REDACT)
        compose.onNodeWithText("马赛克").performClick()
        assertThat(actions.clicked).containsExactly(MaskStyle.PIXELATE)
        assertThat(opened).isEmpty()
    }

    /** 展开后那一组就是返回：点它收回去，两组和右边的按钮回来。 */
    @Test fun theOpenGroupIsTheWayBack() {
        show(initial = ToolGroup.REDACT)
        compose.onNodeWithText("打码").performClick()
        assertThat(opened).containsExactly(null)
        compose.onNodeWithText("色块").assertDoesNotExist()
        listOf("编辑", "导出").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
    }

    /** 编辑：马克笔、画笔（还没做，点不动）、水印（点开用途水印）。 */
    @Test fun editHoldsTheMarkerThePenAndTheWatermark() {
        val actions = Recorder()
        show(actions = actions)
        compose.onNodeWithText("编辑").performClick()
        compose.onNodeWithText("打码").assertDoesNotExist()
        compose.onNodeWithText("表情").assertDoesNotExist()      // 去掉了
        compose.onNodeWithText("画笔").assertIsNotEnabled()
        compose.onNodeWithText("马克笔").assertIsNotSelected().performClick()
        assertThat(actions.clicked).containsExactly(MaskStyle.MARKER)
        compose.onNodeWithText("水印").performClick()
        assertThat(purposeClicks).isEqualTo(1)
    }

    // ---------- AI 增强识别：和两组并排 ----------

    @Test fun aiReviewSitsBesideTheGroupsAndRunsOnClick() {
        ai = AiReview.Ready
        show()
        compose.onNodeWithText("AI 增强识别").assertIsEnabled().performClick()
        assertThat(aiClicks).isEqualTo(1)
    }

    @Test fun aiReviewIsNotOfferedWhereItCannotRun() {
        show()
        compose.onNodeWithText("AI 增强识别").assertDoesNotExist()
        compose.onNodeWithText("导出").assertIsDisplayed()
    }

    /** 跑着、跑完了都点不动，结果写在字上；没跑成是另一句话，还能点。 */
    @Test fun aiReviewShowsItsProgressAndResultInPlace() {
        ai = AiReview.Running
        show()
        compose.onNodeWithText("增强识别中…").assertIsNotEnabled()
        ai = AiReview.Done(found = 3)
        compose.onNodeWithText("新发现 3 处").assertIsDisplayed().assertIsNotEnabled()
        ai = AiReview.Done(found = 0)
        compose.onNodeWithText("没有新发现").assertIsDisplayed()
        ai = AiReview.Failed
        compose.onNodeWithText("识别失败，重试").assertIsEnabled().performClick()
        assertThat(aiClicks).isEqualTo(1)
    }

    @Test fun aiReviewStepsAsideWhileAGroupIsOpen() {
        ai = AiReview.Ready
        show(initial = ToolGroup.EDIT)
        compose.onNodeWithText("AI 增强识别").assertDoesNotExist()
    }

    @Test fun theWatermarkSaysWhenItIsOn() {
        show(purposeOn = true, initial = ToolGroup.EDIT)
        compose.onNodeWithText("水印").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已开启"))
    }
}
