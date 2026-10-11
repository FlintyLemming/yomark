package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind

/**
 * @param needsAnchor 这一处只是猜测：要页面上附近有电话、证件号、地址或「收货人」这类字段才算数。
 *   见 [NeedsAnchor]。
 */
data class RuleMatch(val range: IntRange, val confidence: Float, val needsAnchor: Boolean = false)

/** 一种认法：只管在一行里找出区间。类型与初始状态由外面的规则决定。 */
fun interface Finder {
    fun findIn(text: String): List<RuleMatch>
}

/**
 * 让一种认法的命中只算猜测，要靠页面上的旁证坐实。给同一类型里误报率高出一截的认法用：
 * 它与其余认法合在一条规则里、共用设置页上的一行，跟着那一行开关。
 *
 * 一行文字看不出旁证在哪，所以由 RuleClassifier 看整页再定：有旁证（旁证表见 DefaultRuleSet.nameAnchors：
 * 紧挨着的称呼，或者同一行、上下相邻一行的电话、证件号……）的，照这一行的设置打码或圈出；没有的，出厂丢掉，
 * 设置 › 文字 › 人名里打开「没有旁证的也圈出」才只圈不打码。
 */
class NeedsAnchor(private val finder: Finder) : Finder {
    override fun findIn(text: String): List<RuleMatch> =
        finder.findIn(text).map { it.copy(needsAnchor = true) }
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

    /**
     * 让给哪几类：同一行上与它们重叠的命中丢掉。合并只在同类之间做，两类抢同一处就会叠出两个框——
     * 「Date of birth」下面的「05 FEB 1965」是出生日期，不该再另圈一个日期时间。
     */
    val yieldsTo: Set<SensitiveKind> get() = emptySet()

    /** 整页上的命中。默认逐行跑 findIn；有看整页的认法（PageFinder）的规则覆写它。 */
    fun findOnPage(page: Page): List<PageMatch> =
        page.texts.indices.flatMap { i -> findIn(page.texts[i]).map { PageMatch(i, it) } }
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
    override val yieldsTo: Set<SensitiveKind> = emptySet(),
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
 *
 * 看整页的认法（PageFinder）也按这个先后排，只在 findOnPage 里跑。
 */
class CompositeRule(
    override val id: String,
    override val kind: SensitiveKind,
    override val enabledByDefault: Boolean,
    private vararg val finders: Finder,
    override val yieldsTo: Set<SensitiveKind> = emptySet(),
) : Rule {
    override fun findIn(text: String): List<RuleMatch> {
        val out = ArrayList<RuleMatch>()
        finders.forEach { f ->
            f.findIn(text).forEach { m -> if (out.none { it.range.overlaps(m.range) }) out += m }
        }
        return out
    }

    override fun findOnPage(page: Page): List<PageMatch> {
        val out = ArrayList<PageMatch>()
        finders.forEach { f ->
            // 一种认法出错，当它什么也没认出，不拖累同一条规则里的其余几种
            val found = if (f is PageFinder) runCatching { f.findOnPage(page) }.getOrElse { emptyList() }
            else page.texts.indices.flatMap { i ->
                runCatching { f.findIn(page.texts[i]) }.getOrElse { emptyList() }.map { PageMatch(i, it) }
            }
            found.forEach { m ->
                if (out.none { it.line == m.line && it.match.range.overlaps(m.match.range) }) out += m
            }
        }
        return out
    }
}

internal fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
