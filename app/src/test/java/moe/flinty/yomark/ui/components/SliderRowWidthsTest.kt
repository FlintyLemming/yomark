package moe.flinty.yomark.ui.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 滑条那一行写值的那一格还放不放在滑条右边（见 valueBesideSlider）。下面的数按 dp 想：一行的宽是手机宽减去面板两边的 56，
 * 名字那一列 72；写值的那一格是最长的档位名字（labelLarge，Robolectric 里量的，真机上差一两 dp）再加前面空的 8。
 */
class SliderRowWidthsTest {

    /** 427 dp 宽的 Pixel 10 Pro 上英文的马赛克：最长的「Fairly coarse」84，那一格 92，滑条还有两百多。 */
    @Test fun `english levels sit beside the slider on a phone`() {
        assertThat(valueBesideSlider(371.dp, 72.dp, 92.dp)).isTrue()
    }

    /** 320 dp 宽的手机、宽屏右边那一栏最窄时这一行 264：滑条还剩 100。 */
    @Test fun `and on the narrowest rows too`() {
        assertThat(valueBesideSlider(264.dp, 72.dp, 92.dp)).isTrue()
    }

    /** 字号调到两倍，「Fairly coarse」152、那一格 160：360 dp 宽的手机上滑条只剩 72，值挪到名字那一行；宽一点的手机上还放得下。 */
    @Test fun `a doubled font moves the value off the slider row on a narrow phone`() {
        assertThat(valueBesideSlider(304.dp, 72.dp, 160.dp)).isFalse()
        assertThat(valueBesideSlider(356.dp, 72.dp, 160.dp)).isTrue()
    }

    @Test fun `the slider keeps its minimum length`() {
        assertThat(valueBesideSlider(72.dp + 48.dp + MinSliderWidth, 72.dp, 48.dp)).isTrue()
        assertThat(valueBesideSlider(72.dp + 48.dp + MinSliderWidth - 1.dp, 72.dp, 48.dp)).isFalse()
    }
}
