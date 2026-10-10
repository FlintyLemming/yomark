package moe.flinty.yomark.ui

import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/**
 * 每个 Activity 在 super.onCreate 之前调一次。
 *
 * 边到边，系统栏图标一律深色。界面只有浅色一套（主题色可换，见 YomarkTheme，但不跟随系统深色模式），
 * 所以图标颜色写死在主题里（windowLightStatusBar / windowLightNavigationBar），手机开着深色模式也不变白。
 * 各屏自己让开系统栏和挖孔（WindowInsets），不靠系统把内容往里挤。
 *
 * 不用 androidx 的 enableEdgeToEdge：它（以及 WindowCompat.enableEdgeToEdge）在各版本分支里都调了
 * Window.setStatusBarColor / setNavigationBarColor 和 LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES，
 * 这些在 Android 15 已弃用，Play 管理中心会据此报「使用已弃用的 API 或参数来实现无边框设计」。
 * 这里只用没弃用的写法：
 * - Android 15 起系统对 targetSdk 35+ 强制边到边、系统栏透明、挖孔区一律可绘制，什么都不用做。
 * - Android 10–14 关掉 decorFitsSystemWindows；系统栏透明由主题给（冷启动的启动窗口也要它），
 *   导航栏的对比度保护沿用系统默认：三键导航时垫一层半透明底，手势导航时什么都不垫。
 * - Android 11–14 挖孔区用 ALWAYS（横屏时挖孔在侧边也照样画进去，和 15 起的行为一致）；
 *   Android 10 没有 ALWAYS，只剩已弃用的 SHORT_EDGES，就保持默认，横屏时由系统让开挖孔。
 */
fun ComponentActivity.enableLightEdgeToEdge() {
    if (Build.VERSION.SDK_INT >= 35) return
    WindowCompat.setDecorFitsSystemWindows(window, false)
    if (Build.VERSION.SDK_INT >= 30) {
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }
}
