package moe.flinty.yomark

import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.NameAnchor
import moe.flinty.yomark.rules.RuleMatch
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * 出厂规则在英文页上认出了什么（只在评测台里）。
 *
 * 规则逐行跑，与 RuleClassifier 同口径：行置信度 < 0.5 的不跑；要旁证的猜测（NeedsAnchor）按整页找旁证，
 * 旁证的「附近」用行框近似（app 里用的是字级框，差别只在同一行里的左右位置）。
 *
 * 用法：en-rules <ocr目录> <输出.json> [<地区，默认 CN>] [<名单目录：给了就加跑 EnProto>]
 *   地区决定 libphonenumber 的默认国家：中文系统上是 CN，美国号码不带 +1 时按中国号码解析。
 */

private class Box(val text: String, val conf: Float, val l: Float, val t: Float, val r: Float, val b: Float) {
    val h get() = b - t
}

private val SIZE = Regex(""""analysis":\[(\d+),(\d+)\]""")
private val LINE = Regex("""\{"text":"((?:[^"\\]|\\.)*)","conf":([0-9.Ee-]+),"box":\[([^\]]*)\]\}""")

private fun loadLines(f: File): List<Box> = LINE.findAll(f.readText()).map { m ->
    val (l, t, r, b) = m.groupValues[3].split(',').map { it.trim().toFloat() }
    Box(m.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\"), m.groupValues[2].toFloat(), l, t, r, b)
}.toList()

private fun nearby(a: Box, b: Box): Boolean {
    val h = max(a.h, b.h)
    val overlap = min(a.b, b.b) - max(a.t, b.t)
    if (overlap >= 0.5f * min(a.h, b.h)) return true
    val gap = max(0f, max(a.l, b.l) - min(a.r, b.r))
    return -overlap <= 1.5f * h && gap <= 2f * h
}

/** [lines] 上 [finder] 认出的东西，按 RuleClassifier 的口径过一遍旁证。 */
/** [unanchored] 不为 null 时，没有旁证的猜测不丢，放进它（弱一档：只圈不打码）。 */
private fun runRules(
    lines: List<Box>, findIn: (String) -> List<RuleMatch>, anchors: List<NameAnchor>, unanchored: MutableList<String>? = null,
): List<String> {
    val found = lines.map { l -> anchors.map { a -> runCatching { a.finder.findIn(l.text) }.getOrElse { emptyList() } } }
    val out = ArrayList<String>()
    lines.forEachIndexed { i, line ->
        runCatching { findIn(line.text) }.getOrElse { emptyList() }.forEach { m ->
            val ok = !m.needsAnchor || anchors.indices.any { k ->
                lines.indices.any { j ->
                    found[j][k].any { a ->
                        when (anchors[k].reach) {
                            NameAnchor.Reach.ATTACHED -> j == i && (a.range.first <= m.range.last + 2 && m.range.first <= a.range.last + 2)
                            NameAnchor.Reach.NEARBY -> if (j == i) a.range.last < m.range.first || a.range.first > m.range.last
                            else nearby(line, lines[j])
                        }
                    }
                }
            }
            if (ok) out += line.text.substring(m.range) else unanchored?.add(line.text.substring(m.range))
        }
    }
    return out
}

fun main(args: Array<String>) {
    val region = args.getOrNull(2) ?: "CN"
    Locale.setDefault(if (region == "CN") Locale.CHINA else Locale.US)
    val proto = args.getOrNull(3)?.let { EnProto(File(it)) }
    val enAnchors = proto?.let { p ->
        DefaultRuleSet.nameAnchors + listOf(
            NameAnchor("英文地址", NameAnchor.Reach.NEARBY, p.address),
            NameAnchor("外国电话", NameAnchor.Reach.NEARBY, p.foreignPhone),
        )
    }
    val result = StringBuilder("{\n")
    val files = File(args[0]).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
    files.forEachIndexed { fi, f ->
        val lines = loadLines(f).filter { it.conf >= 0.5f }
        val byRule = LinkedHashMap<String, List<String>>()
        DefaultRuleSet.rules.filter { it.id != "datetime" }.forEach { rule ->
            byRule[rule.id] = runRules(lines, rule::findIn, DefaultRuleSet.nameAnchors)
        }
        if (proto != null && enAnchors != null) {
            byRule["en-address"] = runRules(lines, proto.address::findIn, enAnchors)
            val weak = ArrayList<String>()
            byRule["en-name"] = runRules(lines, proto.nameLine::findIn, enAnchors, weak)
            byRule["en-name-weak"] = weak + lines.flatMap { l -> proto.standaloneName(l.text).map { l.text.substring(it.range) } }
            // 顶栏：页面最上面一成
            val pageH = SIZE.find(f.readText())!!.groupValues[2].toFloat()
            byRule["en-title"] = lines.filter { it.t < 0.1f * pageH }.flatMap { l -> proto.titleName(l.text).map { l.text.substring(it.range) } }
            byRule["en-phone"] = runRules(lines, proto.foreignPhone::findIn, enAnchors)
            val page = lines.map { EnProto.PageLine(it.text, it.l, it.t, it.r, it.b) }
            byRule["en-below-label"] = proto.pageFinds(page).map { (i, r) -> lines[i].text.substring(r) }
        }
        fun arr(xs: List<String>) = xs.joinToString(",", "[", "]") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        result.append("\"${f.nameWithoutExtension}\": {")
        result.append(byRule.entries.joinToString(", ") { (id, xs) -> "\"$id\": ${arr(xs)}" })
        result.append("}").append(if (fi < files.lastIndex) ",\n" else "\n")
    }
    File(args[1]).writeText(result.append("}\n").toString())
}
