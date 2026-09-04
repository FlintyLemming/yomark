package com.youma.app.engine

/** OCR 后端。BOTH 并行跑两个再取并集，去重交给 CandidateMerger。 */
enum class TextEngineOption { LATIN, CHINESE, BOTH }

/**
 * 条码策略。
 *
 * LOOSE 连解不出内容的疑似条码也报（`enableAllPotentialBarcodes`）——
 * 漏检是事故，倾斜的收款码在 STRICT 下会被静默丢掉（自造 36 张评测图里丢 6 张）。
 * 代价是布料印花、格纹这类有规律纹理会被判成疑似条码，所以那部分只圈不打码。
 */
enum class BarcodeOption { LOOSE, STRICT, OFF }

enum class FaceOption { FAST, ACCURATE, OFF }

/** 一条规则的三态。OFF 的规则根本不进引擎，另两态决定候选的初始 MaskState。 */
enum class RuleState { OFF, OUTLINED, MASKED }

/**
 * 识别方案（本文件对应 2026-09-04 的增补设计 §1）。
 *
 * 四根轴各自独立，刻意不提供命名预设——逐项开关的全部意义就在于变量隔离，
 * 预设会把变量重新绑回一起，出了问题定位不到是哪一项。
 */
data class RecognitionConfig(
    val textEngine: TextEngineOption = TextEngineOption.BOTH,
    val barcode: BarcodeOption = BarcodeOption.LOOSE,
    val face: FaceOption = FaceOption.FAST,
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
