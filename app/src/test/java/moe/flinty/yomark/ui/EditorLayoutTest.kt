package moe.flinty.yomark.ui

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.ui.EditorLayout.PHONE
import moe.flinty.yomark.ui.EditorLayout.WIDE_BOTTOM
import moe.flinty.yomark.ui.EditorLayout.WIDE_SIDE
import org.junit.Test

/**
 * 哪些窗口用宽屏版式。数值是各类设备上 Configuration 报的 dp：Android 15 起含系统栏，之前不含，
 * 两种都列了一些。手机怎么摆都不该用宽屏版式，宽屏转个方向只换列表的位置。
 */
class EditorLayoutTest {

    private fun layout(width: Int, height: Int, smallest: Int = minOf(width, height)) =
        EditorLayouts.choose(width, height, smallest)

    @Test fun `phones keep the phone layout in both orientations`() {
        assertThat(layout(412, 915)).isEqualTo(PHONE)           // Pixel 8
        assertThat(layout(915, 412)).isEqualTo(PHONE)
        assertThat(layout(360, 780)).isEqualTo(PHONE)           // 小屏手机
        assertThat(layout(780, 336, smallest = 360)).isEqualTo(PHONE)   // 横屏，Android 14 不含系统栏
        assertThat(layout(412, 1005)).isEqualTo(PHONE)          // 翻盖折叠屏展开
        assertThat(layout(344, 882)).isEqualTo(PHONE)           // 书本式折叠屏的外屏
    }

    /** 大屏手机把显示大小调到最小，最短边能到四百八九十；再宽的老款 18:9 手机，长宽比也挡得住。 */
    @Test fun `a phone at the smallest display size is still a phone`() {
        assertThat(layout(485, 1080)).isEqualTo(PHONE)
        assertThat(layout(1080, 485)).isEqualTo(PHONE)
        assertThat(layout(514, 1028)).isEqualTo(PHONE)
        assertThat(layout(514, 956)).isEqualTo(PHONE)          // Android 14 不含状态栏和导航栏，比例 1.86
        assertThat(layout(956, 466, smallest = 514)).isEqualTo(PHONE)
    }

    /** 阔折叠（华为 Pura X 内屏 2120×1320，16:10）：按 2.5 和 2.625 两种密度折算。 */
    @Test fun `a wide fold gets the wide layout`() {
        assertThat(layout(528, 848)).isEqualTo(WIDE_BOTTOM)
        assertThat(layout(848, 528)).isEqualTo(WIDE_SIDE)
        assertThat(layout(503, 808)).isEqualTo(WIDE_BOTTOM)
        assertThat(layout(808, 503)).isEqualTo(WIDE_SIDE)
        assertThat(layout(808, 455, smallest = 503)).isEqualTo(WIDE_SIDE)   // 不含系统栏
    }

    @Test fun `book foldables and tablets get the wide layout`() {
        assertThat(layout(690, 829)).isEqualTo(WIDE_BOTTOM)     // 书本式折叠屏的内屏
        assertThat(layout(829, 690)).isEqualTo(WIDE_SIDE)
        assertThat(layout(800, 1280)).isEqualTo(WIDE_BOTTOM)    // 10 英寸平板
        assertThat(layout(1280, 800)).isEqualTo(WIDE_SIDE)
        assertThat(layout(960, 528, smallest = 600)).isEqualTo(WIDE_SIDE)   // 小平板横放，三键导航占了底边
    }

    /** 分屏、自由窗口里看的是这个窗口：平板上分到三分之一的窗口和手机一样窄。 */
    @Test fun `split screen follows the window`() {
        assertThat(layout(420, 752)).isEqualTo(PHONE)
        assertThat(layout(636, 752)).isEqualTo(WIDE_BOTTOM)
        assertThat(layout(800, 600)).isEqualTo(WIDE_SIDE)
        assertThat(layout(1000, 400)).isEqualTo(PHONE)
    }

    @Test fun `a square window counts as upright`() {
        assertThat(layout(700, 700)).isEqualTo(WIDE_BOTTOM)
    }

    @Test fun `an unknown size is treated as a phone`() {
        assertThat(layout(0, 0)).isEqualTo(PHONE)
        assertThat(layout(0, 0, smallest = 520)).isEqualTo(PHONE)
    }
}
