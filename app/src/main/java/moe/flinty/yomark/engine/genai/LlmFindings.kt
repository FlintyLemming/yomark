package moe.flinty.yomark.engine.genai

import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.rules.Rule
import moe.flinty.yomark.rules.RuleClassifier

/*
 * 两个大模型后端（本机的 Gemini Nano、外部模型）共用的部分：送哪几行、关掉的类型怎么拦、找到的怎么落成候选。
 * 两边的提示词和解析都是 SemanticPrompt，只是问的地方不一样。
 */

/**
 * 送给模型的行，null 是这一行不送：置信度不到规则的下限，或者不到两个字母、汉字或数字。
 * 纯数字的行也送：单独一行的取件码、手机号，模型要看到才认得出，打了星的证件号也是上一行是人名的线索。
 * 纯符号、单个字的行没什么可看的。
 */
internal fun reviewTexts(lines: List<TextLine>): List<String?> =
    lines.map { l -> l.text.takeIf { l.confidence >= RuleClassifier.MIN_LINE_CONFIDENCE && it.count(Char::isLetterOrDigit) >= 2 } }

/**
 * 用户关掉的那几类，用它们的规则拦模型的回答：模型把关掉的那一类报成开着的另一类时（关了电话，它把手机号报成证件号；
 * 日期出厂关着，它把下单时间报成取件码），整段就是关掉那一类的东西，这一条不认。
 *
 * 日期、IP（置信度 0.6）往上的算；没有校验位的长数字串是猜的（0.4），出厂又关着，拿它来拦，
 * 银行账号报成银行卡也会被拦掉。网址规则只认 ? 和路径那一截，整段的网址拦不住。
 * 生日、取件码（「3-2-2025」）也可能是日期的样子，关了日期不拦它们。
 */
internal fun vetoBy(offRules: List<Rule>): (SensitiveKind, String) -> Boolean = { kind, quote ->
    offRules.any { rule ->
        !(rule.kind == SensitiveKind.DATETIME && kind in rule.yieldsTo) &&
            rule.findIn(quote).any { m ->
                m.confidence >= VETO_CONFIDENCE && !m.needsAnchor && !m.outlineOnly &&
                    m.range.first == 0 && m.range.last == quote.lastIndex
            }
    }
}

/** 模型找到的几处落成候选，标「AI」。合并时同规则的候选一样按各自的阅读方向量：截图里转过的证件照上两行地址不拼成一块。 */
internal fun List<SemanticPrompt.Finding>.toCandidates(lines: List<TextLine>): List<Candidate> {
    val directions by lazy { RuleClassifier.readingDegrees(lines) }
    return map { f ->
        Candidate(
            id = "llm-${f.lineIndex}-${f.range.first}-${f.kind}",
            quad = lines[f.lineIndex].quadForRange(f.range),
            kind = f.kind,
            source = DetectorSource.LLM,
            confidence = LLM_CONFIDENCE,
            readingDegrees = directions[f.lineIndex],
        )
    }
}

private const val LLM_CONFIDENCE = 0.5f
private const val VETO_CONFIDENCE = 0.6f
