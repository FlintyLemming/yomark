package moe.flinty.yomark.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.activity.ComponentActivity
import java.util.Locale

/**
 * 界面语言。第一项「跟随系统」，其余是有译文的几种，名字一律用它自己的语言写（不随界面语言变），
 * 看不懂当前语言的人也认得出自己那一项。
 *
 * [tag] 是 BCP 47 标签，同 res/xml/locales_config.xml 里的一一对应：中文用文字（Hans / Hant）而不是地区区分，
 * 香港、澳门的系统落到繁体，新加坡的落到简体。资源目录对应 values-zh、values-b+zh+Hant、values-ja、values-ko，
 * 英文是缺省的 values/：没有译文的语言都回落到英文。
 */
enum class AppLanguage(val tag: String?, val nativeName: String?) {
    SYSTEM(null, null),
    CHINESE_SIMPLIFIED("zh-Hans", "简体中文"),
    CHINESE_TRADITIONAL("zh-Hant", "繁體中文"),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    KOREAN("ko", "한국어");

    val locale: Locale? get() = tag?.let(Locale::forLanguageTag)

    companion object {
        /** 把一个 locale 对到某一项；对不上（系统设置里不会出现，以防万一）就当跟随系统。 */
        fun of(locale: Locale?): AppLanguage {
            if (locale == null) return SYSTEM
            return when (locale.language) {
                "zh" -> if (isTraditional(locale)) CHINESE_TRADITIONAL else CHINESE_SIMPLIFIED
                "en" -> ENGLISH
                "ja" -> JAPANESE
                "ko" -> KOREAN
                else -> SYSTEM
            }
        }

        private fun isTraditional(locale: Locale) =
            locale.script == "Hant" || (locale.script.isEmpty() && locale.country in setOf("TW", "HK", "MO"))
    }
}

/**
 * 应用自己的界面语言，和系统语言分开设。
 *
 * Android 13 起交给系统的 LocaleManager：选择由系统保存，系统设置「应用语言」里也能改，两边是同一个值；
 * 改了之后系统自己让各个 Activity 重建。
 *
 * Android 10–12 没有这套，自己记在 SharedPreferences 里（DataStore 是异步的，attachBaseContext 等不了），
 * 每个 Activity 建出来时换上这个语言的 Context（[YomarkActivity]）。只换 Activity 的，Application 的不换，
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

    /** Android 12 及以下：给 Activity 换上选定语言的 Context。跟随系统、或 13 及以上时原样返回。 */
    internal fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val locale = storedTag(base)?.let(Locale::forLanguageTag)
        // 默认 Locale 也跟着换（日期、数字的格式）；改回跟随系统时换回系统的
        Locale.setDefault(locale ?: Resources.getSystem().configuration.locales[0])
        if (locale == null) return base
        val config = Configuration(base.resources.configuration).apply { setLocales(LocaleList(locale)) }
        return base.createConfigurationContext(config)
    }

    /** Android 12 及以下：这个 Activity 建出来时用的语言。 */
    internal fun storedTag(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
}

/**
 * 各 Activity 的基类，只为 Android 10–12 上的应用内语言（见 [AppLocale]）：
 * 建出来时换上选定语言的 Context；回到前台时语言已经改过了（在设置里改的，这一页压在下面）就重建一次。
 * Android 13 起这两件事系统都做了，这里什么也不做。
 */
open class YomarkActivity : ComponentActivity() {

    private var createdWithTag: String? = null

    override fun attachBaseContext(newBase: Context) {
        createdWithTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else AppLocale.storedTag(newBase)
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && AppLocale.storedTag(this) != createdWithTag) {
            recreate()
        }
    }
}
