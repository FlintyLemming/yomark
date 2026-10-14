package moe.flinty.yomark.engine

import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine

/**
 * 定位层（spec §4.3）。唯一职责：把一张图变成带坐标的文本行。
 * 首版实现 = MlKitTextRecognizer。PP-OCR + ONNX Runtime 走这个接口接入，
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

    /**
     * 页面在图上转了直角时，要不要把图转正了再看一遍。人脸检测认不出横躺着的脸：
     * 竖着拍的身份证，人像在图上是横着的。条码不分方向，不用。
     */
    val needsUpright: Boolean get() = false

    /**
     * 把图转正了再看：页面在图上顺时针转了 [quarterTurns] 个直角（1..3，见 ReadingFrame.quarterTurns），
     * 逆着转回来再检测，框映射回原图的坐标。引擎只对 [needsUpright] 的检测器调它。
     */
    suspend fun detect(image: SourceImage, quarterTurns: Int): List<Candidate> = emptyList()
}

/**
 * 整页分析：既要文字行、又要像素，在文字识别之后跑。聊天截图的头像、昵称、标题（ChatDetector）：
 * 先得看文字的排法才知道这一页是不是聊天记录、头像该在哪一列找。
 */
interface PageAnalyzer {
    val id: String
    suspend fun analyze(image: SourceImage, lines: List<TextLine>): List<Candidate>
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
