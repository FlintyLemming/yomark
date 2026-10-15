package moe.flinty.yomark.engine

import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.AnalysisResult
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.TextLine
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
     * 挡在第一批结果前面会让「选中即打码」变成「选中后干等」。用户点「AI 增强识别」时才由调用方跑 refine()。
     */
    private val refiners: List<SensitivityClassifier> = emptyList(),
    /** 文字出来之后、拿文字和像素一起看整页的分析（聊天截图）。和判定器一样，失败降级为空。 */
    private val pageAnalyzers: List<PageAnalyzer> = emptyList(),
) {
    val canRefine: Boolean get() = refiners.isNotEmpty()

    /**
     * 慢判定器此刻能不能跑（机型支持、模型已在设备上）。只探测，不推理——
     * 「AI 增强识别」按钮出不出现看它，不支持的机型上就不该摆一个点了没用的按钮。
     */
    suspend fun refineReady(): Boolean =
        refiners.any { runCatching { it.isAvailable() }.getOrDefault(false) }

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
        // 竖着、倒着拍的页：人像在图上是横着的，人脸检测认不出。文字读出来才知道页面转了几个直角，
        // 这时让要转正的检测器在转正的图上再看一遍，和上面那一遍的结果一起合并（同一张脸重叠的去重）
        val frame = ReadingFrame.of(lines.map { it.quad })
        val turns = frame.quarterTurns
        val uprightJobs = if (turns == 0) emptyList() else regionDetectors.filter { it.needsUpright }.map { d ->
            async { runCatching { d.detect(image, turns) }.getOrElse { emptyList() } }
        }
        val fromText = classifiers
            .filter { it.isAvailable() }                    // 不可用的直接跳过
            .flatMap { c -> runCatching { c.classify(lines) }.getOrElse { emptyList() } }
        val fromPage = pageAnalyzers.flatMap { a -> runCatching { a.analyze(image, lines) }.getOrElse { emptyList() } }

        AnalysisResult(
            lines = lines,
            candidates = merger.merge(fromText + fromPage + regionJobs.awaitAll().flatten() + uprightJobs.awaitAll().flatten(), frame),
        )
    }

    /**
     * 第二遍：在已有结果上跑慢的判定器，只返回**新增**的候选。
     *
     * @param covered 画面上已经打了码的地方：规则、聊天的候选，点字打的码，也包括用户自己画的手动框。
     *   与其中任何一个大面积重叠的一律丢掉，不论类型——那块像素已经有人管了，
     *   再叠一个框只会让用户多点一下。马克笔、画笔、文本不算：底下的字照样看得见（见 EditorViewModel.runAiReview）。
     * @param dismissed 用户点掉的识别结果的框，多半是误报：模型在差不多同一处再报，只是同一个误报，丢掉。
     *   只丢和它差不多一样大的（IoU 过半）：点掉的两个字的误报，不能把模型报的整段地址也带走。
     * @return null = 没有一个慢判定器真的跑完（不可用，或者抛了异常）。
     *   与 analyze 的「失败降级为空」不同：增强识别是用户点出来的，「没跑成」和「跑了没发现」
     *   必须分开告诉他，否则他会以为这一页已经被模型看过、是干净的。
     */
    suspend fun refine(lines: List<TextLine>, covered: List<Quad>, dismissed: List<Quad> = emptyList()): List<Candidate>? {
        val runs = refiners
            .filter { runCatching { it.isAvailable() }.getOrDefault(false) }
            .mapNotNull { r -> runCatching { r.classify(lines) }.getOrNull() }
        if (runs.isEmpty()) return null
        val fresh = merger.merge(runs.flatten(), ReadingFrame.of(lines.map { it.quad }))
            .filter { c -> covered.none { covers(it, c.quad) } && dismissed.none { it.iou(c.quad) > DISMISSED_IOU } }
        // 这一遍报的几处之间也不叠：同一个名字既报成人名、又连着 @ 报成昵称时，只留大的那个（merge 已按面积从大到小排好）。
        // 合并本身只在同类之间去重，这里不论类型，和 covered 一样
        return fresh.fold(ArrayList<Candidate>()) { kept, c -> kept.apply { if (none { covers(it.quad, c.quad) }) add(c) } }
    }

    /** a 盖住了 b 的大半：交集超过两者中较小那个的一半。 */
    private fun covers(a: Quad, b: Quad): Boolean {
        val ra = a.bounds()
        val rb = b.bounds()
        val w = minOf(ra.right, rb.right) - maxOf(ra.left, rb.left)
        val h = minOf(ra.bottom, rb.bottom) - maxOf(ra.top, rb.top)
        if (w <= 0f || h <= 0f) return false
        return w * h > 0.5f * minOf(ra.width() * ra.height(), rb.width() * rb.height())
    }

    private companion object {
        const val DISMISSED_IOU = 0.5f
    }
}
