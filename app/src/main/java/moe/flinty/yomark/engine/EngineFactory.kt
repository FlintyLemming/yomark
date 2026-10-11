package moe.flinty.yomark.engine

import android.content.Context
import moe.flinty.yomark.engine.mlkit.MlKitBarcodeDetector
import moe.flinty.yomark.engine.mlkit.MlKitFaceDetector
import moe.flinty.yomark.engine.mlkit.MlKitTextRecognizer
import moe.flinty.yomark.engine.mlkit.TextScript
import moe.flinty.yomark.engine.genai.GeminiNanoClassifier
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.rules.RuleClassifier

/**
 * 装配（spec §4.4 + 2026-09-04 增补设计 §2）：**整个应用只有这一处知道用的是哪家的模型。**
 *
 * config 的每一根轴在这里落成一个具体实现。上层拿到的永远是一个 RedactionEngine，
 * 不知道背后是拉丁还是中文、是宽松还是严格。
 *
 * Gemini Nano 语义判定走 refiners 而不是 classifiers：它慢，不能挡在第一批结果前面，
 * 而且只在用户点「AI 增强识别」时才跑。
 *
 * 每一环都套一层 traced()：调试信息开着时记下这一环的输入输出（见 Traced.kt），没开时原样转发。
 */
fun buildEngine(context: Context, config: RecognitionConfig = RecognitionConfig()) = RedactionEngine(
    recognizer = textRecognizerFor(context, config.textEngine).traced(),
    regionDetectors = buildList {
        if (config.faceState != RuleState.OFF) {
            add(MlKitFaceDetector(config.face, masked = config.faceState == RuleState.MASKED))
        }
        if (config.barcodeState != RuleState.OFF) {
            add(MlKitBarcodeDetector(config.barcode, masked = config.barcodeState == RuleState.MASKED))
        }
    }.map { it.traced() },
    classifiers = listOf(
        RuleClassifier(RuleCatalog.rulesFor(config), keepUnanchored = config.outlineUnanchoredNames).traced(),
    ),
    refiners = when (config.semantic) {
        SemanticOption.GEMINI_NANO -> listOf(GeminiNanoClassifier().traced())
        SemanticOption.OFF -> emptyList()
    },
)

private fun textRecognizerFor(context: Context, option: TextEngineOption): TextRecognizer = when (option) {
    TextEngineOption.PADDLE -> PaddleTextRecognizer(context)
    TextEngineOption.LATIN -> MlKitTextRecognizer(TextScript.LATIN)
    TextEngineOption.CHINESE -> MlKitTextRecognizer(TextScript.CHINESE)
    TextEngineOption.BOTH -> CompositeTextRecognizer(
        listOf(MlKitTextRecognizer(TextScript.LATIN), MlKitTextRecognizer(TextScript.CHINESE))
    )
}
