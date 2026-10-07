package com.yomark.app.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.image.SourceImage
import com.yomark.app.core.model.TextElement
import com.yomark.app.core.model.TextLine
import com.yomark.app.engine.TextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 文字识别（bundled）。脚本由 script 决定，两个模型都随包发行，都不联网。
 *
 * 图像进来时已按 EXIF 转正（spec §5.1），所以 rotationDegrees 恒为 0——
 * 全流程只有一个坐标系。
 *
 * 拼接规则：TextLine.text = elements 的 text 用单个空格连接，同时记录字符区间。
 * 不用 ML Kit 自己的 Line.text：规则跑在拼接串上，两者不一致会让区间映射错位。
 */
class MlKitTextRecognizer(
    private val script: TextScript = TextScript.LATIN,
) : TextRecognizer {

    override val id = when (script) {
        TextScript.LATIN -> "mlkit-text-v2-latin"
        TextScript.CHINESE -> "mlkit-text-v2-chinese"
    }

    /**
     * lazy 是有意的：没被选中的后端不会初始化。中文模型在 APK 里
     * 不等于中文模型在内存里——这个应用还要跟 32 MP 大图抢堆。
     */
    private val client by lazy {
        TextRecognition.getClient(
            when (script) {
                TextScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
                // 中文识别器同时认拉丁字符，所以它不是「只认中文」的那个
                TextScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            }
        )
    }

    override suspend fun recognize(image: SourceImage): List<TextLine> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val result = client.process(input).await()
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { it.toTextLine() }
    }

    private fun Text.Line.toTextLine(): TextLine? {
        val els = elements.mapNotNull { el ->
            val q = el.quad() ?: return@mapNotNull null
            el.text to q
        }
        if (els.isEmpty()) return null

        val sb = StringBuilder()
        val out = ArrayList<TextElement>(els.size)
        els.forEachIndexed { i, (text, quad) ->
            if (i > 0) sb.append(' ')
            val start = sb.length
            sb.append(text)
            out += TextElement(quad, text, start until sb.length)
        }

        return TextLine(
            quad = quad() ?: Quad.boundingQuad(out.map { it.quad }),
            text = sb.toString(),
            confidence = confidence.takeIf { !it.isNaN() } ?: DEFAULT_CONFIDENCE,
            elements = out,
        )
    }

    private fun Text.Line.quad(): Quad? = cornerPoints?.toQuad() ?: boundingBox?.let { Quad.fromRect(RectF(it)) }
    private fun Text.Element.quad(): Quad? = cornerPoints?.toQuad() ?: boundingBox?.let { Quad.fromRect(RectF(it)) }

    private fun Array<android.graphics.Point>.toQuad(): Quad? {
        if (size < 4) return null
        return Quad(
            PointF(this[0].x.toFloat(), this[0].y.toFloat()),
            PointF(this[1].x.toFloat(), this[1].y.toFloat()),
            PointF(this[2].x.toFloat(), this[2].y.toFloat()),
            PointF(this[3].x.toFloat(), this[3].y.toFloat()),
        )
    }

    private companion object { const val DEFAULT_CONFIDENCE = 0.9f }
}
