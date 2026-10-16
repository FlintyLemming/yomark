package moe.flinty.yomark.ui

import android.graphics.RectF
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.ui.bigbang.BigBangPage
import moe.flinty.yomark.ui.components.BigBangActions
import moe.flinty.yomark.ui.components.BigBangContent
import moe.flinty.yomark.ui.components.ChipMetrics
import moe.flinty.yomark.ui.components.layoutChips
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 大爆炸的面板（spec §7.3，2026-10-16 增补）：字块怎么摆，点、横着划、竖着划各是什么。
 * 选上、取消、打码怎么改 plan 见 EditorViewModelBigBangTest。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class BigBangPanelTest {

    @get:Rule val compose = createComposeRule()

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    /** 像 PP-OCR 那样一字一个 element，字宽 20、字高 20。每个 Pair 是一段（原文、左沿），段与段之间补一个空格。 */
    private fun ppLine(top: Float, vararg segs: Pair<String, Float>): TextLine {
        val text = StringBuilder()
        val els = ArrayList<TextElement>()
        val quads = segs.mapIndexed { n, (s, left) ->
            if (n > 0) text.append(' ')
            s.forEachIndexed { i, c ->
                val at = text.length
                if (c != ' ') els += TextElement(rect(left + i * 20f, top, left + (i + 1) * 20f, top + 20f), c.toString(), at..at)
                text.append(c)
            }
            rect(left, top, left + s.length * 20f, top + 20f)
        }
        val whole = RectF(quads[0].bounds()).apply { quads.forEach { union(it.bounds()) } }
        return TextLine(Quad.fromRect(whole), text.toString(), 0.95f, els, quads)
    }

    private class Recorder : BigBangActions {
        val toggled = mutableListOf<Int>()
        val starts = mutableListOf<Int>()
        val swept = mutableListOf<Int>()
        var closed = 0
        override fun onToggle(id: Int) { toggled += id }
        override fun onSweepStart(id: Int) { starts += id }
        override fun onSweep(ids: List<Int>) { swept += ids }
        override fun onClose() { closed++ }
    }

    private val metrics = ChipMetrics.of(Density(1f))

    @Test fun `a line starts a new row and wraps when it does not fit`() {
        val page = BigBangPage.of(listOf(ppLine(0f, "一二三四五" to 0f), ppLine(30f, "六七" to 0f)))
        // 每块 34 宽、间隔 3：一排放得下 3 块（3 × 34 + 2 × 3 = 108）
        val layout = layoutChips(page, { 16f }, 120f, metrics)
        val tops = layout.cells.map { it.top }
        assertThat(tops.subList(0, 3).toSet()).containsExactly(0f)
        assertThat(tops.subList(3, 5).toSet()).containsExactly(44f)                  // 折到第二排
        assertThat(layout.cells[3].left).isEqualTo(0f)
        assertThat(layout.lineTops).containsExactly(0f, 94f).inOrder()             // 第二行：第一行的两排之后再空一个行距
        assertThat(layout.cells[5].top).isEqualTo(94f)
        assertThat(layout.height).isEqualTo(134f)
    }

    /** 只画露在面板里的那几排：按排二分找第一块。 */
    @Test fun `firstBelow finds the first chip on the visible rows`() {
        val page = BigBangPage.of(listOf(ppLine(0f, "一二三四五" to 0f), ppLine(30f, "六七" to 0f)))
        val layout = layoutChips(page, { 16f }, 120f, metrics)
        assertThat(layout.firstBelow(0f)).isEqualTo(0)
        assertThat(layout.firstBelow(41f)).isEqualTo(3)          // 第一排 0..40 整个在上面
        assertThat(layout.firstBelow(90f)).isEqualTo(5)          // 第二排 44..84 也在上面
        assertThat(layout.firstBelow(1000f)).isEqualTo(7)
    }

    @Test fun `gaps follow the spacing on the image`() {
        // 同一段里隔着空格，和连不上的两段
        val page = BigBangPage.of(listOf(ppLine(0f, "张 三" to 0f, "按钮" to 200f)))
        val c = layoutChips(page, { 16f }, 1000f, metrics).cells
        assertThat(c[1].left - c[0].right).isEqualTo(metrics.spaceGap)
        assertThat(c[2].left - c[1].right).isEqualTo(metrics.farGap)
        assertThat(c[3].left - c[2].right).isEqualTo(metrics.chipGap)
    }

    private var bang by mutableStateOf(BigBangState(BigBangPage.EMPTY))

    private fun show(page: BigBangPage, actions: BigBangActions, selected: Set<Int> = emptySet()) {
        bang = BigBangState(page, selected)
        compose.setContent { MaterialTheme { Surface { BigBangContent(bang, emptySet(), actions) } } }
    }

    private val sample get() = BigBangPage.of(listOf(ppLine(0f, "收货人张三" to 0f)))

    /** 第 [k] 块字的中心，相对字块那一块的左上角：中文一个字的块是最小宽 34 dp，间隔 3 dp，高 40 dp。 */
    private fun chip(k: Int, density: Float) = Offset((k * 37f + 17f) * density, 20f * density)

    @Test fun `tapping a chip toggles that character`() {
        val r = Recorder()
        show(sample, r)
        val density = compose.density.density
        compose.onNodeWithContentDescription("收货人张三").performTouchInput { click(chip(3, density)) }
        assertThat(r.toggled).containsExactly(3)
        assertThat(r.starts).isEmpty()
    }

    @Test fun `a sideways swipe sweeps the characters it passes`() {
        val r = Recorder()
        show(sample, r)
        val density = compose.density.density
        compose.onNodeWithContentDescription("收货人张三").performTouchInput {
            down(chip(1, density))
            moveTo(chip(2, density))
            moveTo(chip(4, density))
            up()
        }
        assertThat(r.starts).containsExactly(1)
        assertThat(r.swept.toSet()).containsExactly(1, 2, 3, 4)
        assertThat(r.toggled).isEmpty()
    }

    @Test fun `a vertical drag is left to scrolling`() {
        val r = Recorder()
        show(sample, r)
        val density = compose.density.density
        compose.onNodeWithContentDescription("收货人张三").performTouchInput {
            down(chip(1, density))
            moveTo(chip(1, density) + Offset(0f, 30f * density))
            up()
        }
        assertThat(r.starts).isEmpty()
        assertThat(r.swept).isEmpty()
        assertThat(r.toggled).isEmpty()
    }

    /** 点字就是打码，没有「打码」「全选」：顶上一行是名字、选了几个字，右边「完成」收起。 */
    @Test fun `done closes, and there is no mask or select-all button`() {
        val r = Recorder()
        show(sample, r)
        compose.onNodeWithText("已选 0 字").assertExists()
        bang = bang.copy(selected = setOf(3, 4))
        compose.onNodeWithText("已选 2 字").assertExists()
        compose.onNodeWithText("打码").assertDoesNotExist()
        compose.onNodeWithText("全选").assertDoesNotExist()
        compose.onNodeWithText("取消").assertDoesNotExist()
        val done = compose.onNodeWithText("完成").getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("大爆炸").getUnclippedBoundsInRoot()
        assertThat(done.left).isGreaterThan(title.right)                       // 在右边
        assertThat(done.top).isLessThan(title.bottom)                          // 和名字同一行
        compose.onNodeWithText("完成").performClick()
        assertThat(r.closed).isEqualTo(1)
    }
}
