package com.dama.app.rules

import com.dama.app.core.model.Candidate
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.TextLine
import com.dama.app.engine.SensitivityClassifier

/**
 * 判定层的规则实现（spec §4.3）。
 * 规则永远在 classifiers 列表的第一位且永不缺席——它是全部识别能力的地基。
 */
class RuleClassifier(private val rules: List<Rule>) : SensitivityClassifier {

    override val id = "rule"

    /** 规则不依赖任何模型或网络，永远可用。 */
    override suspend fun isAvailable(): Boolean = true

    override suspend fun classify(lines: List<TextLine>): List<Candidate> {
        val out = ArrayList<Candidate>()
        lines.forEachIndexed { lineIndex, line ->
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
                        enabledByDefault = rule.enabledByDefault,
                    )
                }
            }
        }
        return out
    }
}
