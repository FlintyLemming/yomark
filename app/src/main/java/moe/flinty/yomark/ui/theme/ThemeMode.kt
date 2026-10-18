package moe.flinty.yomark.ui.theme

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.ComponentActivity

/**
 * 深色模式：跟随系统、浅色、深色。出厂跟随系统。
 * 枚举名是持久化格式的一部分（见 [AppThemeMode]），认不出的值当跟随系统。
 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 应用自己的深色模式，和系统的分开设。
 *
 * 不在 Compose 里自己定深浅，而是换掉应用的 Configuration 里 uiMode 的夜间那一位，和界面语言（AppLocale）一个做法：
 * 配色（YomarkTheme 看 isSystemInDarkTheme）、窗口底色和系统栏图标（values-night 里的主题）、
 * Material 3 弹窗的系统栏都看这一位，一处换了处处对得上。
 *
 * Android 12 起交给系统的 UiModeManager.setApplicationNightMode：系统记住这个值，冷启动的启动画面也按它画；
 * 改了之后系统给本应用的各个 Activity 换 Configuration，各页重建一次。系统没有读这个值的接口，
 * 选的是哪一项另记在 SharedPreferences 里，设置页从这里读。
 *
 * Android 10、11 没有这个接口，Activity 建出来时自己换上（[nightOverride]，由 YomarkActivity 换），
 * 启动画面还是跟着系统的。只换 Activity 的，Application 的不换。
 */
object AppThemeMode {

    private const val PREFS = "app_theme_mode"
    private const val KEY_MODE = "mode"

    fun current(context: Context): ThemeMode =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
            ?.let { name -> ThemeMode.entries.firstOrNull { it.name == name } }
            ?: ThemeMode.SYSTEM

    /**
     * 选一种。Android 12 起系统换 Configuration，这一页和压在下面的几页由系统重建（深浅没变就不重建）；
     * Android 10、11 这一页自己重建，压在下面的回到前台时重建（见 YomarkActivity）。
     */
    fun set(activity: ComponentActivity, mode: ThemeMode) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .apply { if (mode == ThemeMode.SYSTEM) remove(KEY_MODE) else putString(KEY_MODE, mode.name) }
            .commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(UiModeManager::class.java).setApplicationNightMode(
                when (mode) {
                    // AUTO 在这里就是不另外设，跟着系统
                    ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                    ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
                    ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
                },
            )
        } else {
            activity.recreate()
        }
    }

    /**
     * Android 10、11：Activity 建出来时 uiMode 的夜间那一位该换成什么（Configuration.UI_MODE_NIGHT_*）。
     * 跟随系统、或 12 及以上（系统已经换好了）时为 null，不换。
     */
    internal fun nightOverride(context: Context): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return null
        return when (current(context)) {
            ThemeMode.SYSTEM -> null
            ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
            ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        }
    }
}
