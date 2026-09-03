package com.dama.app.engine

import android.content.Context
import com.dama.app.engine.mlkit.MlKitTextRecognizer
import com.dama.app.rules.DefaultRuleSet
import com.dama.app.rules.RuleClassifier

/**
 * 首版装配（spec §4.4）：**整个应用只有这一处知道用的是哪家的模型。**
 *
 * v2 想加 Gemini Nano 语义判定：classifiers 里追加一项，其余不动。
 * v2 想换成自带 OCR 模型：换掉 recognizer 这一行，其余不动。
 *
 * regionDetectors 在 M3（计划 05）填入人脸与条码检测器。
 */
fun buildEngine(context: Context) = RedactionEngine(
    recognizer = MlKitTextRecognizer(),
    regionDetectors = emptyList(),
    classifiers = listOf(RuleClassifier(DefaultRuleSet.rules)),
)
