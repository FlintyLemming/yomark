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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import moe.flinty.yomark.ui.components.OtherExport
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 手机竖着拿时的顶栏：返回、撤销、重做、去水印、设置、分享、导出。一行放不下「导出」两个字时（320 dp 宽，或者调大了显示大小），
 * 导出换成只有图标的按钮，别的都还在；再放不下才从最不要紧的起让出来，分享和导出一样不让。
 * 用真的 ViewModel 走一遍的见 EditorScreenLayoutTest。
 */
@RunWith(RobolectricTestRunner::class)
// 真的量字：放不放得下要按真的字宽算
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w320dp-h640dp-xhdpi")
class EditorTopBarTest {

    @get:Rule val compose = createComposeRule()

    private var exporting by mutableStateOf(false)
    private var sharing by mutableStateOf(false)
    private var overwriting by mutableStateOf(false)

    /**
     * EditorScreen 在手机竖着拿时传的那几样；[debug] 是开发者选项里打开了调试信息，
     * [share] 是有分享（别的应用来要图时没有），[other] 是导出右边小箭头里的另一种导出（别的应用来要图时没有）。
     */
    private fun show(
        debug: Boolean = false,
        exportLabel: Int = R.string.export,
        share: Boolean = false,
        other: OtherExport? = null,
        onOther: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    EditorTopBar(
                        canUndo = true, canRedo = true, onUndo = {}, onRedo = {},
                        onSettings = {}, onNavigateUp = {},
                        onRemoveWatermark = {},
                        onDebug = if (debug) ({}) else null,
                        onExport = {},
                        onShare = if (share) ({}) else null,
                        exportLabel = exportLabel,
                        otherExport = other,
                        onOtherExport = onOther,
                        exporting = exporting,
                        sharing = sharing,
                        overwriting = overwriting,
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

    /** 别的应用来要图时导出叫「完成」，换成图标后也念「完成」。没有分享：导出就是把图交回去。 */
    @Test fun forACallerTheIconSaysDone() {
        show(exportLabel = R.string.done)
        button("Done").assertIsDisplayed()
        button("Share").assertDoesNotExist()
    }

    /** 有分享时它挨在导出左边，320 dp 上放不下所有的：去水印让出来，分享和导出都在。 */
    @Test fun onA320dpScreenShareStaysAndThePremiumButtonStepsAside() {
        show(share = true)
        premium.assertIsNotDisplayed()
        assertInARow(listOf("Back", "Undo", "Redo", "Settings", "Share", "Export").map(::button))
        // 紧挨着：两颗圆钮四周各留 4 dp 的触摸余量，中间再多空 4 dp（EXPORT_GAP）
        assertThat(button("Export").bounds().left - button("Share").bounds().right).isAtMost(12.dp)
    }

    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi")
    @Test fun shareInChineseToo() {
        show(share = true)
        assertInARow(listOf("返回", "撤销", "重做", "设置", "分享", "导出").map(::button))
    }

    /** 分享中：分享那颗转圈、读屏念「分享中…」，导出也点不动，但不说自己在导出。 */
    @Test fun whileSharingTheShareIconSpinsAndExportWaits() {
        show(share = true)
        exporting = true
        sharing = true
        compose.waitForIdle()
        button("Sharing…").assertIsDisplayed().assertIsNotEnabled()
        button("Export").assertIsDisplayed().assertIsNotEnabled()
        button("Exporting…").assertDoesNotExist()
    }

    /** 导出中：导出那颗转圈，分享点不动。 */
    @Test fun whileExportingShareWaits() {
        show(share = true)
        exporting = true
        compose.waitForIdle()
        button("Exporting…").assertIsDisplayed().assertIsNotEnabled()
        button("Share").assertIsDisplayed().assertIsNotEnabled()
    }

    /** 放得下时：去水印、设置、分享挨着写字的导出，都在。 */
    @Config(qualifiers = "en-rUS-w412dp-h915dp-xxhdpi")
    @Test fun withRoomShareSitsBesideTheExportLabel() {
        show(share = true)
        button("Export").assertIsNotDisplayed()
        assertInARow(
            listOf("Back", "Undo", "Redo").map(::button) + premium + listOf("Settings", "Share").map(::button) +
                compose.onNodeWithText("Export"),
        )
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

    // ---------- 导出右边的小箭头（覆盖原图，2026-10-27） ----------

    /** 箭头和导出连成一颗，紧挨在写着字的导出右边，在最右上角；点开是覆盖原图，点了菜单收起。 */
    @Config(qualifiers = "en-rUS-w600dp-h915dp-xxhdpi")
    @Test fun theArrowBesideExportOffersOverwritingTheOriginal() {
        var chosen = 0
        show(share = true, other = OtherExport.Overwrite(OverwriteOffer.Ready()), onOther = { chosen++ })
        assertInARow(
            listOf("Back", "Undo", "Redo").map(::button) + premium + listOf("Settings", "Share").map(::button) +
                compose.onNodeWithText("Export") + button("Export options"),
        )
        assertThat(button("Export options").bounds().left - compose.onNodeWithText("Export").bounds().right).isAtMost(2.dp)

        button("Export options").performClick()
        compose.onNodeWithText("Overwrite original").assertIsDisplayed().assertIsEnabled().performClick()

        assertThat(chosen).isEqualTo(1)
        compose.onNodeWithText("Overwrite original").assertDoesNotExist()
    }

    /** 这张写不回去：那一项灰着，底下说为什么。 */
    @Test fun anImageThatCannotBeWrittenBackSaysWhy() {
        var chosen = 0
        show(other = OtherExport.Overwrite(OverwriteOffer.Unavailable(R.string.overwrite_format)), onOther = { chosen++ })
        button("Export options").performClick()
        compose.onNodeWithText("Overwrite original", substring = true).assertIsNotEnabled()
        compose.onNodeWithText("JPG and PNG only").assertIsDisplayed()
        assertThat(chosen).isEqualTo(0)
    }

    /** 批量里看过的有几张写不回去：点得动，底下说一声那几张会另存。 */
    @Test fun inABatchTheArrowSaysHowManyWillBeCopies() {
        show(other = OtherExport.Overwrite(OverwriteOffer.Ready(copies = 2)))
        button("Export options").performClick()
        compose.onNodeWithText("2 can't be overwritten, will be saved to gallery").assertIsDisplayed()
    }

    /** 设置里默认导出选了覆盖原图：导出写「覆盖」，箭头里是另存到相册。 */
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-xhdpi")
    @Test fun whenOverwritingIsTheDefaultTheArrowSavesACopy() {
        var chosen = 0
        show(exportLabel = R.string.overwrite, share = true, other = OtherExport.SaveCopy, onOther = { chosen++ })
        button("覆盖").assertIsDisplayed()
        button("导出选项").performClick()
        compose.onNodeWithText("另存到相册").performClick()
        assertThat(chosen).isEqualTo(1)
    }

    /**
     * 320 dp 上再加个箭头：导出换成图标，箭头、分享、导出都在，中间那几个照要紧的先后让出来（去水印、设置在首页也有）。
     * 覆盖中转圈，读屏念「覆盖中…」。
     */
    @Test fun onA320dpScreenTheArrowStaysAndTheLeastNeededStepAside() {
        show(share = true, other = OtherExport.Overwrite(OverwriteOffer.Ready()))
        compose.onNodeWithText("Export").assertIsNotDisplayed()
        assertInARow(listOf("Back", "Undo", "Redo", "Share", "Export", "Export options").map(::button))

        exporting = true
        overwriting = true
        compose.waitForIdle()
        button("Overwriting…").assertIsDisplayed().assertIsNotEnabled()
        button("Export options").assertIsNotEnabled()
    }
}
