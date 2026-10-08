package moe.flinty.yomark.core.model

import moe.flinty.yomark.core.geometry.Quad

data class TextElement(val quad: Quad, val text: String, val range: IntRange)

data class TextLine(
    val quad: Quad,
    val text: String,                 // elements 按阅读顺序拼接，见 MlKitTextRecognizer
    val confidence: Float,
    val elements: List<TextElement>,  // range 是该词在 text 中的字符区间
) {
    /**
     * 把 text 上的匹配区间映射回像素四边形（spec §5.3）。
     * 只能到词粒度：取所有与 range 相交的 element，求它们的最小外接四边形。
     * 匹配不到任何 element 时退回整行的 quad——宁可多遮。
     */
    fun quadForRange(range: IntRange): Quad {
        val hit = elements.filter { it.range.first <= range.last && range.first <= it.range.last }
        if (hit.isEmpty()) return quad
        return Quad.boundingQuad(hit.map { it.quad })
    }
}

data class Candidate(
    val id: String,
    val quad: Quad,
    val kind: SensitiveKind,
    val source: DetectorSource,
    val confidence: Float,
    /**
     * 进编辑器时的初始状态。true → MASKED，false → OUTLINED。
     * 由规则表逐条指定（spec §6），不是由 confidence 阈值算出来的；
     * 同一条规则里误报率高出一截的认法可以把自己的命中压成仅圈出（见 OutlineOnly）。
     */
    val enabledByDefault: Boolean = true,
)

data class AnalysisResult(val lines: List<TextLine>, val candidates: List<Candidate>)
