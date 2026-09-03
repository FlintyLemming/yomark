package com.dama.app.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.TextElement
import com.dama.app.core.model.TextLine
import com.dama.app.engine.TextRecognizer
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * ML Kit 文字识别（bundled 拉丁脚本）。
 *
 * 图像进来时已按 EXIF 转正（spec §5.1），所以 rotationDegrees 恒为 0——
 * 全流程只有一个坐标系。
 *
 * 拼接规则：TextLine.text = elements 的 text 用单个空格连接，同时记录字符区间。
 * 不用 ML Kit 自己的 Line.text：规则跑在拼接串上，两者不一致会让区间映射错位。
 */
class MlKitTextRecognizer : TextRecognizer {

    override val id = "mlkit-text-v2-latin"

    private val client by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

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
