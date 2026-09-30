package com.youma.app.engine

import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.AnalysisResult
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.TextLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 识别引擎（spec §4.4）。
 *
 * 任何一条链路失败都降级为空结果，不让整次识别失败——
 * 用户宁可少几个候选，也不愿意看到「识别失败」。
 */
class RedactionEngine(
    private val recognizer: TextRecognizer,
    private val regionDetectors: List<RegionDetector>,
    private val classifiers: List<SensitivityClassifier>,
    private val merger: CandidateMerger = CandidateMerger(),
    /**
     * 慢的判定器（端侧大模型），不进 analyze：一次推理要几秒，
     * 挡在第一批结果前面会让「选中即打码」变成「选中后干等」。由调用方在结果上屏之后另跑 refine()。
     */
    private val refiners: List<SensitivityClassifier> = emptyList(),
) {
    val canRefine: Boolean get() = refiners.isNotEmpty()

    suspend fun analyze(image: SourceImage): AnalysisResult = coroutineScope {
        // 文字链路和区域检测互不依赖，并行跑。
        //
        // runCatching 必须写在 async **内部**：结构化并发下，async 的子协程抛出异常会
        // 立即取消父 scope，在外面 catch await() 是拦不住的——整次 analyze 会跟着炸，
        // 恰好违背「任何一条链路失败都降级为空结果」这条规格。
        val linesJob = async { runCatching { recognizer.recognize(image) }.getOrElse { emptyList<TextLine>() } }
        val regionJobs = regionDetectors.map { d ->
            async { runCatching { d.detect(image) }.getOrElse { emptyList() } }
        }

        val lines = linesJob.await()
        val fromText = classifiers
            .filter { it.isAvailable() }                    // 不可用的直接跳过
            .flatMap { c -> runCatching { c.classify(lines) }.getOrElse { emptyList() } }

        AnalysisResult(
            lines = lines,
            candidates = merger.merge(fromText + regionJobs.awaitAll().flatten()),
        )
    }

    /**
     * 第二遍：在已有结果上跑慢的判定器，只返回**新增**的候选。
     *
     * 与已有候选大面积重叠的一律丢掉，不论类型——那块像素规则已经管了，
     * 再叠一个框只会让用户多点一下。失败、不可用都降级为空，与 analyze 同一条约定。
     */
    suspend fun refine(result: AnalysisResult): List<Candidate> {
        val found = refiners
            .filter { runCatching { it.isAvailable() }.getOrDefault(false) }
            .flatMap { r -> runCatching { r.classify(result.lines) }.getOrElse { emptyList() } }
        return merger.merge(found).filter { c -> result.candidates.none { covers(it, c) } }
    }

    /** a 盖住了 b 的大半：交集超过两者中较小那个的一半。 */
    private fun covers(a: Candidate, b: Candidate): Boolean {
        val ra = a.quad.bounds()
        val rb = b.quad.bounds()
        val w = minOf(ra.right, rb.right) - maxOf(ra.left, rb.left)
        val h = minOf(ra.bottom, rb.bottom) - maxOf(ra.top, rb.top)
        if (w <= 0f || h <= 0f) return false
        return w * h > 0.5f * minOf(ra.width() * ra.height(), rb.width() * rb.height())
    }
}
