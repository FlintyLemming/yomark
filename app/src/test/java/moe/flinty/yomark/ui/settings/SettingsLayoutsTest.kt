package moe.flinty.yomark.ui.settings

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.rules.RuleCatalog
import org.junit.Test

/**
 * 设置哪些窗口分两栏：宽屏（和编辑器的宽屏版式同一套认法），而且窗口宽到放得下两栏。
 * 数值同 EditorLayoutTest，是各类设备上 Configuration 报的 dp。
 */
class SettingsLayoutsTest {

    private fun twoPane(width: Int, height: Int, smallest: Int = minOf(width, height)) =
        SettingsLayouts.twoPane(width, height, smallest)

    @Test fun `tablets and unfolded book foldables split either way round`() {
        assertThat(twoPane(800, 1280)).isTrue()     // 10 英寸平板
        assertThat(twoPane(1280, 800)).isTrue()
        assertThat(twoPane(690, 829)).isTrue()      // 书本式折叠屏的内屏
        assertThat(twoPane(829, 690)).isTrue()
        assertThat(twoPane(960, 528, smallest = 600)).isTrue()   // 小平板横放，三键导航占了底边
    }

    /** 手机横着拿也不分：上下太矮，两个大标题栏一占就看不到几行。 */
    @Test fun `phones never split, not even held sideways`() {
        assertThat(twoPane(412, 915)).isFalse()
        assertThat(twoPane(915, 412)).isFalse()
        assertThat(twoPane(1005, 412)).isFalse()    // 翻盖折叠屏展开，横着拿
        assertThat(twoPane(1080, 485)).isFalse()    // 显示大小调到最小的大屏手机
    }

    /** 阔折叠横着拿够宽；竖着拿只有五百来 dp，分成两栏两边都太挤。 */
    @Test fun `a wide fold splits only when held sideways`() {
        assertThat(twoPane(848, 528)).isTrue()
        assertThat(twoPane(808, 503)).isTrue()
        assertThat(twoPane(528, 848)).isFalse()
        assertThat(twoPane(503, 808)).isFalse()
    }

    /** 分屏、自由窗口里看的是这个窗口。 */
    @Test fun `split screen follows the window`() {
        assertThat(twoPane(420, 752)).isFalse()     // 平板上分到三分之一
        assertThat(twoPane(636, 752)).isTrue()      // 分到一半
        assertThat(twoPane(1000, 400)).isFalse()    // 上下分屏：矮得像横过来的手机
    }

    @Test fun `an unknown size does not split`() {
        assertThat(twoPane(0, 0)).isFalse()
    }

    /** 分栏时右栏从一级页上那一项叠起：文字里的一类在「文字」上面，识别引擎在「AI」上面，调试记录在「开发者」上面。 */
    @Test fun `a page stacks on the topic it is opened from`() {
        val rule = RuleCatalog.all.first().id
        assertThat(SettingsDestination(SettingsPage.TEXT_RULE, rule).chain).containsExactly(
            SettingsDestination(SettingsPage.TEXT),
            SettingsDestination(SettingsPage.TEXT_RULE, rule),
        ).inOrder()
        assertThat(SettingsDestination(SettingsPage.TEXT_ENGINE).chain)
            .containsExactly(SettingsDestination(SettingsPage.AI), SettingsDestination(SettingsPage.TEXT_ENGINE)).inOrder()
        assertThat(SettingsDestination(SettingsPage.DEBUG_TRACE).chain)
            .containsExactly(SettingsDestination(SettingsPage.DEVELOPER), SettingsDestination(SettingsPage.DEBUG_TRACE)).inOrder()
        assertThat(SettingsDestination(SettingsPage.FACE).chain).containsExactly(SettingsDestination(SettingsPage.FACE))
    }

    @Test fun `a kind that no longer exists cannot be opened`() {
        assertThat(SettingsDestination(SettingsPage.TEXT_RULE, "no-such-kind").valid).isFalse()
        assertThat(SettingsDestination(SettingsPage.TEXT_RULE, null).valid).isFalse()
        assertThat(SettingsDestination(SettingsPage.TEXT_RULE, RuleCatalog.all.first().id).valid).isTrue()
        assertThat(SettingsDestination(SettingsPage.ABOUT).valid).isTrue()
    }
}
