package moe.flinty.yomark.ui

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * 每个 Activity 在 super.onCreate 之前调一次。
 *
 * 边到边，系统栏图标一律深色。界面只有浅色一套（主题色可换，见 YomarkTheme，但不跟随系统深色模式），
 * 所以 detectDarkMode 恒为 false：手机开着深色模式时图标也不能变白，否则落在浅底上看不见。
 * Android 15 起系统强制边到边、状态栏透明，不声明的话父主题的白色图标就是这么看不见的。
 * 用 auto 而不是 light：三键导航时由系统在按钮后面垫一层半透明底，手势导航时什么都不垫。
 * 各屏自己让开系统栏和挖孔（WindowInsets），不靠系统把内容往里挤。
 */
fun ComponentActivity.enableLightEdgeToEdge() {
    val lightBars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { false }
    enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
}
