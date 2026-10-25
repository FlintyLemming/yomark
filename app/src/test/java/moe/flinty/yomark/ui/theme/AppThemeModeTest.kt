package moe.flinty.yomark.ui.theme

import android.app.Application
import android.app.UiModeManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.R
import moe.flinty.yomark.ui.YomarkActivity
import moe.flinty.yomark.ui.withAppConfiguration
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.Locale

/** 应用自己的深色模式：选的那一项记下来；Android 12 起交给系统，10、11 由各页建出来时自己换上夜间那一位。 */
@RunWith(RobolectricTestRunner::class)
class AppThemeModeTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()

    private fun page(): ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    /** 交给系统的那个值。UiModeManager 每个 Context 各有一个，看改的那一页自己的。 */
    private fun handedToSystem(page: ComponentActivity) =
        shadowOf(page.getSystemService(UiModeManager::class.java)).applicationNightMode

    @Test fun `follows the system until something else is picked`() {
        assertThat(AppThemeMode.current(app)).isEqualTo(ThemeMode.SYSTEM)
    }

    @Test fun `the pick is remembered and handed to the system`() {
        val page = page()
        AppThemeMode.set(page, ThemeMode.DARK)
        assertThat(AppThemeMode.current(app)).isEqualTo(ThemeMode.DARK)
        assertThat(handedToSystem(page)).isEqualTo(UiModeManager.MODE_NIGHT_YES)

        AppThemeMode.set(page, ThemeMode.LIGHT)
        assertThat(AppThemeMode.current(app)).isEqualTo(ThemeMode.LIGHT)
        assertThat(handedToSystem(page)).isEqualTo(UiModeManager.MODE_NIGHT_NO)

        AppThemeMode.set(page, ThemeMode.SYSTEM)
        assertThat(AppThemeMode.current(app)).isEqualTo(ThemeMode.SYSTEM)
        assertThat(handedToSystem(page)).isEqualTo(UiModeManager.MODE_NIGHT_AUTO)
    }

    @Test fun `from Android 12 the pages leave the night bit to the system`() {
        AppThemeMode.set(page(), ThemeMode.DARK)
        assertThat(AppThemeMode.nightOverride(app)).isNull()
    }

    @Test @Config(sdk = [30]) fun `before Android 12 the pages swap in the night bit themselves`() {
        val page = page()
        AppThemeMode.set(page, ThemeMode.DARK)
        assertThat(AppThemeMode.nightOverride(app)).isEqualTo(Configuration.UI_MODE_NIGHT_YES)
        AppThemeMode.set(page, ThemeMode.LIGHT)
        assertThat(AppThemeMode.nightOverride(app)).isEqualTo(Configuration.UI_MODE_NIGHT_NO)
        AppThemeMode.set(page, ThemeMode.SYSTEM)
        assertThat(AppThemeMode.nightOverride(app)).isNull()
    }

    @Test @Config(sdk = [30]) fun `before Android 12 a page opens in the picked mode`() {
        AppThemeMode.set(page(), ThemeMode.DARK)
        val page = Robolectric.buildActivity(Page::class.java).setup().get()
        assertThat(page.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            .isEqualTo(Configuration.UI_MODE_NIGHT_YES)
        // 主题取的是 values-night 里的那份：系统栏图标白色
        val attrs = page.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowLightStatusBar))
        try {
            assertThat(attrs.getBoolean(0, true)).isFalse()
        } finally {
            attrs.recycle()
        }
    }

    @Test @Config(qualifiers = "night") fun `the dark window background is a resource the splash screen can read`() {
        // Android 12 起冷启动的启动画面只认资源，主题里直接写的色值它取不到，整屏画成纯黑
        val page = Robolectric.buildActivity(Page::class.java).setup().get()
        val attrs = page.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowBackground))
        try {
            assertThat(attrs.getResourceId(0, 0)).isEqualTo(R.color.window_background_dark)
        } finally {
            attrs.recycle()
        }
    }

    @Test @Config(sdk = [30]) fun `swapping in the language and the night bit leaves the rest as the system has it`() {
        val system = app.resources.configuration
        val config = withAppConfiguration(app, Locale.ENGLISH, Configuration.UI_MODE_NIGHT_YES).resources.configuration
        assertThat(config.locales[0]).isEqualTo(Locale.ENGLISH)
        assertThat(config.uiMode and Configuration.UI_MODE_NIGHT_MASK).isEqualTo(Configuration.UI_MODE_NIGHT_YES)
        assertThat(config.uiMode and Configuration.UI_MODE_TYPE_MASK).isEqualTo(system.uiMode and Configuration.UI_MODE_TYPE_MASK)
        assertThat(config.screenWidthDp).isEqualTo(system.screenWidthDp)
        assertThat(config.densityDpi).isEqualTo(system.densityDpi)
    }

    /** 一个什么都不画的页，只看 YomarkActivity 给它换上的 Configuration 和主题。 */
    class Page : YomarkActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            setTheme(R.style.Theme_Yomark)
            super.onCreate(savedInstanceState)
        }
    }
}
