package moe.flinty.yomark.core.model

import moe.flinty.yomark.core.geometry.Quad

data class TextElement(val quad: Quad, val text: String, val range: IntRange)

data class TextLine(
    val quad: Quad,
    val text: String,                 // elements 按阅读顺序拼接，见 MlKitTextRecognizer
    val confidence: Float,
    val elements: List<TextElement>,  // range 是该词在 text 中的字符区间
    /** 拼成这一行的那几段各自的框，见 OcrLine.segments。ML Kit 的行没有拼过，就是 [quad]。 */
    val segments: List<Quad> = listOf(quad),
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
     * 打不打码。true 进编辑器就是一块码；false 是认出来了、但不打码，编辑器不收它（见 MaskPlanFactory），
     * 只在调试信息里看得到。由规则表逐条指定（spec §6），不是由 confidence 阈值算出来的；
     * 同一条规则里误报率高出一截、又找不到旁证可要的认法（RuleMatch.outlineOnly）是 false。
     */
    val enabledByDefault: Boolean = true,
    /**
     * 这一处的字朝哪个方向读（度，同 ReadingFrame.degrees）；null 是跟着整页（人脸、条码）。
     * 截图里一张转过的证件照、车牌照：合并时只在同一个方向的候选之间、按这个方向量「同一行」「挨着」（CandidateMerger），
     * 按整页的方向量，证件上上下两行地址、照片上摞着的几块牌子会拼成一大块。
     */
    val readingDegrees: Float? = null,
)

data class AnalysisResult(val lines: List<TextLine>, val candidates: List<Candidate>)
