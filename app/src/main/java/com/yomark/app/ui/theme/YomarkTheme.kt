package com.yomark.app.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yomark.app.data.SettingsStore

/**
 * 主题色，在设置里切换（见 SettingsStore.themeColor）。出厂跟随系统。
 *
 * 界面仍只有浅色一套（见 enableLightEdgeToEdge），这里换的只是色相。
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

fun ThemeColor.colorScheme(context: Context): ColorScheme = when (this) {
    ThemeColor.SYSTEM ->
        if (systemDynamicColorAvailable) dynamicLightColorScheme(context) else lightColorScheme()
    ThemeColor.PURPLE -> lightColorScheme()
    ThemeColor.BLUE -> ThemePalettes.blue
    ThemeColor.TEAL -> ThemePalettes.teal
    ThemeColor.GREEN -> ThemePalettes.green
    ThemeColor.ORANGE -> ThemePalettes.orange
    ThemeColor.RED -> ThemePalettes.red
    ThemeColor.PINK -> ThemePalettes.pink
}

@Composable
fun YomarkTheme(color: ThemeColor, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = remember(color, context) { color.colorScheme(context) }
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
