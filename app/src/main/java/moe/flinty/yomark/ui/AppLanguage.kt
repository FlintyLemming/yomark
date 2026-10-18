package moe.flinty.yomark.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * 界面语言。第一项「跟随系统」，其余是有译文的几种，名字一律用它自己的语言写（不随界面语言变），
 * 看不懂当前语言的人也认得出自己那一项。
 *
 * [tag] 是 BCP 47 标签，同 res/xml/locales_config.xml 里的一一对应。资源目录：简体中文是 values-zh，
 * 英文是缺省的 values/，没有译文的语言都回落到英文。
 */
enum class AppLanguage(val tag: String?, val nativeName: String?) {
    SYSTEM(null, null),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    ENGLISH("en", "English");

    companion object {
        /** 把一个 locale 对到某一项；对不上（系统设置里不会出现，以防万一）就当跟随系统。 */
        fun of(locale: Locale?): AppLanguage = when (locale?.language) {
            "zh" -> CHINESE_SIMPLIFIED
            "en" -> ENGLISH
            else -> SYSTEM
        }
    }
}

/**
 * 应用自己的界面语言，和系统语言分开设。
 *
 * Android 13 起交给系统的 LocaleManager：选择由系统保存，系统设置「应用语言」里也能改，两边是同一个值；
 * 改了之后系统自己让各个 Activity 重建。
 *
 * Android 10–12 没有这套，自己记在 SharedPreferences 里（DataStore 是异步的，attachBaseContext 等不了），
 * 每个 Activity 建出来时换上这个语言（[localeFor]，由 [YomarkActivity] 换）。只换 Activity 的，Application 的不换，
 * 所以界面文字要从 Activity / Compose 的 Context 取，不要从 applicationContext 取。
 */
object AppLocale {

    private const val PREFS = "app_locale"
    private const val KEY_TAG = "tag"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            AppLanguage.of(if (locales.isEmpty) null else locales[0])
        } else {
            AppLanguage.of(storedTag(context)?.let(Locale::forLanguageTag))
        }

    fun set(activity: ComponentActivity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .apply { if (language.tag == null) remove(KEY_TAG) else putString(KEY_TAG, language.tag) }
                .commit()
            activity.recreate()
        }
    }

    /**
     * Android 12 及以下：Activity 建出来时该换成的语言，跟随系统、或 13 及以上时为 null，不换。
     * 默认 Locale 也跟着换（日期、数字的格式）；改回跟随系统时换回系统的。
     */
    internal fun localeFor(base: Context): Locale? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        val locale = storedTag(base)?.let(Locale::forLanguageTag)
        Locale.setDefault(locale ?: Resources.getSystem().configuration.locales[0])
        return locale
    }

    /** Android 12 及以下：这个 Activity 建出来时用的语言。 */
    internal fun storedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
}
