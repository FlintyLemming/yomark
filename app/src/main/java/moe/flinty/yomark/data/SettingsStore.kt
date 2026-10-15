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
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.debug.TraceLog
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.engine.genai.ExternalModelSettings
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.export.PurposeWatermarkStyle
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.theme.ThemeColor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 本地设置（spec §12 M5 的「记住上次样式」）。
 *
 * 全部是无隐私含义的偏好项——这里不存任何图像内容、识别结果或用途水印文案。唯一的凭据是用户自己填的外部模型 API Key。
 * 用途水印只存外观（颜色、角度、透明度、密度），文案照旧只活在当次会话里。
 * 编辑器里「打码」上次选的样式存枚举名，画笔里各样式（连马克笔、画笔）的参数逐项存；每块码自己的样式只活在 MaskPlan 里。
 */
class SettingsStore internal constructor(
    private val store: DataStore<Preferences>,
    /** 系统语言，品牌水印字样没选过时按它定。测试换成固定值。 */
    private val locale: () -> Locale = Locale::getDefault,
) {

    constructor(context: Context) : this(context.applicationContext.settingsDataStore)

    /**
     * 「打码」里上次选的样式：识别出来直接打码的用它，下次打开编辑器时画笔也是它。
     * 只会是色块、马赛克、模糊之一：之前的版本记的是画笔停在哪一种，读回来是马克笔、画笔的按色块算——
     * 打码的样式只从「打码」里来。
     */
    val lastStyle: Flow<MaskStyle> = store.data.map { prefs ->
        val raw = prefs[KEY_STYLE] ?: return@map MaskStyle.SOLID
        // 枚举名是持久化格式的一部分：删掉或改名一个样式后，旧安装里的值会读不回来。
        // 读不回来就退回 SOLID，而不是崩在启动路径上。抹除并进色块（近似色）后，旧安装里的抹除也走这一条，
        // 近似色由 maskOptions 接上；去掉的表情也走这一条
        runCatching { MaskStyle.valueOf(raw) }.getOrNull()?.takeIf { it.redacts } ?: MaskStyle.SOLID
    }

    suspend fun setLastStyle(style: MaskStyle) {
        store.edit { it[KEY_STYLE] = style.name }
    }

    /**
     * 画笔里各样式的参数：色块颜色与近似色、马赛克粗细、模糊强度、马克笔颜色与浓淡，马克笔、画笔的画法与粗细。
     * 与 lastStyle 一样是编辑器里记住的偏好，不是设置页上的项，「恢复默认设置」不动它。
     *
     * 读回来一律 normalized()：手改过的或以后改了范围的旧值收回范围内，色块不会变成半透明的，
     * 马克笔也不会变成不透明的。
     *
     * 近似色还没记过、上次的样式是抹除（旧安装），就当开着：抹除就是现在的近似色，画笔照旧是那个样子。
     * 画笔动过一次之后两项都写下了，这一条就不再起作用。
     */
    val maskOptions: Flow<MaskOptions> = store.data.map { prefs ->
        val fallback = MaskOptions()
        MaskOptions(
            solidColor = prefs[KEY_SOLID_COLOR] ?: fallback.solidColor,
            solidNear = prefs[KEY_SOLID_NEAR] ?: (prefs[KEY_STYLE] == LEGACY_ERASE),
            pixelBlockDivisor = prefs[KEY_PIXEL_DIVISOR] ?: fallback.pixelBlockDivisor,
            blurRadiusRatio = prefs[KEY_BLUR_RATIO] ?: fallback.blurRadiusRatio,
            markerColor = prefs[KEY_MARKER_COLOR] ?: fallback.markerColor,
            markerShape = prefs[KEY_MARKER_SHAPE].toShape(fallback.markerShape),
            markerWidth = prefs[KEY_MARKER_WIDTH] ?: fallback.markerWidth,
            penColor = prefs[KEY_PEN_COLOR] ?: fallback.penColor,
            penShape = prefs[KEY_PEN_SHAPE].toShape(fallback.penShape),
            penWidth = prefs[KEY_PEN_WIDTH] ?: fallback.penWidth,
        ).normalized()
    }

    suspend fun setMaskOptions(options: MaskOptions) {
        val o = options.normalized()
        store.edit {
            it[KEY_SOLID_COLOR] = o.solidColor
            it[KEY_SOLID_NEAR] = o.solidNear
            it[KEY_PIXEL_DIVISOR] = o.pixelBlockDivisor
            it[KEY_BLUR_RATIO] = o.blurRadiusRatio
            it[KEY_MARKER_COLOR] = o.markerColor
            it[KEY_MARKER_SHAPE] = o.markerShape.name
            it[KEY_MARKER_WIDTH] = o.markerWidth
            it[KEY_PEN_COLOR] = o.penColor
            it[KEY_PEN_SHAPE] = o.penShape.name
            it[KEY_PEN_WIDTH] = o.penWidth
        }
    }

    private fun String?.toShape(fallback: StrokeShape): StrokeShape =
        this?.let { raw -> StrokeShape.entries.firstOrNull { it.name == raw } } ?: fallback

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
     * 换打码样式时一起换掉已有的打码：一张图一般只用一种打码样式，出厂开着。
     * 关掉时换样式只管选中的那一处和之后新打的码。
     */
    val restyleAllMasks: Flow<Boolean> = store.data.map { it[KEY_RESTYLE_ALL] ?: true }

    suspend fun setRestyleAllMasks(on: Boolean) {
        store.edit { it[KEY_RESTYLE_ALL] = on }
    }

    /**
     * 品牌水印的字样（见 BrandWatermarkStyle）。没选过就按**读的时候**的系统语言定，不在第一次读时写下：
     * 用户换了系统语言，没选过的人跟着换；选过一次之后就一直是那一版。
     * 认不出的值同没选过，理由与 lastStyle 一致。
     */
    val brandWatermarkStyle: Flow<BrandWatermarkStyle> = store.data.map {
        it[KEY_BRAND_WATERMARK].toEnum(BrandWatermarkStyle.defaultFor(locale()))
    }

    /**
     * 选字样。[unlocked]（能去水印）时选字样就是要水印，一并把 [brandWatermarkOff] 记成 false；
     * 没解锁时不碰它——否则以后解锁了，水印还在。
     */
    suspend fun setBrandWatermarkStyle(style: BrandWatermarkStyle, unlocked: Boolean = false) {
        store.edit {
            it[KEY_BRAND_WATERMARK] = style.name
            if (unlocked) it[KEY_BRAND_WATERMARK_OFF] = false
        }
    }

    /**
     * 设置里选了「无水印」。只在解锁后才能选，没选过是 null，怎么算见 export.brandWatermarkApplies：
     * 解锁了、没选过就按不加算，所以买成或点了「去除水印」的那一下，不用回头改这里水印就没了。
     */
    val brandWatermarkOff: Flow<Boolean?> = store.data.map { it[KEY_BRAND_WATERMARK_OFF] }

    suspend fun setBrandWatermarkOff() {
        store.edit { it[KEY_BRAND_WATERMARK_OFF] = true }
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

    /**
     * 调试信息（设置 → 关于，连点版本号打开）：编辑页顶栏多一个按钮，看每次识别各环节的输入输出。出厂关着。
     * 这里存的只是开没开和保留几次；记下来的内容存在应用私有目录里（见 debug/TraceStore.kt），关掉时一并删掉。
     */
    val debugTrace: Flow<Boolean> = store.data.map { it[KEY_DEBUG_TRACE] ?: false }

    suspend fun setDebugTrace(on: Boolean) {
        store.edit { it[KEY_DEBUG_TRACE] = on }
    }

    /** 调试记录保留最近几次识别。只认 [TraceLog.CAPACITY_OPTIONS] 里的档，别的值退回出厂的 20 次。 */
    val debugTraceCapacity: Flow<Int> = store.data.map { prefs ->
        prefs[KEY_DEBUG_TRACE_CAPACITY]?.takeIf { it in TraceLog.CAPACITY_OPTIONS } ?: TraceLog.DEFAULT_CAPACITY
    }

    suspend fun setDebugTraceCapacity(count: Int) {
        store.edit { it[KEY_DEBUG_TRACE_CAPACITY] = count }
    }

    /**
     * 开发者里的「显示识别框」：编辑页画布上叠出文字识别认出的每一行的框，看漏认、框歪在哪儿。出厂关着。
     * 只在调试信息也开着时才画；调试信息关掉再打开，它还是原来的样子。
     */
    val showTextBoxes: Flow<Boolean> = store.data.map { it[KEY_SHOW_TEXT_BOXES] ?: false }

    suspend fun setShowTextBoxes(on: Boolean) {
        store.edit { it[KEY_SHOW_TEXT_BOXES] = on }
    }

    /**
     * 实验室里的外部模型：开关，和 AI 页上填的接口地址、API Key、模型（见 ExternalModelSettings）。
     * 不进 [recognitionConfig]：改一个字编辑器不用重跑识别，它每次用时现读。
     * 只在这几项变了时才发出新值：编辑器收到要重新探测一次「AI 增强识别」能不能用。
     *
     * key 和别的设置一样存在应用私有目录里；manifest 里 allowBackup="false"，不进备份。
     */
    val externalModel: Flow<ExternalModelSettings> = store.data.map(::readExternalModel).distinctUntilChanged()

    /** 改外部模型的设置。读、改、写在一次 edit 里，两处同时改不会互相盖掉。 */
    suspend fun updateExternalModel(change: (ExternalModelSettings) -> ExternalModelSettings) {
        store.edit {
            val next = change(readExternalModel(it))
            it[KEY_EXTERNAL_ON] = next.enabled
            it[KEY_EXTERNAL_URL] = next.baseUrl.trim()
            it[KEY_EXTERNAL_KEY] = next.apiKey.trim()
            it[KEY_EXTERNAL_MODEL] = next.model.trim()
        }
    }

    private fun readExternalModel(prefs: Preferences) = ExternalModelSettings(
        enabled = prefs[KEY_EXTERNAL_ON] ?: false,
        baseUrl = prefs[KEY_EXTERNAL_URL].orEmpty(),
        apiKey = prefs[KEY_EXTERNAL_KEY].orEmpty(),
        model = prefs[KEY_EXTERNAL_MODEL].orEmpty(),
    )

    // ---------- 识别方案（2026-09-04 增补设计 §4）----------

    /**
     * 逐项容错：认不出的枚举名只让**该项**退回出厂默认，不牵连其余项，
     * 也绝不在启动路径上抛。理由与 lastStyle 一致。
     *
     * 人脸、条码的「关」原先是识别模式里的一个选项（存在 KEY_FACE / KEY_BARCODE 里的 OFF），
     * 现在挪到了各自的处理方式。老安装里存着 OFF、还没有处理方式这一项的，读成「关」，
     * 识别模式退回出厂值——不迁的话，关掉过人脸的用户升级后会突然又被打码。
     * 处理方式里存着已经去掉的「仅圈出」的，同样读成「关」（见 RuleState.fromName），不落回出厂的打码。
     */
    val recognitionConfig: Flow<RecognitionConfig> = store.data.map { prefs ->
        val fallback = RecognitionConfig()
        RecognitionConfig(
            textEngine = prefs[KEY_TEXT_ENGINE].toEnum(fallback.textEngine),
            barcode = prefs[KEY_BARCODE].toEnum(fallback.barcode),
            barcodeState = prefs[KEY_BARCODE_STATE].toRuleState(
                if (prefs[KEY_BARCODE] == LEGACY_OFF) RuleState.OFF else fallback.barcodeState,
            ),
            face = prefs[KEY_FACE].toEnum(fallback.face),
            faceState = prefs[KEY_FACE_STATE].toRuleState(
                if (prefs[KEY_FACE] == LEGACY_OFF) RuleState.OFF else fallback.faceState,
            ),
            chatState = prefs[KEY_CHAT_STATE].toRuleState(fallback.chatState),
            semantic = prefs[KEY_SEMANTIC].toEnum(fallback.semantic),
            maskUnanchoredNames = prefs[KEY_UNANCHORED_NAMES] ?: fallback.maskUnanchoredNames,
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
            it[KEY_CHAT_STATE] = config.chatState.name
            it[KEY_SEMANTIC] = config.semantic.name
            it[KEY_UNANCHORED_NAMES] = config.maskUnanchoredNames
            it[KEY_RULES] = config.encodeOverrides()
        }
    }

    /**
     * 设置 → 关于里的「恢复默认设置」：识别方案（文字、人脸、条码、文字识别）和导出的水印样式回到出厂值。
     * 水印样式回到出厂值，对解锁了的人就是无水印（见 [brandWatermarkOff]）。
     * 外观（主题色、识别动效）不动；编辑器里记住的样式、用途水印外观不是设置页上的项，也不动；
     * 调试信息的开关在「开发者」里自己关。实验室里的外部模型关掉，填过的接口地址、key、模型留着，再打开不用重填。
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
            it.remove(KEY_CHAT_STATE)
            it.remove(KEY_SEMANTIC)
            it.remove(KEY_UNANCHORED_NAMES)
            it.remove(KEY_RULES)
            it.remove(KEY_RESTYLE_ALL)
            it.remove(KEY_BRAND_WATERMARK)
            it.remove(KEY_BRAND_WATERMARK_OFF)
            it.remove(KEY_EXTERNAL_ON)
        }
    }

    private inline fun <reified T : Enum<T>> String?.toEnum(fallback: T): T =
        this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback

    private fun String?.toRuleState(fallback: RuleState): RuleState = this?.let { RuleState.fromName(it) } ?: fallback

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

    /** 模拟老安装：处理方式里存着一个原样的名字（比如去掉了的 OUTLINED）。 */
    @VisibleForTesting
    internal suspend fun writeRawHandlingForTest(face: String, chat: String, rules: String) {
        store.edit {
            it[KEY_FACE_STATE] = face
            it[KEY_CHAT_STATE] = chat
            it[KEY_RULES] = rules
        }
    }

    /** 模拟升级前的老安装：聊天记录的处理方式存在换键前的旧键里。 */
    @VisibleForTesting
    internal suspend fun writeLegacyChatStateForTest(raw: String) {
        store.edit { it[stringPreferencesKey("recognition_chat_state")] = raw }
    }

    @VisibleForTesting
    internal suspend fun writeRawStyleForTest(raw: String) {
        store.edit { it[KEY_STYLE] = raw }
    }

    @VisibleForTesting
    internal suspend fun writeRawBrandWatermarkForTest(raw: String) {
        store.edit { it[KEY_BRAND_WATERMARK] = raw }
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
        val KEY_SOLID_NEAR = booleanPreferencesKey("mask_solid_near")
        /**
         * v2：马赛克、模糊的出厂值从最弱一档改成了中间一档（2026-10-12）。setMaskOptions 每次都整份写入，
         * 动过画笔任何一项的老安装里都存着最弱一档，沿用旧键就会一直透得太多。换键的理由同 KEY_TEXT_ENGINE。
         */
        val KEY_PIXEL_DIVISOR = intPreferencesKey("mask_pixel_divisor_v2")
        /** v2：理由同 KEY_PIXEL_DIVISOR。 */
        val KEY_BLUR_RATIO = floatPreferencesKey("mask_blur_ratio_v2")
        val KEY_MARKER_COLOR = intPreferencesKey("mask_marker_color")
        val KEY_MARKER_SHAPE = stringPreferencesKey("mask_marker_shape")
        val KEY_MARKER_WIDTH = floatPreferencesKey("mask_marker_width")
        val KEY_PEN_COLOR = intPreferencesKey("mask_pen_color")
        val KEY_PEN_SHAPE = stringPreferencesKey("mask_pen_shape")
        val KEY_PEN_WIDTH = floatPreferencesKey("mask_pen_width")
        val KEY_ONBOARDING = booleanPreferencesKey("onboarding_seen")
        val KEY_PURPOSE_COLOR = intPreferencesKey("purpose_watermark_color")
        val KEY_PURPOSE_ANGLE = floatPreferencesKey("purpose_watermark_angle")
        val KEY_PURPOSE_OPACITY = floatPreferencesKey("purpose_watermark_opacity")
        val KEY_PURPOSE_DENSITY = floatPreferencesKey("purpose_watermark_density")
        val KEY_RESTYLE_ALL = booleanPreferencesKey("restyle_all_masks")
        val KEY_BRAND_WATERMARK = stringPreferencesKey("brand_watermark_style")
        val KEY_BRAND_WATERMARK_OFF = booleanPreferencesKey("brand_watermark_off")
        val KEY_SCAN_STYLE = stringPreferencesKey("scan_style")
        val KEY_THEME_COLOR = stringPreferencesKey("theme_color")
        val KEY_DEBUG_TRACE = booleanPreferencesKey("debug_trace")
        val KEY_DEBUG_TRACE_CAPACITY = intPreferencesKey("debug_trace_capacity")
        val KEY_SHOW_TEXT_BOXES = booleanPreferencesKey("debug_show_text_boxes")
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
        /** v2：出厂从打码改成关着（挪进实验室），换键的理由同 KEY_TEXT_ENGINE。 */
        val KEY_CHAT_STATE = stringPreferencesKey("recognition_chat_state_v2")
        val KEY_RULES = stringPreferencesKey("recognition_rules")
        val KEY_SEMANTIC = stringPreferencesKey("recognition_semantic")
        val KEY_UNANCHORED_NAMES = booleanPreferencesKey("recognition_unanchored_names")
        val KEY_EXTERNAL_ON = booleanPreferencesKey("external_model_enabled")
        val KEY_EXTERNAL_URL = stringPreferencesKey("external_model_base_url")
        val KEY_EXTERNAL_KEY = stringPreferencesKey("external_model_api_key")
        val KEY_EXTERNAL_MODEL = stringPreferencesKey("external_model_name")

        /** 人脸、条码的识别模式里原先那个「关」。只在读老安装时认它，见 recognitionConfig。 */
        const val LEGACY_OFF = "OFF"

        /** 原先的样式「抹除」，现在是色块的近似色。只在读老安装时认它，见 lastStyle、maskOptions。 */
        const val LEGACY_ERASE = "ERASE"
    }
}
