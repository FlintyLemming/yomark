package moe.flinty.yomark.ui

import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.AnalysisResult
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceBlock
import moe.flinty.yomark.debug.TraceSection
import moe.flinty.yomark.debug.TraceStage
import moe.flinty.yomark.debug.describe
import moe.flinty.yomark.debug.describeCandidate
import moe.flinty.yomark.engine.RecognitionConfig

/*
 * 编辑器往调试信息里记的那几节（见 debug/Trace.kt）：这一次识别的输入（哪张图、什么方案）和最后进编辑器的结果。
 * 中间各环节由引擎的各环节自己记（见 engine/Traced.kt）。
 */

/** 识别的是哪张图、按什么方案。方案写枚举名：排查时要的是确切的值，不是设置页上的措辞。 */
internal fun inputSection(image: SourceImage, config: RecognitionConfig) = TraceSection(
    stage = TraceStage.INPUT,
    title = "输入",
    summary = "分析图 ${image.width}×${image.height} · 原图 ${image.originalWidth}×${image.originalHeight} · ${image.mimeType}",
    lines = buildList {
        add("框的坐标都按分析图的像素算")
        add("文字识别 ${config.textEngine} · 人脸 ${config.faceState}/${config.face} · 条码 ${config.barcodeState}/${config.barcode}")
        add("没有旁证的人名${if (config.maskUnanchoredNames) "打码" else "丢掉"}")
        if (config.ruleOverrides.isNotEmpty()) {
            add("改过的规则 " + config.ruleOverrides.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
    },
)

/** 合并之后的那些框，要打码的进编辑器，其余的认出来了也不打码。识别整个抛了异常（不该发生，各环节的异常引擎都吞了）也记下来。 */
internal fun resultSection(outcome: Result<AnalysisResult>): TraceSection = outcome.fold(
    onSuccess = { result ->
        val masked = result.candidates.count { it.enabledByDefault }
        TraceSection(
            stage = TraceStage.RESULT,
            title = "结果",
            summary = "合并后 ${result.candidates.size} 处：打码 $masked · 不打码 ${result.candidates.size - masked}",
            lines = result.candidates.map { describeCandidate(it, result.lines) },
        )
    },
    onFailure = { error ->
        TraceSection(
            stage = TraceStage.RESULT,
            title = "结果",
            summary = "识别抛了异常：${error.describe()}",
            blocks = listOf(TraceBlock("堆栈", error.stackTraceToString())),
        )
    },
)

/** AI 增强识别最后新增的框：合并过，已经有框的地方去掉了。null 是一个判定器都没跑成。 */
internal fun refineResultSection(extra: List<Candidate>?, lines: List<TextLine>) = TraceSection(
    stage = TraceStage.RESULT,
    title = "结果",
    summary = extra?.let { "新增 ${it.size} 处（合并后，已经有框的地方去掉了）" }
        ?: "没跑成：模型不可用，或者抛了异常（见上面几节）",
    lines = extra.orEmpty().map { describeCandidate(it, lines) },
)
