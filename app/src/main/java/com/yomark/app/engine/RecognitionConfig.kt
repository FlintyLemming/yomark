package com.yomark.app.engine

/**
 * OCR 后端。PADDLE = PP-OCRv5 mobile（ONNX Runtime，随包发行）；
 * 其余三档是 ML Kit：BOTH 并行跑两个再取并集，去重交给 CandidateMerger。
 */
enum class TextEngineOption { PADDLE, LATIN, CHINESE, BOTH }

/**
 * 条码的识别模式。认出来之后打码、仅圈出还是干脆不认，由 [RecognitionConfig.barcodeState] 管。
 *
 * LOOSE 连解不出内容的疑似条码也报（`enableAllPotentialBarcodes`），倾斜的收款码不会被
 * 静默丢掉（STRICT 在自造 36 张评测图里丢 6 张）。代价是布料印花、格纹这类有规律纹理
 * 会被判成疑似条码。彩色照片在像素上就筛掉了（`TwoInks`：不是两种墨色印出来的），剩下的只圈不打码。
 *
 * 声明顺序就是设置页上的排列顺序，出厂项排第一；持久化存的是名字，调换顺序不影响老安装。
 * 原先还有一个 OFF，2026-10-07 起「关」挪到了 barcodeState，老安装存着的 OFF 由 SettingsStore 迁过去。
 */
enum class BarcodeOption { STRICT, LOOSE }

/** 人脸的识别模式。打不打码、关不关由 [RecognitionConfig.faceState] 管；OFF 的迁移同 [BarcodeOption]。 */
enum class FaceOption { FAST, ACCURATE }

/**
 * 语义判定（设置页上叫「AI 复查」）：让端侧大模型把整页文字再看一遍（spec §14 的 Gemini Nano 接口位）。
 *
 * GEMINI_NANO 不是「每张图都自动跑」，而是「编辑器里给一个 AI 复查按钮」——用户点了才跑。
 * 只在支持 AICore 的机型上出现；结果只圈出、不自动打码。枚举名是持久化格式，语义变了名字不改。
 */
enum class SemanticOption { GEMINI_NANO, OFF }

/**
 * 一条规则的三态。OFF 的规则根本不进引擎，另两态决定候选的初始 MaskState。
 * 人脸、条码用的也是这三态（[RecognitionConfig.faceState]、[RecognitionConfig.barcodeState]）。
 */
enum class RuleState { OFF, OUTLINED, MASKED }

/**
 * 识别方案（本文件对应 2026-09-04 的增补设计 §1）。
 *
 * 各根轴各自独立，刻意不提供命名预设——逐项开关的全部意义就在于变量隔离，
 * 预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 */
data class RecognitionConfig(
    /**
     * 出厂是 PP-OCR：真机截图上中文识别明显好于 ML Kit（打了星的号码、生僻字人名都认得出），
     * 而且能给出字符级的框。ML Kit 三档留着，逐项开关的意义就在于能切回去对比。
     */
    val textEngine: TextEngineOption = TextEngineOption.PADDLE,
    /** 出厂是 STRICT（2026-10-04 起）：不误圈花纹，代价是歪斜、模糊的码可能漏掉。 */
    val barcode: BarcodeOption = BarcodeOption.STRICT,
    /**
     * 认出来的条码怎么处理，和文字规则同样的三态。OFF 时条码检测根本不跑。
     * MASKED 只管解得出内容的那些：解不出的疑似条码（LOOSE 才有）无论如何只圈出。
     */
    val barcodeState: RuleState = RuleState.MASKED,
    val face: FaceOption = FaceOption.FAST,
    /** 认出来的人脸怎么处理。OFF 时人脸检测根本不跑。 */
    val faceState: RuleState = RuleState.MASKED,
    val semantic: SemanticOption = SemanticOption.GEMINI_NANO,
    /**
     * **只存与出厂默认不同的项。** 存全表的话，出厂默认改了之后老安装会读到
     * 一份冻结的旧全表，变成一个需要迁移的问题；只存差异则新规则天然继承新默认。
     */
    val ruleOverrides: Map<String, RuleState> = emptyMap(),
) {

    /**
     * 改一条规则的状态。**改回出厂值就把这一项删掉**，而不是记一条「等于默认」的覆盖——
     * 否则出厂默认将来一改，这条冻结的覆盖会悄悄把老用户按在旧行为上。
     *
     * @param factoryState 由 RuleCatalog 提供。放在参数里而不是直接调 RuleCatalog，
     *   是为了不让 engine 包反向依赖 rules 包（rules 已经依赖 engine 的枚举）。
     */
    fun withRule(id: String, state: RuleState, factoryState: RuleState): RecognitionConfig {
        val next = ruleOverrides.toMutableMap()
        if (state == factoryState) next.remove(id) else next[id] = state
        return copy(ruleOverrides = next)
    }

    /** `id:STATE` 分号分隔。十几条、只存差异，为它引 JSON 序列化不划算。 */
    fun encodeOverrides(): String =
        ruleOverrides.entries.joinToString(SEP) { "${it.key}$KV${it.value.name}" }

    companion object {
        private const val SEP = ";"
        private const val KV = ":"

        /**
         * 逐项容错：认不出的条目直接丢掉，不抛。枚举名是持久化格式的一部分，
         * 删掉一个选项不该让老安装打不开（与 SettingsStore.lastStyle 同一条理由）。
         */
        fun parseOverrides(raw: String): Map<String, RuleState> =
            raw.split(SEP).mapNotNull { entry ->
                val parts = entry.split(KV)
                if (parts.size != 2 || parts[0].isBlank()) return@mapNotNull null
                val state = runCatching { RuleState.valueOf(parts[1]) }.getOrNull() ?: return@mapNotNull null
                parts[0] to state
            }.toMap()
    }
}
