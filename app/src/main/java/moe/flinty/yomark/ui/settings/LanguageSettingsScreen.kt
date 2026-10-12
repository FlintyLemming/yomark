package moe.flinty.yomark.ui.settings

import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R
import moe.flinty.yomark.ui.AppLanguage

/**
 * 语言：第一项跟随系统，下面是有译文的几种，各自用自己的语言写名字（见 [AppLanguage]）。
 * 选了即时生效：Activity 重建，这一页直接换成新语言。
 */
@Composable
fun LanguageSettingsScreen(
    language: AppLanguage,
    onLanguageChange: (AppLanguage) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(title = stringResource(R.string.settings_language), onNavigateUp = onNavigateUp, modifier = modifier) {
        item {
            PageTopSpacer()
            SettingsGroup(Modifier.selectableGroup()) {
                AppLanguage.entries.forEach { option ->
                    RadioRow(
                        title = languageLabel(option),
                        selected = language == option,
                        onClick = { if (option != language) onLanguageChange(option) },
                    )
                }
            }
        }
    }
}

/** 「跟随系统」随界面语言翻译，其余是语言自己的名字。 */
@Composable
internal fun languageLabel(language: AppLanguage): String =
    language.nativeName ?: stringResource(R.string.settings_language_system)
