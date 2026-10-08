package moe.flinty.yomark.engine

import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine

/**
 * 定位层（spec §4.3）。唯一职责：把一张图变成带坐标的文本行。
 * 首版实现 = MlKitTextRecognizer。PP-OCRv5 + ONNX Runtime 走这个接口接入，
 * 引擎和上层一行都不用改。
 */
interface TextRecognizer {
    val id: String
    suspend fun recognize(image: SourceImage): List<TextLine>
}

/**
 * 非文字区域检测。人脸、条码各一个实现，按 List 注入。
 * 以后加车牌检测 = 加一个实现 + 装配处 List 里加一项。
 */
interface RegionDetector {
    val id: String
    val kind: SensitiveKind
    suspend fun detect(image: SourceImage): List<Candidate>
}

/**
 * 判定层。输入文本行，输出候选区域。
 * 首版只有 RuleClassifier。规则永远在第一位且永不缺席。
 */
interface SensitivityClassifier {
    val id: String
    /** 运行时探测。模型没就绪、设备不支持、配额耗尽都返回 false，引擎跳过它。 */
    suspend fun isAvailable(): Boolean
    suspend fun classify(lines: List<TextLine>): List<Candidate>
}
