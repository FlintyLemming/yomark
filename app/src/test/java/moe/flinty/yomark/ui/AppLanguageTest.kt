package moe.flinty.yomark.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

/** 系统设置「应用语言」里选的，或者 Android 12 及以下自己存的标签，对回设置页上的哪一项。 */
class AppLanguageTest {

    @Test fun noLocaleMeansSystemDefault() {
        assertThat(AppLanguage.of(null)).isEqualTo(AppLanguage.SYSTEM)
    }

    @Test fun eachLanguageRoundTripsThroughItsTag() {
        AppLanguage.entries.filter { it != AppLanguage.SYSTEM }.forEach {
            assertThat(AppLanguage.of(Locale.forLanguageTag(it.tag!!))).isEqualTo(it)
        }
    }

    @Test fun chineseWithARegionIsStillChinese() {
        assertThat(AppLanguage.of(Locale.forLanguageTag("zh-CN"))).isEqualTo(AppLanguage.CHINESE_SIMPLIFIED)
        assertThat(AppLanguage.of(Locale.forLanguageTag("en-GB"))).isEqualTo(AppLanguage.ENGLISH)
    }

    @Test fun anUntranslatedLanguageFallsBackToSystemDefault() {
        assertThat(AppLanguage.of(Locale.forLanguageTag("fr"))).isEqualTo(AppLanguage.SYSTEM)
    }

    /** 系统设置里列的和设置页上的是同一份。 */
    @Test fun theSystemSettingsListTheSameLanguages() {
        val xml = java.io.File("src/main/res/xml/locales_config.xml").readText()
        val listed = Regex("""android:name="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toList()
        assertThat(listed).containsExactlyElementsIn(AppLanguage.entries.mapNotNull { it.tag }).inOrder()
    }
}
