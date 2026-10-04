package com.youma.app.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.youma.app.core.model.MaskStyle
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.FaceOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.TextEngineOption
import com.youma.app.export.PurposeWatermarkStyle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本地设置（spec §12 M5 的「记住上次样式」）。
 *
 * 全部是无隐私含义的偏好项——这里不存任何图像内容、识别结果或用途水印文案。
 * 用途水印只存外观（颜色、角度、透明度、密度），文案照旧只活在当次会话里。
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

    /** 读回来一律 normalized()：手改过的或旧版本写的越界值收回范围内，不让水印变成不透明的。 */
    val purposeWatermarkStyle: Flow<PurposeWatermarkStyle> = store.data.map { prefs ->
        val fallback = PurposeWatermarkStyle()
        PurposeWatermarkStyle(
            color = prefs[KEY_PURPOSE_COLOR] ?: fallback.color,
            angle = prefs[KEY_PURPOSE_ANGLE] ?: fallback.angle,
            opacity = prefs[KEY_PURPOSE_OPACITY] ?: fallback.opacity,
            density = prefs[KEY_PURPOSE_DENSITY] ?: fallback.density,
        ).normalized()
    }

    suspend fun setPurposeWatermarkStyle(style: PurposeWatermarkStyle) {
        val s = style.normalized()
        store.edit {
            it[KEY_PURPOSE_COLOR] = s.color
            it[KEY_PURPOSE_ANGLE] = s.angle
            it[KEY_PURPOSE_OPACITY] = s.opacity
            it[KEY_PURPOSE_DENSITY] = s.density
        }
    }

    @VisibleForTesting
    internal suspend fun writeRawPurposeOpacityForTest(raw: Float) {
        store.edit { it[KEY_PURPOSE_OPACITY] = raw }
    }

    // ---------- 识别方案（2026-09-04 增补设计 §4）----------

    /**
     * 逐项容错：认不出的枚举名只让**该项**退回出厂默认，不牵连其余项，
     * 也绝不在启动路径上抛。理由与 lastStyle 一致。
     */
    val recognitionConfig: Flow<RecognitionConfig> = store.data.map { prefs ->
        val fallback = RecognitionConfig()
        RecognitionConfig(
            textEngine = prefs[KEY_TEXT_ENGINE].toEnum(fallback.textEngine),
            barcode = prefs[KEY_BARCODE].toEnum(fallback.barcode),
            face = prefs[KEY_FACE].toEnum(fallback.face),
            semantic = prefs[KEY_SEMANTIC].toEnum(fallback.semantic),
            ruleOverrides = RecognitionConfig.parseOverrides(prefs[KEY_RULES].orEmpty()),
        )
    }

    suspend fun setRecognitionConfig(config: RecognitionConfig) {
        store.edit {
            it[KEY_TEXT_ENGINE] = config.textEngine.name
            it[KEY_BARCODE] = config.barcode.name
            it[KEY_FACE] = config.face.name
            it[KEY_SEMANTIC] = config.semantic.name
            it[KEY_RULES] = config.encodeOverrides()
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    @VisibleForTesting
    internal suspend fun writeRawTextEngineForTest(raw: String) {
        store.edit { it[KEY_TEXT_ENGINE] = raw }
    }

    @VisibleForTesting
    internal suspend fun writeRawStyleForTest(raw: String) {
        store.edit { it[KEY_STYLE] = raw }
    }

    private companion object {
        val KEY_STYLE = stringPreferencesKey("last_style")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_seen")
        val KEY_PURPOSE_COLOR = intPreferencesKey("purpose_watermark_color")
        val KEY_PURPOSE_ANGLE = floatPreferencesKey("purpose_watermark_angle")
        val KEY_PURPOSE_OPACITY = floatPreferencesKey("purpose_watermark_opacity")
        val KEY_PURPOSE_DENSITY = floatPreferencesKey("purpose_watermark_density")
        /**
         * v2：出厂 OCR 从 ML Kit 换成了 PP-OCR。setRecognitionConfig 每次都整份写入，
         * 动过任何一项设置的老安装里都存着一个 BOTH，沿用旧键就会永远停在 ML Kit 上。
         * 换键让所有安装都回到新的出厂值一次，之后照常记住用户的选择。
         */
        val KEY_TEXT_ENGINE = stringPreferencesKey("recognition_text_engine_v2")
        val KEY_BARCODE = stringPreferencesKey("recognition_barcode")
        val KEY_FACE = stringPreferencesKey("recognition_face")
        val KEY_RULES = stringPreferencesKey("recognition_rules")
        val KEY_SEMANTIC = stringPreferencesKey("recognition_semantic")
    }
}
