package moe.flinty.yomark.engine

import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.TextLine
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * 并行跑多个识别器，取并集（2026-09-04 增补设计 §2）。
 *
 * **不去重**——去重是 CandidateMerger 的职责。两个识别器在同一块文字上
 * 各出一个候选时，IoU 去重本来就会合并掉；在 OCR 层再实现一遍只会有两套规则。
 *
 * 单条 lane 失败降级为空，与 RedactionEngine 同一条约定：runCatching 必须写在
 * async 内部，否则子协程的异常会取消整个 scope。
 */
class CompositeTextRecognizer(private val delegates: List<TextRecognizer>) : TextRecognizer {

    override val id = delegates.joinToString("+") { it.id }

    override suspend fun recognize(image: SourceImage): List<TextLine> = coroutineScope {
        delegates
            .map { d -> async { runCatching { d.recognize(image) }.getOrElse { emptyList<TextLine>() } } }
            .awaitAll()
            .flatten()
    }
}
