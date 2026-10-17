package moe.flinty.yomark.ui.bigbang

import android.graphics.PointF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.TextLine
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * 大爆炸（2026-10-16，spec §7.3 修订）：长按图上的字，这张图识别出的字一个个摆出来（见 BigBangPanel），
 * 点、划着选几个字，打上码。挨着的几个字打成一块码，不是一个字一块。
 *
 * 这里是页上的字和它们怎么连成块，全是纯函数。面板上摆字块见 BigBangPanel，画布上的预览、点字、划字见 ImageCanvas。
 */

/**
 * 页上的一个字。
 *
 * @param id 整页里的序号，按阅读顺序：选中的字记的是它
 * @param line 第几行（[BigBangPage.lines] 的下标）
 * @param text 这一个字。字典里 BMP 以外的字是两个 UTF-16 单元
 * @param quad 它在图上的框，p0→p1 是阅读方向。PP-OCR 的字框按 CTC 时间步定位、分界落在像素空当上（见 PpOcrEngine.line）；
 *   ML Kit 用它给的每个字的框（TextElement.chars），没有的话把词框按字宽（汉字算两份）分开
 * @param spaceBefore 和前一个字之间隔着空格（模型读出的，或按像素间隙补回来的词界）
 */
data class Glyph(
    val id: Int,
    val line: Int,
    val text: String,
    val quad: Quad,
    val spaceBefore: Boolean,
)

/** 一行字。[paragraphBefore]：和上一行在图上隔得远（空出了大半行），面板上多空一点，看得出是另一块。 */
class BigBangLine(val glyphs: List<Glyph>, val paragraphBefore: Boolean)

/** 一张图上识别出的字，按行、按阅读顺序。 */
class BigBangPage(val lines: List<BigBangLine>) {

    /** 整页的字，下标就是 [Glyph.id]。 */
    val glyphs: List<Glyph> = lines.flatMap { it.glyphs }

    val isEmpty: Boolean get() = glyphs.isEmpty()

    /** 第 i 个字选中时和第 i + 1 个连不连成一块（[joins]），拆页时一次算好：画布、面板每次选字都要用。 */
    private val joinsNext = BooleanArray(glyphs.size) { i -> i + 1 < glyphs.size && joins(glyphs[i], glyphs[i + 1]) }

    /** 每个字框的外接矩形（左、上、右、下），找手指下的字时先拿它筛：长截图上几千个字，不能每个都算一遍四边形。 */
    private val bounds = FloatArray(glyphs.size * 4).also { b ->
        glyphs.forEachIndexed { i, g ->
            val r = g.quad.bounds()
            b[i * 4] = r.left; b[i * 4 + 1] = r.top; b[i * 4 + 2] = r.right; b[i * 4 + 3] = r.bottom
        }
    }

    /** 第 [i] 个字和第 i + 1 个字选中时连成一块。 */
    fun joinsNext(i: Int): Boolean = i in joinsNext.indices && joinsNext[i]

    /**
     * [p]（图像坐标）上的那个字：落在几个字的框里取最小的那个；一个都不在，取 [reach] 以内最近的。
     * 和点字打码（GestureRules.lineAt）同一个口径。
     */
    fun glyphAt(p: PointF, reach: Float): Glyph? {
        var inside = -1
        var insideArea = Float.MAX_VALUE
        var near = -1
        var nearDistance = Float.MAX_VALUE
        for (i in glyphs.indices) {
            val k = i * 4
            if (p.x < bounds[k] - reach || p.x > bounds[k + 2] + reach || p.y < bounds[k + 1] - reach || p.y > bounds[k + 3] + reach) continue
            val q = glyphs[i].quad
            if (q.contains(p)) {
                val area = q.area()
                if (area < insideArea) { insideArea = area; inside = i }
            } else if (inside < 0) {
                val d = q.distanceTo(p)
                if (d <= reach && d < nearDistance) { nearDistance = d; near = i }
            }
        }
        return glyphs.getOrNull(if (inside >= 0) inside else near)
    }

