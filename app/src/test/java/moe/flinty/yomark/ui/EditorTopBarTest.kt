package moe.flinty.yomark.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.R
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.ui.components.EditorTopBar
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 手机竖着拿时的顶栏：返回、撤销、重做、去水印、设置、导出。一行放不下「导出」两个字时（320 dp 宽，或者调大了显示大小），
 * 导出换成只有图标的按钮，别的都还在；再放不下才从最不要紧的起让出来。
 * 用真的 ViewModel 走一遍的见 EditorScreenLayoutTest。
 */
@RunWith(RobolectricTestRunner::class)
// 真的量字：放不放得下要按真的字宽算
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w320dp-h640dp-xhdpi")
class EditorTopBarTest {

    @get:Rule val compose = createComposeRule()

    private var exporting by mutableStateOf(false)

    /** EditorScreen 在手机竖着拿时传的那几样；[debug] 是开发者选项里打开了调试信息。 */
    private fun show(debug: Boolean = false, exportLabel: Int = R.string.export) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    EditorTopBar(
                        canUndo = true, canRedo = true, onUndo = {}, onRedo = {},
                        onPurpose = null, onSettings = {}, onNavigateUp = {},
                        onRemoveWatermark = {},
                        onDebug = if (debug) ({}) else null,
                        onExport = {},
                        exportLabel = exportLabel,
                        exporting = exporting,
                    )
                }
            }
        }
    }

    private fun button(description: String) = compose.onNodeWithContentDescription(description)

    /** 去水印的按钮，开源版上它叫「全功能免费版」。 */
    private val premium get() = button(
        ApplicationProvider.getApplicationContext<Context>().getString(if (Edition.isFree) R.string.free_edition else R.string.remove_watermark),
    )

    private fun SemanticsNodeInteraction.bounds() = getUnclippedBoundsInRoot()

    /** 从左往右一个挨一个，不叠在一起，也不出右边。 */
    private fun assertInARow(buttons: List<SemanticsNodeInteraction>) {
        val row = buttons.map { it.assertIsDisplayed().bounds() }
        row.zipWithNext().forEach { (left, right) -> assertThat(left.right).isAtMost(right.left) }
        assertThat(row.last().right).isAtMost(compose.onRoot().bounds().right)
    }

    @Test fun onA320dpScreenExportBecomesAnIconAndEveryButtonStays() {
        show()
        compose.onNodeWithText("Export").assertIsNotDisplayed()
        assertInARow(listOf("Back", "Undo", "Redo").map(::button) + premium + button("Settings") + button("Export"))
        assertThat(button("Export").bounds().width).isAtMost(48.dp)
    }

    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi")
    @Test fun inChineseToo() {
        show()
        compose.onNodeWithText("导出").assertIsNotDisplayed()
        assertInARow(listOf("返回", "撤销", "重做").map(::button) + premium + button("设置") + button("导出"))
    }

    /** 导出中：图标换成转圈，按钮点不动，读屏念「导出中…」；旁边的按钮不动。 */
    @Test fun whileExportingTheIconSpins() {
        show()
        val settings = button("Settings").bounds()
        exporting = true
        compose.waitForIdle()
        button("Exporting…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Exporting…").assertIsNotDisplayed()
        assertThat(button("Settings").assertIsDisplayed().bounds()).isEqualTo(settings)
    }

    /** 别的应用来要图时导出叫「完成」，换成图标后也念「完成」。 */
    @Test fun forACallerTheIconSaysDone() {
        show(exportLabel = R.string.done)
        button("Done").assertIsDisplayed()
    }

    /** 再开着调试信息，换成图标也放不下：去水印最先让出来，别的都在。 */
    @Test fun whenEvenTheIconDoesNotFitTheLeastNeededButtonStepsAside() {
        show(debug = true)
        premium.assertIsNotDisplayed()
        assertInARow(listOf("Back", "Undo", "Redo", "Debug info", "Settings", "Export").map(::button))
    }

    /** 放得下时导出写字，都照原来的顺序：撤销、重做、去水印、调试信息、设置挨着导出，返回在最左边。 */
    @Config(qualifiers = "en-rUS-w412dp-h915dp-xxhdpi")
    @Test fun withRoomExportKeepsItsLabel() {
        show(debug = true)
        val label = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Export", useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(label) }
        assertThat(label.single().lineCount).isEqualTo(1)
        button("Export").assertIsNotDisplayed()
        assertInARow(
            listOf("Back", "Undo", "Redo").map(::button) + premium + listOf("Debug info", "Settings").map(::button) +
                compose.onNodeWithText("Export"),
        )
        assertThat(compose.onNodeWithText("Export").bounds().height).isAtMost(48.dp)
    }
}
