package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind

/**
 * @param outlineOnly 这一处最多只圈出：规则选了「打码」也不自动打码。见 [OutlineOnly]。
 */
data class RuleMatch(val range: IntRange, val confidence: Float, val outlineOnly: Boolean = false)

/** 一种认法：只管在一行里找出区间。类型与初始状态由外面的规则决定。 */
fun interface Finder {
    fun findIn(text: String): List<RuleMatch>
}

/**
 * 让一种认法的命中最多只圈出。给同一类型里误报率高出一截的认法用：
 * 它与其余认法合在一条规则里、共用设置页上的一行，跟着那一行开关；
 * 那一行选「打码」时，它的命中仍然只圈不打码——与条码宽松档里解不出内容的疑似条码一个道理。
 */
class OutlineOnly(private val finder: Finder) : Finder {
    override fun findIn(text: String): List<RuleMatch> =
        finder.findIn(text).map { it.copy(outlineOnly = true) }
}

/**
 * 一条敏感规则。规则集是数据不是代码（spec §6）——
 * 加一条规则 = 往 DefaultRuleSet.rules 里加一个对象，不改任何逻辑。
 */
interface Rule : Finder {
    val id: String
    val kind: SensitiveKind
    /** 进编辑器时的初始状态。按误报率划线，不按危害划线（spec §6）。 */
    val enabledByDefault: Boolean
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

/**
 * 同一类型的几种认法合成一条规则。设置页上一类只有一行、出厂态只有一个，
 * 用户不需要分清是哪种认法命中的。
 *
 * **排在前面的认法优先**：后面的认法给出的区间只要与前面任何一个重叠就丢掉。
 * 姓名、地址都把标签锚定放在第一位——它知道值从哪里开始；
 * 靠形状、靠邻居猜的认法排在后面，只补标签锚定够不着的地方。
 * 不这样做，同一个名字会被两种认法各框一次，合并后框的边界取决于谁猜得更宽。
 */
class CompositeRule(
    override val id: String,
    override val kind: SensitiveKind,
    override val enabledByDefault: Boolean,
    private vararg val finders: Finder,
) : Rule {
    override fun findIn(text: String): List<RuleMatch> {
        val out = ArrayList<RuleMatch>()
        finders.forEach { f ->
            f.findIn(text).forEach { m -> if (out.none { it.range.overlaps(m.range) }) out += m }
        }
        return out
    }
}

internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
