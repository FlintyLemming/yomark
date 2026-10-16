package moe.flinty.yomark.ui.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 滑条那一行左右两格留多宽（SliderWidths.fitting），名字、滑条、值还排不排得进一行（fitOneRow）。下面的数按 dp 想：
 * 一行的宽是手机宽减去面板两边的 56；名字（bodyMedium）、值（labelLarge）的宽是 Robolectric 里量的，真机上差一两 dp。
 */
class SliderRowWidthsTest {

    /** 字短的面板和原先一样：名字那一列 72，写值那一格 48。中文最长的「不透明度」57，「48 px」35。 */
    @Test fun `short names and numbers keep the old columns`() {
        assertThat(SliderWidths.fitting(57.dp, 35.dp)).isEqualTo(SliderWidths(72.dp, 48.dp))
    }

    /** 字号调到 1.3 倍，「Brightness」92：名字那一列放得下它，再空 8 不贴着滑条。「Fairly coarse」82 的那一格也一样。 */
    @Test fun `a long name widens its column and keeps a gap from the slider`() {
        assertThat(SliderWidths.fitting(92.dp, 82.dp)).isEqualTo(SliderWidths(100.dp, 90.dp))
    }

    /** 427 dp 宽的 Pixel 10 Pro 上英文的马赛克：最长的「Fairly coarse」84，那一格 92，滑条还有两百多。 */
    @Test fun `english levels sit beside the slider on a phone`() {
        assertThat(SliderWidths(72.dp, 92.dp).fitOneRow(371.dp)).isTrue()
    }

    /** 320 dp 宽的手机、宽屏右边那一栏最窄时这一行 264：滑条还剩 100。 */
    @Test fun `and on the narrowest rows too`() {
        assertThat(SliderWidths(72.dp, 92.dp).fitOneRow(264.dp)).isTrue()
    }

    /** 字号调到两倍，「Fairly coarse」152、那一格 160：360 dp 宽的手机上滑条只剩 72，值挪到名字那一行；宽一点的手机上还放得下。 */
    @Test fun `a doubled font moves the value off the slider row on a narrow phone`() {
        assertThat(SliderWidths(72.dp, 160.dp).fitOneRow(304.dp)).isFalse()
        assertThat(SliderWidths(72.dp, 160.dp).fitOneRow(356.dp)).isTrue()
    }

    /**
     * 字号两倍的英文水印：名字那一列按「Brightness」128 留 136，写值那一格按「Medium」97 留 105。
     * Pixel 10 Pro 上滑条还有 130；360 dp 宽的手机上只剩 63，换成上下两行。
     */
    @Test fun `a long name takes slider room too`() {
        val watermark = SliderWidths.fitting(128.dp, 97.dp)
        assertThat(watermark.fitOneRow(371.dp)).isTrue()
        assertThat(watermark.fitOneRow(304.dp)).isFalse()
    }

    @Test fun `the slider keeps its minimum length`() {
        assertThat(SliderWidths(72.dp, 48.dp).fitOneRow(72.dp + 48.dp + MinSliderWidth)).isTrue()
        assertThat(SliderWidths(72.dp, 48.dp).fitOneRow(72.dp + 48.dp + MinSliderWidth - 1.dp)).isFalse()
    }
}
