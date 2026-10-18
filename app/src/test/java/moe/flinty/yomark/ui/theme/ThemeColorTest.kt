package moe.flinty.yomark.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class ThemeColorTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    /** ColorScheme 没有 equals；它的 toString 逐项列出了全部颜色，拿来比整套。 */
    private fun assertSameScheme(actual: ColorScheme, expected: ColorScheme) {
        assertThat(actual.toString()).isEqualTo(expected.toString())
    }

    @Test
    fun `every preset has its own primary color`() {
        listOf(false, true).forEach { dark ->
            val primaries = ThemeColor.entries.filter { it != ThemeColor.SYSTEM }.map { it.colorScheme(context, dark).primary }
            assertThat(primaries).containsNoDuplicates()
        }
    }

    @Test
    fun `purple is the look the app had before theme colors`() {
        assertSameScheme(ThemeColor.PURPLE.colorScheme(context, dark = false), lightColorScheme())
    }

    @Test
    fun `purple in the dark is Material's own dark scheme`() {
        assertSameScheme(ThemeColor.PURPLE.colorScheme(context, dark = true), darkColorScheme())
    }

    @Test
    fun `every color is light in light mode and dark in dark mode`() {
        // 系统栏图标的深浅由 values-night 里的主题定，配色的底色深浅必须和它对得上
        ThemeColor.entries.forEach {
            val light = it.colorScheme(context, dark = false)
            val dark = it.colorScheme(context, dark = true)
            assertThat(light.surface.luminance()).isGreaterThan(0.8f)
            assertThat(dark.surface.luminance()).isLessThan(0.05f)
            assertThat(light.isDark).isFalse()
            assertThat(dark.isDark).isTrue()
        }
    }

    @Test
    fun `following the system uses the wallpaper colors when the system has them`() {
        assertThat(systemDynamicColorAvailable).isTrue()
        assertThat(ThemeColor.SYSTEM.colorScheme(context, dark = false).toString()).isNotEqualTo(lightColorScheme().toString())
        assertThat(ThemeColor.SYSTEM.colorScheme(context, dark = true).toString()).isNotEqualTo(darkColorScheme().toString())
    }

    @Test
    @Config(sdk = [30])
    fun `following the system falls back to purple before Android 12`() {
        assertThat(systemDynamicColorAvailable).isFalse()
        assertSameScheme(ThemeColor.SYSTEM.colorScheme(context, dark = false), lightColorScheme())
        assertSameScheme(ThemeColor.SYSTEM.colorScheme(context, dark = true), darkColorScheme())
    }
}
