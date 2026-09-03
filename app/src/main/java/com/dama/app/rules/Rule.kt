package com.dama.app.rules

import com.dama.app.core.model.SensitiveKind

data class RuleMatch(val range: IntRange, val confidence: Float)

/**
 * 一条敏感规则。规则集是数据不是代码（spec §6）——
 * 加一条规则 = 往 DefaultRuleSet.rules 里加一个对象，不改任何逻辑。
 */
interface Rule {
    val id: String
    val kind: SensitiveKind
    /** 进编辑器时的初始状态。按误报率划线，不按危害划线（spec §6）。 */
    val enabledByDefault: Boolean
    fun findIn(text: String): List<RuleMatch>
}

/**
 * 正则 + 校验的通用规则。
 *
 * @param validate 收下**整行文本**作为第一个参数：IP 规则要靠前文判断「这是不是版本号」，
 *                 而 Kotlin 的 MatchResult 不暴露原串。
 * @param select 从匹配里挑出**要遮的**区间。默认是整个匹配；
 *               URL 规则用它做到「只遮 query 与 path，域名保留」。
 */
class RegexRule(
    override val id: String,
    override val kind: SensitiveKind,
    override val enabledByDefault: Boolean,
    private val pattern: Regex,
    private val confidence: Float,
    private val validate: (text: String, m: MatchResult) -> Boolean,
    private val select: (MatchResult) -> IntRange = { it.range },
) : Rule {
    override fun findIn(text: String): List<RuleMatch> =
        pattern.findAll(text)
            .filter { validate(text, it) }
            .mapNotNull { m ->
                val r = select(m)
                if (r.isEmpty() || r.first < 0 || r.last >= text.length) null
                else RuleMatch(r, confidence)
            }
            .toList()
}
