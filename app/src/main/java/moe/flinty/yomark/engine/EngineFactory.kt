package moe.flinty.yomark.engine

import android.content.Context
import moe.flinty.yomark.chat.ChatDetector
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.engine.mlkit.MlKitBarcodeDetector
import moe.flinty.yomark.engine.mlkit.MlKitFaceDetector
import moe.flinty.yomark.engine.mlkit.MlKitTextRecognizer
import moe.flinty.yomark.engine.mlkit.TextScript
import moe.flinty.yomark.engine.genai.ExternalModelClassifier
import moe.flinty.yomark.engine.genai.ExternalModelSettings
import moe.flinty.yomark.engine.genai.GeminiNanoClassifier
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import moe.flinty.yomark.rules.Rule
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.rules.RuleClassifier
import kotlinx.coroutines.flow.first

/**
 * 装配（spec §4.4 + 2026-09-04 增补设计 §2）：**整个应用只有这一处知道用的是哪家的模型。**
 *
 * config 的每一根轴在这里落成一个具体实现。上层拿到的永远是一个 RedactionEngine，
 * 不知道背后是拉丁还是中文、是宽松还是严格。
 *
 * 大模型（Gemini Nano、实验室里的外部模型）走 refiners 而不是 classifiers：它慢，不能挡在第一批结果前面，
 * 而且只在用户点「AI 增强识别」时才跑。
 *
 * 每一环都套一层 traced()：调试信息开着时记下这一环的输入输出（见 Traced.kt），没开时原样转发。
 */
fun buildEngine(context: Context, config: RecognitionConfig = RecognitionConfig()) = RedactionEngine(
    recognizer = textRecognizerFor(context, config.textEngine).traced(),
    regionDetectors = buildList {
        if (config.faceState != RuleState.OFF) add(MlKitFaceDetector(config.face))
        if (config.barcodeState != RuleState.OFF) add(MlKitBarcodeDetector(config.barcode))
    }.map { it.traced() },
    classifiers = listOf(
        RuleClassifier(RuleCatalog.rulesFor(config), maskUnanchored = config.maskUnanchoredNames).traced(),
    ),
    refiners = SettingsStore(context).let { store -> refinersFor(config) { store.externalModel.first() } },
    pageAnalyzers = if (config.chatState == RuleState.OFF) emptyList() else {
        listOf(ChatDetector().traced())
    },
)

/**
 * 「AI 增强识别」的两个后端：实验室里的外部模型开着、配好了就用它，Nano 让开；不然照旧是本机的 Nano。
 * 外部模型的设置每次用时现读（[external]），不进 config：改了接口不用重建引擎、编辑器也不用重跑识别。
 * 什么都没开就不摆「AI 增强识别」：没有可问的，点了也只会说「没有新发现」。
 */
internal fun refinersFor(config: RecognitionConfig, external: suspend () -> ExternalModelSettings): List<SensitivityClassifier> =
    when (config.semantic) {
        SemanticOption.GEMINI_NANO -> aiKinds(config).takeIf { it.isNotEmpty() }?.let { kinds ->
            listOf(
                ExternalModelClassifier(kinds, aiOffRules(config), RuleCatalog.rulesFor(config), external).traced(),
                GeminiNanoClassifier(kinds, aiOffRules(config), yieldTo = { external().active }).traced(),
            )
        }.orEmpty()
        SemanticOption.OFF -> emptyList()
    }

/**
 * AI 增强识别找哪几类：「文字」里开着的那些，聊天记录开着时再加上昵称和聊天标题（头像是图，读文字的模型看不到）。
 * 它找到的直接打码，所以用户关掉的那一类它也不找，不能绕过设置。
 */
internal fun aiKinds(config: RecognitionConfig): Set<SensitiveKind> =
    RuleCatalog.rulesFor(config).map { it.kind }.toSet() +
        if (config.chatState == RuleState.OFF) emptySet() else setOf(SensitiveKind.CHAT_NAME, SensitiveKind.CHAT_TITLE)

/** 用户关掉的那几类文字规则：AI 拿它们认一遍回答，关掉的那一类换个类型报上来也不认（见 GeminiNanoClassifier 的 offRules）。 */
internal fun aiOffRules(config: RecognitionConfig): List<Rule> =
    RuleCatalog.all.filter { RuleCatalog.stateOf(config, it.id) == RuleState.OFF }

private fun textRecognizerFor(context: Context, option: TextEngineOption): TextRecognizer = when (option) {
    TextEngineOption.PADDLE -> PaddleTextRecognizer(context)
    TextEngineOption.LATIN -> MlKitTextRecognizer(TextScript.LATIN)
    TextEngineOption.CHINESE -> MlKitTextRecognizer(TextScript.CHINESE)
    TextEngineOption.BOTH -> CompositeTextRecognizer(
        listOf(MlKitTextRecognizer(TextScript.LATIN), MlKitTextRecognizer(TextScript.CHINESE))
    )
}
