package moe.flinty.yomark.data

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
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.theme.ThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本地设置（spec §12 M5 的「记住上次样式」）。
 *
 * 全部是无隐私含义的偏好项——这里不存任何图像内容、识别结果或用途水印文案。
 * 用途水印只存外观（颜色、角度、透明度、密度），文案照旧只活在当次会话里。
 * 编辑器的画笔（下一次打码用的样式）存样式的枚举名加各样式的参数；每块码自己的样式只活在 MaskPlan 里。
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

    /**
     * 画笔里各样式的参数：色块颜色、马赛克粗细、模糊强度、马克笔颜色与浓淡、表情与它的底色和排法。
     * 与 lastStyle 一样是编辑器里记住的偏好，不是设置页上的项，「恢复默认设置」不动它。
     *
     * 读回来一律 normalized()：手改过的或以后改了范围的旧值收回范围内，色块不会变成半透明的，
     * 马克笔也不会变成不透明的。
     */
    val maskOptions: Flow<MaskOptions> = store.data.map { prefs ->
        val fallback = MaskOptions()
        MaskOptions(
            solidColor = prefs[KEY_SOLID_COLOR] ?: fallback.solidColor,
            pixelBlockDivisor = prefs[KEY_PIXEL_DIVISOR] ?: fallback.pixelBlockDivisor,
            blurRadiusRatio = prefs[KEY_BLUR_RATIO] ?: fallback.blurRadiusRatio,
            markerColor = prefs[KEY_MARKER_COLOR] ?: fallback.markerColor,
            emoji = prefs[KEY_EMOJI] ?: fallback.emoji,
            emojiBackground = prefs[KEY_EMOJI_BACKGROUND] ?: fallback.emojiBackground,
            emojiTiled = prefs[KEY_EMOJI_TILED] ?: fallback.emojiTiled,
        ).normalized()
    }

    suspend fun setMaskOptions(options: MaskOptions) {
        val o = options.normalized()
        store.edit {
            it[KEY_SOLID_COLOR] = o.solidColor
            it[KEY_PIXEL_DIVISOR] = o.pixelBlockDivisor
            it[KEY_BLUR_RATIO] = o.blurRadiusRatio
            it[KEY_MARKER_COLOR] = o.markerColor
            it[KEY_EMOJI] = o.emoji
            it[KEY_EMOJI_BACKGROUND] = o.emojiBackground
            it[KEY_EMOJI_TILED] = o.emojiTiled
        }
    }

    @VisibleForTesting
    internal suspend fun writeRawMaskOptionsForTest(markerColor: Int, pixelDivisor: Int, solidColor: Int) {
        store.edit {
            it[KEY_MARKER_COLOR] = markerColor
            it[KEY_PIXEL_DIVISOR] = pixelDivisor
            it[KEY_SOLID_COLOR] = solidColor
        }
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

    /**
     * 导出前提醒：还有圈出但没打码的内容时先弹对话框。出厂开着。
     *
     * 不放进 RecognitionConfig：它不影响识别，放进去的话一拨开关编辑器就会重跑识别。
     */
    val pendingExportReminder: Flow<Boolean> = store.data.map { it[KEY_EXPORT_REMINDER] ?: true }

    suspend fun setPendingExportReminder(on: Boolean) {
        store.edit { it[KEY_EXPORT_REMINDER] = on }
    }

    /**
     * 识别动效：扫光或磨砂（见 ScanStyle）。出厂是扫光。
     *
     * 同样不放进 RecognitionConfig：它只是识别时画面上的过渡，放进去的话一换样式编辑器就会重跑识别。
     * 认不出的值退回扫光，理由与 lastStyle 一致。
     */
    val scanStyle: Flow<ScanStyle> = store.data.map { it[KEY_SCAN_STYLE].toEnum(ScanStyle.SWEEP) }

    suspend fun setScanStyle(style: ScanStyle) {
        store.edit { it[KEY_SCAN_STYLE] = style.name }
    }

    /**
     * 主题色：跟随系统（莫奈取色）或一个预设色（见 ThemeColor）。出厂跟随系统。
     * 认不出的值退回跟随系统，理由与 lastStyle 一致。
     */
    val themeColor: Flow<ThemeColor> = store.data.map { it[KEY_THEME_COLOR].toEnum(ThemeColor.SYSTEM) }

    suspend fun setThemeColor(color: ThemeColor) {
        store.edit { it[KEY_THEME_COLOR] = color.name }
    }

    // ---------- 识别方案（2026-09-04 增补设计 §4）----------

    /**
     * 逐项容错：认不出的枚举名只让**该项**退回出厂默认，不牵连其余项，
     * 也绝不在启动路径上抛。理由与 lastStyle 一致。
     *
     * 人脸、条码的「关」原先是识别模式里的一个选项（存在 KEY_FACE / KEY_BARCODE 里的 OFF），
     * 现在挪到了各自的处理方式。老安装里存着 OFF、还没有处理方式这一项的，读成「关」，
     * 识别模式退回出厂值——不迁的话，关掉过人脸的用户升级后会突然又被打码。
     */
    val recognitionConfig: Flow<RecognitionConfig> = store.data.map { prefs ->
        val fallback = RecognitionConfig()
        RecognitionConfig(
            textEngine = prefs[KEY_TEXT_ENGINE].toEnum(fallback.textEngine),
            barcode = prefs[KEY_BARCODE].toEnum(fallback.barcode),
            barcodeState = prefs[KEY_BARCODE_STATE].toEnum(
                if (prefs[KEY_BARCODE] == LEGACY_OFF) RuleState.OFF else fallback.barcodeState,
            ),
            face = prefs[KEY_FACE].toEnum(fallback.face),
            faceState = prefs[KEY_FACE_STATE].toEnum(
                if (prefs[KEY_FACE] == LEGACY_OFF) RuleState.OFF else fallback.faceState,
            ),
            semantic = prefs[KEY_SEMANTIC].toEnum(fallback.semantic),
            outlineUnanchoredNames = prefs[KEY_UNANCHORED_NAMES] ?: fallback.outlineUnanchoredNames,
            ruleOverrides = RecognitionConfig.parseOverrides(prefs[KEY_RULES].orEmpty()),
        )
    }

    suspend fun setRecognitionConfig(config: RecognitionConfig) {
        store.edit {
            it[KEY_TEXT_ENGINE] = config.textEngine.name
            it[KEY_BARCODE] = config.barcode.name
            it[KEY_BARCODE_STATE] = config.barcodeState.name
            it[KEY_FACE] = config.face.name
            it[KEY_FACE_STATE] = config.faceState.name
            it[KEY_SEMANTIC] = config.semantic.name
            it[KEY_UNANCHORED_NAMES] = config.outlineUnanchoredNames
            it[KEY_RULES] = config.encodeOverrides()
        }
    }

    /**
     * 设置一级页的「恢复默认设置」：识别方案（文字、人脸、条码、文字识别）和导出前提醒回到出厂值。
     * 外观（主题色、识别动效）不动；编辑器里记住的样式、用途水印外观不是设置页上的项，也不动。
     *
     * 做法是删掉这些键，而不是写一份当下的出厂值：读的时候缺省就是出厂值，和新装一样，
     * 出厂值以后再改也不会被这一下冻住（与 RecognitionConfig.ruleOverrides 只存差异同一条理由）。
     */
    suspend fun resetSettings() {
        store.edit {
            it.remove(KEY_TEXT_ENGINE)
            it.remove(KEY_BARCODE)
            it.remove(KEY_BARCODE_STATE)
            it.remove(KEY_FACE)
            it.remove(KEY_FACE_STATE)
            it.remove(KEY_SEMANTIC)
            it.remove(KEY_UNANCHORED_NAMES)
            it.remove(KEY_RULES)
            it.remove(KEY_EXPORT_REMINDER)
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    @VisibleForTesting
    internal suspend fun writeRawTextEngineForTest(raw: String) {
        store.edit { it[KEY_TEXT_ENGINE] = raw }
    }

    /** 模拟升级前的老安装：只有识别模式这一项，处理方式还没存过。 */
    @VisibleForTesting
    internal suspend fun writeLegacyDetectorModesForTest(face: String?, barcode: String?) {
        store.edit {
            if (face != null) it[KEY_FACE] = face
            if (barcode != null) it[KEY_BARCODE] = barcode
            it.remove(KEY_FACE_STATE)
            it.remove(KEY_BARCODE_STATE)
        }
    }

    @VisibleForTesting
    internal suspend fun writeRawStyleForTest(raw: String) {
        store.edit { it[KEY_STYLE] = raw }
    }

    @VisibleForTesting
    internal suspend fun writeRawScanStyleForTest(raw: String) {
        store.edit { it[KEY_SCAN_STYLE] = raw }
    }

    @VisibleForTesting
    internal suspend fun writeRawThemeColorForTest(raw: String) {
        store.edit { it[KEY_THEME_COLOR] = raw }
    }

    private companion object {
        val KEY_STYLE = stringPreferencesKey("last_style")
        val KEY_SOLID_COLOR = intPreferencesKey("mask_solid_color")
        val KEY_PIXEL_DIVISOR = intPreferencesKey("mask_pixel_divisor")
        val KEY_BLUR_RATIO = floatPreferencesKey("mask_blur_ratio")
        val KEY_MARKER_COLOR = intPreferencesKey("mask_marker_color")
        val KEY_EMOJI = stringPreferencesKey("mask_emoji")
        val KEY_EMOJI_BACKGROUND = intPreferencesKey("mask_emoji_background")
        val KEY_EMOJI_TILED = booleanPreferencesKey("mask_emoji_tiled")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_seen")
        val KEY_PURPOSE_COLOR = intPreferencesKey("purpose_watermark_color")
        val KEY_PURPOSE_ANGLE = floatPreferencesKey("purpose_watermark_angle")
        val KEY_PURPOSE_OPACITY = floatPreferencesKey("purpose_watermark_opacity")
        val KEY_PURPOSE_DENSITY = floatPreferencesKey("purpose_watermark_density")
        val KEY_EXPORT_REMINDER = booleanPreferencesKey("pending_export_reminder")
        val KEY_SCAN_STYLE = stringPreferencesKey("scan_style")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        /**
         * v2：出厂 OCR 从 ML Kit 换成了 PP-OCR。setRecognitionConfig 每次都整份写入，
         * 动过任何一项设置的老安装里都存着一个 BOTH，沿用旧键就会永远停在 ML Kit 上。
         * 换键让所有安装都回到新的出厂值一次，之后照常记住用户的选择。
         */
        val KEY_TEXT_ENGINE = stringPreferencesKey("recognition_text_engine_v2")
        /** v2：出厂条码从 LOOSE 改成 STRICT，换键的理由同 KEY_TEXT_ENGINE。 */
        val KEY_BARCODE = stringPreferencesKey("recognition_barcode_v2")
        val KEY_BARCODE_STATE = stringPreferencesKey("recognition_barcode_state")
        val KEY_FACE = stringPreferencesKey("recognition_face")
        val KEY_FACE_STATE = stringPreferencesKey("recognition_face_state")
        val KEY_RULES = stringPreferencesKey("recognition_rules")
        val KEY_SEMANTIC = stringPreferencesKey("recognition_semantic")
        val KEY_UNANCHORED_NAMES = booleanPreferencesKey("recognition_unanchored_names")

        /** 人脸、条码的识别模式里原先那个「关」。只在读老安装时认它，见 recognitionConfig。 */
        const val LEGACY_OFF = "OFF"
    }
}
