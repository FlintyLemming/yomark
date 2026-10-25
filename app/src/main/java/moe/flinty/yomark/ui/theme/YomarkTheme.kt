package moe.flinty.yomark.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.flinty.yomark.data.SettingsStore

/**
 * 主题色，在设置里切换（见 SettingsStore.themeColor）。出厂跟随系统。
 *
 * 只管色相；浅色还是深色由深色模式定（见 ThemeMode），每个主题色浅色、深色各一套。
 * 枚举名是持久化格式的一部分：改名或删掉一项，旧安装里存着的值就读不回来（读不回来退回 [SYSTEM]）。
 */
enum class ThemeColor {
    /**
     * 跟随系统：Android 12 起取系统按壁纸生成的配色（莫奈取色）。
     * 更早的系统没有这套配色，退回 [PURPLE]。
     */
    SYSTEM,

    /** 紫：Compose 自带的 Material 3 基准配色，也是本应用加主题色之前的样子。 */
    PURPLE,
    BLUE,
    TEAL,
    GREEN,
    ORANGE,
    RED,
    PINK,
}

/** 系统有没有莫奈取色。没有的话「跟随系统」就是紫色。 */
val systemDynamicColorAvailable: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

fun ThemeColor.colorScheme(context: Context, dark: Boolean): ColorScheme = when (this) {
    ThemeColor.SYSTEM -> when {
        !systemDynamicColorAvailable -> ThemeColor.PURPLE.colorScheme(context, dark)
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    ThemeColor.PURPLE -> if (dark) darkColorScheme() else lightColorScheme()
    ThemeColor.BLUE -> if (dark) ThemePalettes.blueDark else ThemePalettes.blue
    ThemeColor.TEAL -> if (dark) ThemePalettes.tealDark else ThemePalettes.teal
    ThemeColor.GREEN -> if (dark) ThemePalettes.greenDark else ThemePalettes.green
    ThemeColor.ORANGE -> if (dark) ThemePalettes.orangeDark else ThemePalettes.orange
    ThemeColor.RED -> if (dark) ThemePalettes.redDark else ThemePalettes.red
    ThemeColor.PINK -> if (dark) ThemePalettes.pinkDark else ThemePalettes.pink
}

/**
 * 深色还是浅色看 Configuration 里的 uiMode：设置里选了深色、浅色时，应用自己的 uiMode 已经换过了（见 AppThemeMode），
 * 窗口底色、系统栏图标、弹窗也都按它走，这里和它们对得上。
 */
@Composable
fun YomarkTheme(color: ThemeColor, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = remember(color, dark, context) { color.colorScheme(context, dark) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/**
 * 各 Activity 的根：主题色直接从 DataStore 收，设置页里一换，压在下面的页面回来时也已经是新颜色。
 *
 * 还没读到（null）的那一下什么都不画，免得先闪一下出厂配色再跳成用户选的颜色。
 * DataStore 在进程里读过一次就有缓存，实际看不出来。
 */
@Composable
fun YomarkTheme(store: SettingsStore, content: @Composable () -> Unit) {
    val color by store.themeColor.collectAsStateWithLifecycle(initialValue = null)
    color?.let { YomarkTheme(it, content) }
}

/** 这套配色是不是深色的：底色暗。按哪套配色画就听哪套的，不另外去问 Configuration。 */
internal val ColorScheme.isDark: Boolean
    get() = surface.luminance() < 0.5f
