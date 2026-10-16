package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.view.View
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.ui.bigbang.BigBangPage
import moe.flinty.yomark.ui.components.BigBangPanelModel
import moe.flinty.yomark.ui.components.EditorBottomBar
import moe.flinty.yomark.ui.components.PanelTop
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
 * 手机底栏的胶囊和从它长出来的调节面板，看画出来的样子：长出来、缩回去的半路上是同一块胶囊在变大变小，
 * 工具那一行的上沿没有一道自己的边，面板里也没有它多出来的一层投影；开着面板收起这一组，面板跟着胶囊收窄。
 * 读屏看不出这些，真的画一遍取像素。面板里的东西怎么用见 StyleBarTest，工具那一行见 EditorToolbarTest。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class EditorBottomBarTest {

    @get:Rule val compose = createComposeRule()

    private var group by mutableStateOf<ToolGroup?>(ToolGroup.REDACT)
    private var model by mutableStateOf(StyleBarModel(MaskLook()))
    private lateinit var view: View

    /** 胶囊的底色。 */
    private var fill = 0

    /** 工具那一行的上沿，像素。 */
    private var rowTop = 0

    /** 摆出底栏，量好工具那一行在哪儿，再长出面板、等它停稳。 */
    private fun showWithThePanelOpen() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    view = LocalView.current
                    fill = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                    EditorBottomBar(
                        toolGroup = group,
                        onToolGroup = {},
                        styleBar = model,
                        styleActions = object : StyleBarActions {},
                        compare = remember { MutableInteractionSource() },
                        canCompare = false,
                    )
                }
            }
        }
        // 展开后的一项工具和这一行一样高
        rowTop = px(compose.onNodeWithText("色块").getUnclippedBoundsInRoot().top)
        model = model.copy(panelOpen = true)
        compose.waitForIdle()
    }

    /** 停住时钟，[change] 之后走到收起的半路：面板上的东西已经淡没了，胶囊还高出工具那一行一截。 */
    private fun halfwayAfter(change: () -> Unit): Bitmap {
        compose.mainClock.autoAdvance = false
        change()
        // 时钟停着也先把这一下组合进去，不然推多少帧画出来的都还是改之前的样子
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(HALFWAY_MS)
        return compose.runOnUiThread {
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
    }

    /** 收起面板的半路上：工具那一行的上沿没有一道线，它上面也没有一层影子，都是胶囊的底色。 */
    @Test fun foldingThePanelShrinksOneCapsule() {
        showWithThePanelOpen()
        val shot = halfwayAfter { model = model.copy(panelOpen = false) }
        for (x in listOf(shot.width / 3, shot.width / 2, shot.width * 2 / 3)) {
            assertThat(hex(shot.getPixel(x, rowTop + 1))).isEqualTo(hex(fill))
            assertThat(hex(shot.getPixel(x, rowTop - px(6.dp)))).isEqualTo(hex(fill))
        }
    }

    /** 开着面板点返回收起这一组：胶囊一边往下缩一边收窄，面板跟着收窄，不是满宽的一块缩到最后一下子没了。 */
    @Test fun foldingTheGroupNarrowsThePanelWithTheCapsule() {
        showWithThePanelOpen()
        val shot = halfwayAfter {
            group = null
            model = model.copy(panelOpen = false)
        }
        val y = rowTop - px(6.dp)
        assertThat(hex(shot.getPixel(shot.width / 2, y))).isEqualTo(hex(fill))
        // 面板满宽时离右边 12 dp
        assertThat(hex(shot.getPixel(shot.width - px(18.dp), y))).isNotEqualTo(hex(fill))
    }

    // ---------- 大爆炸：面板从胶囊长出来，画布跟着让 ----------

    /** 「收货人张三」一行，一字一个 element（和 PP-OCR 一样）。 */
    private val page = run {
        val text = "收货人张三"
        val els = text.indices.map { i ->
            TextElement(Quad.fromRect(RectF(40f + i * 20f, 100f, 60f + i * 20f, 120f)), text[i].toString(), i..i)
        }
        BigBangPage.of(listOf(TextLine(Quad.fromRect(RectF(40f, 100f, 140f, 120f)), text, 0.95f, els)))
    }

    /**
     * 打开大爆炸：面板和别的面板一样从胶囊一点点长出来，画布跟着胶囊的上沿往上让（选字时看得到图上选中的是哪几块）；
     * 收起时倒过来，画布回到原处。点面板外面不收起它：点图上是点字。
     */
    @Test fun bigBangGrowsOutOfTheCapsuleAndPushesTheCanvas() {
        var canvasEnd = 0
        var bang by mutableStateOf<BigBangPanelModel?>(null)
        val folded = mutableListOf<ToolGroup?>()
        compose.setContent {
            MaterialTheme {
                Surface {
                    Layout(content = {
                        EditorBottomBar(
                            toolGroup = ToolGroup.TOOLS,
                            onToolGroup = { folded += it },
                            styleBar = model,
                            styleActions = object : StyleBarActions {},
                            compare = remember { MutableInteractionSource() },
                            canCompare = false,
                            aiReview = AiReview.Ready,
                            bigBangReady = true,
                            bigBang = bang,
                        )
                    }) { measurables, constraints ->
                        val bar = measurables.single().measure(constraints)
                        canvasEnd = bar[PanelTop]
                        layout(bar.width, bar.height) { bar.place(0, 0) }
                    }
                }
            }
        }
        val resting = canvasEnd
        compose.onNodeWithText("已选 0 字").assertDoesNotExist()

        compose.mainClock.autoAdvance = false
        bang = BigBangPanelModel(BigBangState(page), emptySet())
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(HALFWAY_MS)
        val halfway = canvasEnd
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        val open = canvasEnd
        assertThat(halfway).isLessThan(resting)
        assertThat(open).isLessThan(halfway)
        compose.onNodeWithText("已选 0 字").assertIsDisplayed()
        // 字块整块自己画，读屏念的是整页的字
        compose.onNodeWithContentDescription("收货人张三").assertIsDisplayed()

        compose.onRoot().performTouchInput { click(Offset(centerX, 10f)) }
        assertThat(folded).isEmpty()

        compose.mainClock.autoAdvance = false
        bang = null
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(HALFWAY_MS)
        assertThat(canvasEnd).isGreaterThan(open)
        assertThat(canvasEnd).isLessThan(resting)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertThat(canvasEnd).isEqualTo(resting)
        compose.onNodeWithText("已选 0 字").assertDoesNotExist()
    }

    /**
     * 用不了 AI 时大爆炸占住工具那一行（ToolGroup.BIG_BANG）：面板占到胶囊最底下，底下没有「工具」那一行，
     * 比带着「工具」那一行时整块低一行，图让得少一行。
     */
    @Test fun withoutAiTheBigBangPanelTakesTheWholeCapsule() {
        var canvasEnd = 0
        var row by mutableStateOf<ToolGroup?>(ToolGroup.TOOLS)
        var ai by mutableStateOf<AiReview>(AiReview.Ready)
        var bang by mutableStateOf<BigBangPanelModel?>(null)
        val open = BigBangPanelModel(BigBangState(page), emptySet())
        compose.setContent {
            MaterialTheme {
                Surface {
                    Layout(content = {
                        EditorBottomBar(
                            toolGroup = row,
                            onToolGroup = {},
                            styleBar = model,
                            styleActions = object : StyleBarActions {},
                            compare = remember { MutableInteractionSource() },
                            canCompare = false,
                            aiReview = ai,
                            bigBangReady = true,
                            bigBang = bang,
                        )
                    }) { measurables, constraints ->
                        val bar = measurables.single().measure(constraints)
                        canvasEnd = bar[PanelTop]
                        layout(bar.width, bar.height) { bar.place(0, 0) }
                    }
                }
            }
        }
        // 带着「工具」那一行打开：标题一个、底下工具里一个
        bang = open
        compose.waitForIdle()
        compose.onAllNodesWithText("大爆炸").assertCountEquals(2)
        val withRow = canvasEnd
        bang = null
        row = null
        compose.waitForIdle()

        // 用不了 AI：外面让大爆炸占住这一行再打开（见 EditorScreen）
        ai = AiReview.Hidden
        row = ToolGroup.BIG_BANG
        bang = open
        compose.waitForIdle()
        compose.onAllNodesWithText("大爆炸").assertCountEquals(1)
        compose.onNodeWithText("完成").assertIsDisplayed()
        assertThat(canvasEnd - withRow).isAtLeast(px(48.dp))
    }

    private fun px(dp: Dp) = with(compose.density) { dp.roundToPx() }

    private fun hex(argb: Int) = "%08X".format(argb)

    private companion object {
        /** 收起动画（350 ms）的这一刻，面板长出来的那一截还剩两成左右。 */
        const val HALFWAY_MS = 160L
    }
}
