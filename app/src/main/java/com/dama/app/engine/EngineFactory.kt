package com.dama.app.engine

import android.content.Context
import com.dama.app.engine.mlkit.MlKitBarcodeDetector
import com.dama.app.engine.mlkit.MlKitFaceDetector
import com.dama.app.engine.mlkit.MlKitTextRecognizer
import com.dama.app.rules.DefaultRuleSet
import com.dama.app.rules.RuleClassifier

/**
 * 首版装配（spec §4.4）：**整个应用只有这一处知道用的是哪家的模型。**
 *
 * v2 想加 Gemini Nano 语义判定：classifiers 里追加一项，其余不动。
 * v2 想换成自带 OCR 模型：换掉 recognizer 这一行，其余不动。
 *
 * M3（计划 05）填入 regionDetectors：人脸与条码。两者由 RegionDetector 直接产出，
 * 不经过 DefaultRuleSet 那张表——引擎、UI、数据模型一行都没改。
 */
fun buildEngine(context: Context) = RedactionEngine(
    recognizer = MlKitTextRecognizer(),
    regionDetectors = listOf(MlKitFaceDetector(), MlKitBarcodeDetector()),
    classifiers = listOf(RuleClassifier(DefaultRuleSet.rules)),
)
