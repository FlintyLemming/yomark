package com.youma.app.ui.theme

import androidx.compose.material3.ColorScheme
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
        val primaries = ThemeColor.entries.filter { it != ThemeColor.SYSTEM }.map { it.colorScheme(context).primary }
        assertThat(primaries).containsNoDuplicates()
    }

    @Test
    fun `purple is the look the app had before theme colors`() {
        assertSameScheme(ThemeColor.PURPLE.colorScheme(context), lightColorScheme())
    }

    @Test
    fun `every preset stays a light theme`() {
        // 界面只有浅色一套，系统栏图标恒为深色：底色必须是浅的
        ThemeColor.entries.forEach {
            assertThat(it.colorScheme(context).surface.luminance()).isGreaterThan(0.8f)
        }
    }

    @Test
    fun `following the system uses the wallpaper colors when the system has them`() {
        assertThat(systemDynamicColorAvailable).isTrue()
        assertThat(ThemeColor.SYSTEM.colorScheme(context).toString()).isNotEqualTo(lightColorScheme().toString())
    }

    @Test
    @Config(sdk = [30])
    fun `following the system falls back to purple before Android 12`() {
        assertThat(systemDynamicColorAvailable).isFalse()
        assertSameScheme(ThemeColor.SYSTEM.colorScheme(context), lightColorScheme())
    }
}
