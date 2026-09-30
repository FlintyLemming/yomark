package com.youma.app.engine

import android.content.Context
import com.youma.app.engine.mlkit.MlKitBarcodeDetector
import com.youma.app.engine.mlkit.MlKitFaceDetector
import com.youma.app.engine.mlkit.MlKitTextRecognizer
import com.youma.app.engine.mlkit.TextScript
import com.youma.app.engine.ppocr.PaddleTextRecognizer
import com.youma.app.rules.RuleCatalog
import com.youma.app.rules.RuleClassifier

/**
 * 装配（spec §4.4 + 2026-09-04 增补设计 §2）：**整个应用只有这一处知道用的是哪家的模型。**
 *
 * config 的每一根轴在这里落成一个具体实现。上层拿到的永远是一个 RedactionEngine，
 * 不知道背后是拉丁还是中文、是宽松还是严格。
 *
 * v2 想加 Gemini Nano 语义判定：classifiers 里追加一项，其余不动。
 */
fun buildEngine(context: Context, config: RecognitionConfig = RecognitionConfig()) = RedactionEngine(
    recognizer = textRecognizerFor(context, config.textEngine),
    regionDetectors = buildList {
        if (config.face != FaceOption.OFF) add(MlKitFaceDetector(config.face))
        if (config.barcode != BarcodeOption.OFF) add(MlKitBarcodeDetector(config.barcode))
    },
    classifiers = listOf(RuleClassifier(RuleCatalog.rulesFor(config))),
)

private fun textRecognizerFor(context: Context, option: TextEngineOption): TextRecognizer = when (option) {
    TextEngineOption.PADDLE -> PaddleTextRecognizer(context)
    TextEngineOption.LATIN -> MlKitTextRecognizer(TextScript.LATIN)
    TextEngineOption.CHINESE -> MlKitTextRecognizer(TextScript.CHINESE)
    TextEngineOption.BOTH -> CompositeTextRecognizer(
        listOf(MlKitTextRecognizer(TextScript.LATIN), MlKitTextRecognizer(TextScript.CHINESE))
    )
}