    /**
     * 手指从 [from] 划到 [to]（图像坐标）经过的字，按经过的先后，不重复。每隔 [step] 像素看一次：
     * 手指快的时候两次事件之间隔着好几个字，只看事件落点的话中间那几个就漏了。
     * [withStart] 为 false 时不看 [from] 本身：接着上一段划的，上一段已经看过它。
     */
    fun glyphsAlong(from: PointF, to: PointF, reach: Float, step: Float, withStart: Boolean = true): List<Glyph> {
        val length = hypot(to.x - from.x, to.y - from.y)
        val n = if (step <= 0f) 1 else max(1, ceil(length / step).toInt())
        val out = LinkedHashSet<Glyph>()
        for (k in (if (withStart) 0 else 1)..n) {
            val t = k.toFloat() / n
            glyphAt(PointF(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t), reach)?.let { out += it }
        }
        return out.toList()
    }

    /**
     * 选中的字连成的一块块：同一行里前后挨着、都选中了、又连得上（[joins]）的几个字是一块。
     * 一块里的字按阅读顺序，块按第一个字的先后。
     */
    fun runs(selected: Set<Int>): List<List<Glyph>> {
        val out = ArrayList<List<Glyph>>()
        var run = ArrayList<Glyph>()
        for (id in selected.filter { it in glyphs.indices }.sorted()) {
            if (run.isNotEmpty() && !(run.last().id == id - 1 && joinsNext[id - 1])) {
                out += run
                run = ArrayList()
            }
            run += glyphs[id]
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    /** 选中的字要打的码：一块一个框（[runQuad]）。 */
    fun runQuads(selected: Set<Int>): List<Quad> = runs(selected).map { runQuad(it) }

    /** 已经被打码的样式（色块、马赛克、模糊）盖住的字：字的中心落在码里。马克笔、画笔、文本底下的字还看得见，不算。 */
    fun covered(items: List<MaskItem>): Set<Int> {
        val masks = items.filter { it.look.style.redacts }.map { it.quad to it.quad.bounds() }
        if (masks.isEmpty()) return emptySet()
        val out = HashSet<Int>()
        glyphs.forEach { g ->
            val c = g.quad.center()
            if (masks.any { (quad, r) -> c.x >= r.left && c.x <= r.right && c.y >= r.top && c.y <= r.bottom && quad.contains(c) }) out += g.id
        }
        return out
    }

    companion object {

        /**
         * 两个前后挨着的字，选中时连成一块的门槛：中间空着的不到这么多个字高。
         * PP-OCR 同一段里的字框首尾相接（检测框成了一段，字和字之间的分界落在空当上），总是连着的，中间隔着空格也是；
         * 隔开的两段，像「取件码」和后面的号码之间那一截，隔得近的照样连上，隔得远的（并排的几个按钮、左右两栏）各打各的，
         * 中间那一截空白不涂。ML Kit 一行里的几个词也照这个量：中文识别器会把一整排拼成一行。
         */
        const val JOIN_GAP = 1f

        /** 和上一行之间空出这么多个字高以上，面板上当作另起一段（[BigBangLine.paragraphBefore]）。 */
        const val PARAGRAPH_GAP = 0.8f

        /** 两行的框重叠过这么多（交并比），是同一行读了两遍：ML Kit 的英文、中文两个识别器各读一遍，留读得有把握的那个。 */
        const val SAME_LINE_IOU = 0.5f

        val EMPTY = BigBangPage(emptyList())

        /** 识别出的几行拆成字。没有字的行（全是空格）不要，同一行读了两遍的只留一遍（[distinct]）。 */
        fun of(lines: List<TextLine>): BigBangPage {
            val out = ArrayList<BigBangLine>()
            var next = 0
            var previous: TextLine? = null
            for (line in distinct(lines)) {
                val glyphs = glyphsOf(line, out.size, next)
                if (glyphs.isEmpty()) continue
                out += BigBangLine(glyphs, paragraphBefore = previous != null && apart(previous, line))
                next += glyphs.size
                previous = line
            }
            return BigBangPage(out)
        }

        /**
         * 框重叠过 [SAME_LINE_IOU] 的两行只留一行：置信度高的，一样高留先来的。PP-OCR 拼过行，不会有这种两行；
         * ML Kit 把拉丁和中文两个识别器的结果直接摞在一起（CompositeTextRecognizer），每一行都有两份，
         * 拉丁那份在中文上多半是乱码。
         */
        private fun distinct(lines: List<TextLine>): List<TextLine> {
            if (lines.size < 2) return lines
            val boxes = lines.map { it.quad.bounds() }
            return lines.filterIndexed { i, line ->
                lines.indices.none { j ->
                    j != i && android.graphics.RectF.intersects(boxes[i], boxes[j]) &&
                        (lines[j].confidence > line.confidence || (lines[j].confidence == line.confidence && j < i)) &&
                        line.quad.iou(lines[j].quad) > SAME_LINE_IOU
                }
            }
        }

        /**
         * 一行里的字。element 是 PP-OCR 的单个字、ML Kit 的一个词：一个 element 里有几个字，就拆成几个字，
         * 框用 element 给的每个字的框（TextElement.chars），没给就把词框按字宽分开（[splitWeights]）。
         * PP-OCR 给 BMP 以外的字两个 element（一个 UTF-16 单元一个，框相同），这里并成一个字。
         */
        private fun glyphsOf(line: TextLine, lineIndex: Int, firstId: Int): List<Glyph> {
            val text = line.text
            val out = ArrayList<Glyph>()
            // 读到哪儿了；上一个字在哪儿结束（它和这个字之间有没有空格从这儿看）
            var consumed = 0
            var lastEnd = 0
            for (e in line.elements.sortedBy { it.range.first }) {
                val start = max(e.range.first, consumed).coerceAtLeast(0)
                var end = min(e.range.last + 1, text.length)
                if (start >= end) continue
                // 只占了半个代理对：后半个也算这个字的
                if (end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end++
                val pieces = codePoints(text, start, end)
                val chars = e.chars?.takeIf { it.size == pieces.size }
                val edges = if (chars == null && pieces.size > 1) splitWeights(text, pieces) else null
                pieces.forEachIndexed { k, range ->
                    val piece = text.substring(range.first, range.last + 1)
                    if (piece.isBlank()) return@forEachIndexed
                    val quad = when {
                        chars != null -> chars[k]
                        edges == null -> e.quad
                        else -> e.quad.slice(edges[k], edges[k + 1])
                    }
                    out += Glyph(
                        id = firstId + out.size,
                        line = lineIndex,
                        text = piece,
                        quad = quad,
                        spaceBefore = out.isNotEmpty() && text.substring(lastEnd, range.first).any { it.isWhitespace() },
                    )
                    lastEnd = range.last + 1
                }
                consumed = end
            }
            return out
        }

        /**
         * 没有每个字的框时，一个词框里每个字占多宽：汉字、假名、谚文、全角符号算两份，别的算一份（截图上一个汉字约是两个数字宽），
         * 返回各字左右边界在 0..1 上的位置（比字数多一个）。等分的话「手机号13812345678」里遮中间四位，码会错开快一个字。
         */
        private fun splitWeights(text: String, pieces: List<IntRange>): FloatArray {
            val weights = pieces.map { if (isWide(text.codePointAt(it.first))) 2f else 1f }
            val total = weights.sum()
            val edges = FloatArray(pieces.size + 1)
            for (k in weights.indices) edges[k + 1] = edges[k] + weights[k] / total
            edges[pieces.size] = 1f
            return edges
        }

        private fun isWide(cp: Int): Boolean {
            val script = Character.UnicodeScript.of(cp)
            return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
                script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL ||
                cp in 0x3000..0x303F || cp in 0xFF01..0xFF60
        }

        /** [text] 里 [start] until [end] 这一截按码点切开，每个码点在 [text] 里的区间。 */
        private fun codePoints(text: String, start: Int, end: Int): List<IntRange> {
            val out = ArrayList<IntRange>()
            var i = start
            while (i < end) {
                val n = Character.charCount(text.codePointAt(i)).coerceAtMost(end - i)
                out += i until i + n
                i += n
            }
            return out
        }

        /** 两行在图上隔开了 [PARAGRAPH_GAP] 个字高以上（按图的上下量；转过的页上量的是左右，近似就够）。 */
        private fun apart(above: TextLine, below: TextLine): Boolean {
            val a = above.quad.bounds()
            val b = below.quad.bounds()
            val h = min(height(above.quad), height(below.quad))
            return b.top - a.bottom > PARAGRAPH_GAP * h
        }

        /**
         * 选中的 [a] 和紧跟在它后面的 [b] 连不连成一块：同一行、前后紧挨着，两个字框之间空着的不到 [JOIN_GAP] 个字高
         * （按 [a] 的阅读方向量）。识别结果（PP-OCR 分的段、切的字框）和字与字的距离合起来看：
         * 同一段里的字框首尾相接，隔着空格的词也连上，「张 三」打成一块；隔开的两段看隔得多远。
         */
        fun joins(a: Glyph, b: Glyph): Boolean {
            if (a.line != b.line || b.id != a.id + 1) return false
            val frame = Frame.of(a.quad)
            val gap = frame.span(b.quad).first - frame.span(a.quad).second
            return gap <= JOIN_GAP * min(height(a.quad), height(b.quad))
        }

        /**
         * 一块字打的码：按第一个字的阅读方向，把这几个字的框都包进去的那个矩形。
         * 同一段里的字框是从同一个检测框上切下来的，包出来就是那一截，不多不少；跨了段的上下取两段的并。
         */
        fun runQuad(run: List<Glyph>): Quad {
            require(run.isNotEmpty()) { "一块码至少一个字" }
            val frame = Frame.of(run.first().quad)
            var u0 = Float.MAX_VALUE; var u1 = -Float.MAX_VALUE
            var v0 = Float.MAX_VALUE; var v1 = -Float.MAX_VALUE
            for (g in run) {
                val (a, b) = frame.span(g.quad)
                val (c, d) = frame.across(g.quad)
                u0 = min(u0, a); u1 = max(u1, b)
                v0 = min(v0, c); v1 = max(v1, d)
            }
            return Quad(frame.at(u0, v0), frame.at(u1, v0), frame.at(u1, v1), frame.at(u0, v1))
        }

        /** 字高：框沿 p0→p3 的长度（两条竖边取长的）。 */
        internal fun height(q: Quad): Float = max(hypot(q.p3.x - q.p0.x, q.p3.y - q.p0.y), hypot(q.p2.x - q.p1.x, q.p2.y - q.p1.y))
    }
}

/**
 * 以一个框的阅读方向为轴的坐标系：u 沿 p0→p1，v 垂直于它、朝 p3 那一边，原点在 p0。
 * 歪着的、转过的字也照它自己的方向量前后、上下。
 */
private class Frame(private val origin: PointF, private val ux: Float, private val uy: Float, private val vx: Float, private val vy: Float) {

    /** [q] 的四个角在 u 轴上的范围。 */
    fun span(q: Quad): Pair<Float, Float> = range(q) { (it.x - origin.x) * ux + (it.y - origin.y) * uy }

    /** [q] 的四个角在 v 轴上的范围。 */
    fun across(q: Quad): Pair<Float, Float> = range(q) { (it.x - origin.x) * vx + (it.y - origin.y) * vy }

    fun at(u: Float, v: Float) = PointF(origin.x + ux * u + vx * v, origin.y + uy * u + vy * v)

    private inline fun range(q: Quad, f: (PointF) -> Float): Pair<Float, Float> {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (p in q.points()) { val x = f(p); lo = min(lo, x); hi = max(hi, x) }
        return lo to hi
    }

    companion object {
        fun of(q: Quad): Frame {
            var dx = q.p1.x - q.p0.x
            var dy = q.p1.y - q.p0.y
            val len = hypot(dx, dy)
            if (len < 1e-3f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            // 垂直于阅读方向、朝 p3 那一边
            var vx = -dy
            var vy = dx
            if ((q.p3.x - q.p0.x) * vx + (q.p3.y - q.p0.y) * vy < 0f) { vx = -vx; vy = -vy }
            return Frame(q.p0, dx, dy, vx, vy)
        }
    }
}

/** 选中、取消的几种改法。面板上、画布上点字划字都走这里。 */
object BigBangSelection {

    /** 点了一个字：没选中的选上，选中的取消。 */
    fun toggle(selected: Set<Int>, id: Int): Set<Int> = if (id in selected) selected - id else selected + id

    /**
     * 一划：从哪个字起手定这一划是选还是取消（起手的字没选中就是选，[select] 为 true），划过的字都照这个改。
     * 划回头经过已经改过的字不翻回去：同一划里一个字只有一个结果。
     */
    fun sweep(selected: Set<Int>, ids: Iterable<Int>, select: Boolean): Set<Int> =
        if (select) selected + ids else selected - ids.toSet()
}

/** 四边形沿 p0→p1 截出 [u0, u1] 那一段（0..1），上下不变。 */
internal fun Quad.slice(u0: Float, u1: Float): Quad {
    fun lerp(a: PointF, b: PointF, t: Float) = PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
    return Quad(lerp(p0, p1, u0), lerp(p0, p1, u1), lerp(p3, p2, u1), lerp(p3, p2, u0))
}

internal fun Quad.center(): PointF = PointF((p0.x + p1.x + p2.x + p3.x) / 4f, (p0.y + p1.y + p2.y + p3.y) / 4f)
