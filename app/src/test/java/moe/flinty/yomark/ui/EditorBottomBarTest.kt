package moe.flinty.yomark.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.ui.components.EditorBottomBar
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

    private fun px(dp: Dp) = with(compose.density) { dp.roundToPx() }

    private fun hex(argb: Int) = "%08X".format(argb)

    private companion object {
        /** 收起动画（350 ms）的这一刻，面板长出来的那一截还剩两成左右。 */
        const val HALFWAY_MS = 160L
    }
}
