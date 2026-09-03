package com.dama.app.engine

import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.AnalysisResult
import com.dama.app.core.model.TextLine
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
) {
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
}
