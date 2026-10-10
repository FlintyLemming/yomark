package moe.flinty.yomark.engine.ppocr

import android.content.Context
import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PP-OCRv6 small 接到 TextRecognizer 接口上（spec §14 第一行：「新增一个实现 + §4.4 换一行」）。
 *
 * 与 ML Kit 那个实现的两处不同，都是为了规则层：
 * - element 是**单个字**，框由 CTC 时间步切出来。quadForRange 因此精确到字，
 *   「沐晨冉 86-186****3392」遮名字时不会连号码一起遮；
 * - TextLine.text 是识别出的原文加上按像素间隙补回的空格，不再是「element 用空格拼接」。
 *   RuleClassifier 只依赖 element 的 range，不依赖拼接方式。
 */
class PaddleTextRecognizer(private val context: Context) : TextRecognizer {

    override val id = "ppocr-v6-small"

    override suspend fun recognize(image: SourceImage): List<TextLine> = withContext(Dispatchers.Default) {
        val bmp = image.bitmap
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        PpOcrModels.engine(context).recognize(pixels, bmp.width, bmp.height).mapNotNull { it.toTextLine() }
    }
}

internal fun OcrLine.toTextLine(): TextLine? {
    val elements = text.indices.mapNotNull { i ->
        charBoxes[i]?.let { TextElement(it.toQuad(), text[i].toString(), i..i) }
    }
    if (elements.isEmpty()) return null
    return TextLine(box.toQuad(), text, confidence, elements)
}

private fun Box.toQuad() = Quad(tl.toPointF(), tr.toPointF(), br.toPointF(), bl.toPointF())
private fun Pt.toPointF() = PointF(x, y)

/**
 * 模型只加载一次。配置一变 buildEngine 就会重建引擎（2026-09-04 增补设计 §2），
 * 而两个模型加起来 31 MB、建 session 要几百毫秒——跟着引擎重建就太浪费了。
 * 进程活着就一直留着；模型随包发行，不联网。
 */
object PpOcrModels {

    /** assets 里这套模型是哪一版，设置的「AI」页显示用。换模型时和 docs/ppocr-models.md 一起改。 */
    const val VERSION = "PP-OCRv5 mobile"

    @Volatile private var cached: PpOcrEngine? = null

    fun engine(context: Context): PpOcrEngine =
        cached ?: synchronized(this) { cached ?: load(context.applicationContext).also { cached = it } }

    private fun load(context: Context): PpOcrEngine {
        val assets = context.assets
        fun bytes(name: String) = assets.open("$DIR/$name").use { it.readBytes() }
        return PpOcrEngine(
            detModel = bytes("det.onnx"),
            recModel = bytes("rec.onnx"),
            dict = PpOcrEngine.parseDict(String(bytes("dict.txt"), Charsets.UTF_8)),
            threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4),
        )
    }

    private const val DIR = "ppocr"
}
