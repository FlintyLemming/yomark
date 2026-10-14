package moe.flinty.yomark.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 展开后各项工具怎么分宽度（见 toolWidths）。下面的数按 dp 想：小样子连两边的空 36，选中那一项的小箭头 18；
 * 每一项拿到的宽够它本来的宽就画全，不够就少画（选中的先去小箭头，没选中的只留小样子）。
 */
class ToolWidthsTest {

    private fun widths(natural: List<Int>, room: Int, picked: Int = 0) =
        toolWidths(natural, room, picked = picked, chevron = CHEVRON, bare = BARE)

    @Test fun `room to spare is shared out evenly`() {
        assertThat(widths(listOf(90, 80, 70), 300)).containsExactly(110, 100, 90).inOrder()
    }

    @Test fun `a little short and only the picked tool loses its chevron`() {
        val w = widths(listOf(124, 70, 72), 260)
        assertThat(w).containsExactly(110, 74, 76).inOrder()
        // 选中的写得下名字、写不下小箭头；没选中的都写全
        assertThat(w[0]).isIn(124 - CHEVRON until 124)
    }

    /** 窄屏英文的「编辑」：选中的 Marker 连小箭头写全，Pen 写得下，Watermark 只剩小样子。 */
    @Test fun `further short and the longest names give way to their glyph`() {
        assertThat(widths(listOf(124, 70, 118), 267)).containsExactly(136, 82, 48).inOrder()
    }

    @Test fun `with nothing picked the shorter names stay`() {
        assertThat(widths(listOf(90, 70, 118), 267, picked = -1)).containsExactly(113, 93, 59).inOrder()
    }

    @Test fun `the picked name is cut only when every other tool is down to its glyph`() {
        assertThat(widths(listOf(150, 80, 90), 200)).containsExactly(128, BARE, BARE).inOrder()
    }

    /** 不管多宽，分出来的都按这个先后：选中的名字最要紧，没选中的短名字比长名字要紧。 */
    @Test fun `the picked name always outlasts the others and short names outlast long ones`() {
        val natural = listOf(80, 124, 70, 118, 95)
        for (room in 150..560) {
            val w = widths(natural, room, picked = 1)
            val shown = natural.indices.filter { it != 1 && w[it] >= natural[it] }
            if (w[1] < 124 - CHEVRON) assertThat(shown).isEmpty()
            for (i in shown) for (j in natural.indices) {
                if (j != 1 && natural[j] <= natural[i]) assertThat(w[j]).isAtLeast(natural[j])
            }
            // 只要每项都还留得下小样子，加起来就不出界
            if (room >= natural.size * BARE) assertThat(w.sum()).isAtMost(room)
        }
    }

    @Test fun `nothing to share`() {
        assertThat(widths(emptyList(), 100)).isEmpty()
        // 地方再少也留着小样子，多出去的由胶囊的边裁掉
        assertThat(widths(listOf(50, 60), 0, picked = -1)).containsExactly(BARE, BARE)
    }

    private companion object {
        const val CHEVRON = 18
        const val BARE = 36
    }
}
