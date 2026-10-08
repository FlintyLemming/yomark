package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.SensitivityClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 判定层的规则实现（spec §4.3）。
 * 规则永远在 classifiers 列表的第一位且永不缺席——它是全部识别能力的地基。
 */
class RuleClassifier(
    private val rules: List<Rule>,
    /**
     * 行置信度下限（spec §15 第 5 条的实测结论）。低于它的行不参与规则判定。
     *
     * bundled 的拉丁识别器碰上中文会吐出置信度 0.25–0.4 的乱码串
     * （「支付宝交易号」→ `7RfRBER`），那些串照样能撞上正则，是真实中文截图上
     * 误报的一个来源。这条下限只挡「识别器自己都不信」的行，挡不掉正常误报。
     *
     * 阈值本身是保守的：ML Kit 对清晰拉丁文本给的是 0.8–0.99，
     * 0.5 离正常区间足够远，不会误伤真实内容。识别器没给置信度时
     * MlKitTextRecognizer 已经回落到 0.9，不会被这里静默吃掉。
     */
    private val minLineConfidence: Float = MIN_LINE_CONFIDENCE,
) : SensitivityClassifier {

    override val id = "rule"

    /** 规则不依赖任何模型或网络，永远可用。 */
    override suspend fun isAvailable(): Boolean = true

    /**
     * 放到后台线程跑：调用方是 viewModelScope（主线程），以前全是正则、几毫秒就完，
     * 加了 HanLP 的人名识别之后，第一次分词要等词典读完，留在主线程会卡住界面。
     */
    override suspend fun classify(lines: List<TextLine>): List<Candidate> = withContext(Dispatchers.Default) {
        val out = ArrayList<Candidate>()
        lines.forEachIndexed { lineIndex, line ->
            if (line.confidence < minLineConfidence) return@forEachIndexed
            rules.forEach { rule ->
                // 单条规则出错不能拖垮整次判定
                val matches = runCatching { rule.findIn(line.text) }.getOrElse { emptyList() }
                matches.forEachIndexed { i, m ->
                    out += Candidate(
                        id = "rule-${rule.id}-$lineIndex-${m.range.first}-$i",
                        quad = line.quadForRange(m.range),
                        kind = rule.kind,
                        source = DetectorSource.RULE,
                        confidence = m.confidence,
                        // 规则选了「打码」，OutlineOnly 认出的那几处仍然只圈出
                        enabledByDefault = rule.enabledByDefault && !m.outlineOnly,
                    )
                }
            }
        }
        out
    }

    companion object {
        const val MIN_LINE_CONFIDENCE = 0.5f
    }
}
