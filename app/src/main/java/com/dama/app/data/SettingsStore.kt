package com.dama.app.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dama.app.core.model.MaskStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本地设置（spec §12 M5 的「记住上次样式」）。
 *
 * 全部是无隐私含义的偏好项——这里不存任何图像内容、识别结果或用途水印文案。
 * MaskPlan.style 是全局单值，所以样式只需要存一个枚举名。
 */
class SettingsStore internal constructor(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    val lastStyle: Flow<MaskStyle> = store.data.map { prefs ->
        val raw = prefs[KEY_STYLE] ?: return@map MaskStyle.SOLID
        // 枚举名是持久化格式的一部分：删掉或改名一个样式后，旧安装里的值会读不回来。
        // 读不回来就退回 SOLID，而不是崩在启动路径上。
        runCatching { MaskStyle.valueOf(raw) }.getOrDefault(MaskStyle.SOLID)
    }

    suspend fun setLastStyle(style: MaskStyle) {
        store.edit { it[KEY_STYLE] = style.name }
    }

    val onboardingSeen: Flow<Boolean> = store.data.map { it[KEY_ONBOARDING] ?: false }

    suspend fun markOnboardingSeen() {
        store.edit { it[KEY_ONBOARDING] = true }
    }

    @VisibleForTesting
    internal suspend fun writeRawStyleForTest(raw: String) {
        store.edit { it[KEY_STYLE] = raw }
    }

    private companion object {
        val KEY_STYLE = stringPreferencesKey("last_style")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_seen")
    }
}
