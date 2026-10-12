package moe.flinty.yomark.engine

import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKindLabels
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceStage
import moe.flinty.yomark.debug.currentTrace
import moe.flinty.yomark.debug.describeCandidate
import moe.flinty.yomark.debug.describeLine
import moe.flinty.yomark.debug.formatDuration
import moe.flinty.yomark.debug.step

/*
 * 调试信息（见 debug/Trace.kt）落在识别各环节上的那一层：buildEngine 给每个识别器、检测器、判定器都套一层。
 * 协程上下文里挂着记录器时，记下这一环的用时、输出和抛出的异常；没挂时原样转发，什么都不做。
 *
 * 套在每一环外面，而不是写进 RedactionEngine：引擎把每一环的异常都吞掉、降级成空结果（「宁可少几个候选」），
 * 在吞之前的这一层才看得见吞掉的是什么。
 */

fun TextRecognizer.traced(): TextRecognizer = TracedTextRecognizer(this)

fun RegionDetector.traced(): RegionDetector = TracedRegionDetector(this)

fun SensitivityClassifier.traced(): SensitivityClassifier = TracedClassifier(this)

private class TracedTextRecognizer(private val inner: TextRecognizer) : TextRecognizer by inner {
    override suspend fun recognize(image: SourceImage): List<TextLine> {
        val trace = currentTrace() ?: return inner.recognize(image)
        return trace.step(TraceStage.TEXT, "文字识别 · ${inner.id}", { inner.recognize(image) }) { lines, ms ->
            "${formatDuration(ms)} · ${lines.size} 行（置信度、框的左上角与宽高、文字）" to lines.mapIndexed(::describeLine)
        }
    }
}

private class TracedRegionDetector(private val inner: RegionDetector) : RegionDetector by inner {
    override val needsUpright: Boolean get() = inner.needsUpright

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val trace = currentTrace() ?: return inner.detect(image)
        val title = "${SensitiveKindLabels.debugName(inner.kind)} · ${inner.id}"
        return trace.step(TraceStage.REGION, title, { inner.detect(image) }) { found, ms ->
            "${formatDuration(ms)} · ${found.size} 处" to found.map { describeCandidate(it) }
        }
    }

    override suspend fun detect(image: SourceImage, quarterTurns: Int): List<Candidate> {
        val trace = currentTrace() ?: return inner.detect(image, quarterTurns)
        val title = "${SensitiveKindLabels.debugName(inner.kind)} · ${inner.id} · 转回 ${quarterTurns * 90}°"
        return trace.step(TraceStage.REGION, title, { inner.detect(image, quarterTurns) }) { found, ms ->
            "${formatDuration(ms)} · ${found.size} 处" to found.map { describeCandidate(it) }
        }
    }
}

private class TracedClassifier(private val inner: SensitivityClassifier) : SensitivityClassifier by inner {
    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
        val trace = currentTrace() ?: return inner.classify(lines)
        return trace.step(TraceStage.CLASSIFY, "判定 · ${inner.id}", { inner.classify(lines) }) { found, ms ->
            "${formatDuration(ms)} · ${found.size} 处（合并之前）" to found.map { describeCandidate(it, lines) }
        }
    }
}
