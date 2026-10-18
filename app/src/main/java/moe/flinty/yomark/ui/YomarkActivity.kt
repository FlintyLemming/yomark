package moe.flinty.yomark.ui

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import moe.flinty.yomark.ui.theme.AppThemeMode
import java.util.Locale

/**
 * 各 Activity 的基类，为老系统上应用自己选的界面语言（Android 10–12，见 [AppLocale]）和深色模式（Android 10、11，见 [AppThemeMode]）：
 * 建出来时换上选定的语言、深浅；回到前台时已经改过了（在设置里改的，这一页压在下面）就重建一次。
 * 新的系统这两件事都由系统做，这里什么也不做。
 */
open class YomarkActivity : ComponentActivity() {

    private var createdWithTag: String? = null
    private var createdWithNight: Int? = null

    override fun attachBaseContext(newBase: Context) {
        createdWithTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else AppLocale.storedTag(newBase)
        createdWithNight = AppThemeMode.nightOverride(newBase)
        super.attachBaseContext(withAppConfiguration(newBase, AppLocale.localeFor(newBase), createdWithNight))
    }

    override fun onResume() {
        super.onResume()
        val languageChanged = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && AppLocale.storedTag(this) != createdWithTag
        if (languageChanged || AppThemeMode.nightOverride(this) != createdWithNight) recreate()
    }
}

/**
 * 换上应用自己选的语言（[locale]）和 uiMode 的夜间那一位（[night]），两样都不换（null）就原样返回。
 *
 * createConfigurationContext 收的是「改哪几项」，这里只写要换的，别的留空照系统的；
 * 整份拷一遍再改的话，拷下来那一刻的屏幕宽高、方向也都算成了要改的。
 * 两样在一次里换：外面再套一层的话，外层的 Resources 只按它自己那一次改的建，里层换的就不算了。
 */
internal fun withAppConfiguration(base: Context, locale: Locale?, night: Int?): Context {
    if (locale == null && night == null) return base
    val override = Configuration().apply {
        if (locale != null) setLocales(LocaleList(locale))
        if (night != null) uiMode = night
    }
    return base.createConfigurationContext(override)
}
