package moe.flinty.yomark.rules

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.SensitivityClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 判定层的规则实现（spec §4.3）。
 * 规则永远在 classifiers 列表的第一位且永不缺席——它是全部识别能力的地基。
 *
 * 规则大多逐行认；整页交给它们（[Page]），看整页的认法（PageFinder：字段名在上、值在下，机读码对照）自己去看。
 * 只圈不打码的命中（RuleMatch.outlineOnly，照片上孤零零一行的德国车牌这类）照这一类的开关出，出来一律只圈出。
 * 「要旁证的猜测」（[NeedsAnchor]，目前是从字面猜出的人名）在这里看整页：
 * 有旁证（[anchors]：紧挨着的称呼，或者同一行、同一排、上下相邻一行的电话、证件号……）的照规则的设置出；
 * 没有的，按 [keepUnanchored] 丢掉或只圈出。一行文字里看不出它下面那行是不是打了星的证件号，OCR 的框看得出。
 */
class RuleClassifier(
    private val rules: List<Rule>,
    /**
     * 附近没有旁证的猜测怎么办：false 丢掉（出厂），true 只圈不打码。
     * 设置 › 文字 › 人名的页上「没有旁证的也圈出」那个开关。
     */
    private val keepUnanchored: Boolean = false,
    /** 什么算旁证、多近才算。见 DefaultRuleSet.nameAnchors。 */
    private val anchors: List<NameAnchor> = DefaultRuleSet.nameAnchors,
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
        val kept = lines.indices.filter { lines[it].confidence >= minLineConfidence }
        // 按行排回去，同一行里按规则表的先后：与逐行跑规则时的出法一样
        groups(lines, kept).flatMap { classifyGroup(lines, it) }.sortedBy { it.first }.map { it.second }
    }

    /**
     * 一页上的一组行当一页认。规则按「下面 = +y」认版面：竖着、倒着拍的页先转到阅读方向上再取框
     * （正着的页原样）。候选的 quad 仍是图上的。给回（行号, 候选）。
     */
    private fun classifyGroup(lines: List<TextLine>, group: Group): List<Pair<Int, Candidate>> {
        val kept = group.kept
        val frame = group.frame
        fun measure(f: ReadingFrame) = { i: Int, range: IntRange -> f.bounds(lines[kept[i]].quadForRange(range)).toBounds() }
        val page = Page(
            // 全角数字（台湾证件上的「民國７９年１月１日」）逐字换成半角：下标不变，规则不用各认一遍
            texts = kept.map { halfWidthDigits(lines[it].text) },
            turned = group.own.withIndex().filter { it.value !== frame }.associate { (i, f) -> i to measure(f) },
            locate = measure(frame),
        )
        // 单条规则出错不能拖垮整次判定
        val found = rules.map { rule -> runCatching { rule.findOnPage(page) }.getOrElse { emptyList() } }

        val out = ArrayList<Pair<Int, Candidate>>()
        // 旁证只在有猜测要核对时才找：大多数页面上一个猜出来的人名都没有
        val anchorsOnPage by lazy { findAnchors(lines, kept, frame) }
        rules.forEachIndexed { r, rule ->
            found[r].forEachIndexed { n, hit ->
                val pm = yielded(rule, hit, found, page.texts) ?: return@forEachIndexed
                val m = pm.match
                val lineIndex = kept[pm.line]
                val line = lines[lineIndex]
                val quad = line.quadForRange(m.range)
                val anchored = !m.needsAnchor ||
                    anchorsOnPage.any { it.supports(line.text, lineIndex, m.range, frame.bounds(quad)) }
                if (!anchored && !keepUnanchored) return@forEachIndexed
                out += lineIndex to Candidate(
                    id = "rule-${rule.id}-$lineIndex-${m.range.first}-$n",
                    // 落在几行上的（上下两行的车牌）合成一个框
                    quad = if (pm.also.isEmpty()) quad
                    else Quad.boundingQuad(listOf(quad) + pm.also.map { lines[kept[it.line]].quadForRange(it.range) }),
                    kind = rule.kind,
                    source = DetectorSource.RULE,
                    confidence = m.confidence,
                    // 有旁证的猜测照规则的设置打码；没有旁证、又被设置留下来的只圈出
                    enabledByDefault = rule.enabledByDefault && anchored && !m.outlineOnly,
                    // 落在几行上的按头一行的方向
                    readingDegrees = group.own[pm.line].degrees,
                )
            }
        }
        return out
    }

    /**
     * [rule] 的命中 [m] 让给同一处的另一类之后还剩下的，null 是整个让掉。要让的：那一类在 Rule.yieldsTo 里；
     * 或者 [m] 只是只圈不打码的一档，同一处别的类型认出了东西。只让给那边有把握的命中（不是只圈出的、不是要旁证的猜测）：
     * 地址让给车牌，让给一个只圈出的车牌就是把打了码的降成只圈出。
     *
     * 那边只占了 [m] 的后半截（地址取到行尾，「地址：南京市玄武区中山路1号 车牌：苏A12345」）：截到它前面为止，
     * 夹在中间的车牌字段名（「车牌：」「号牌号码 Plate No.」）和空格标点一起去掉。别的字段名（「车辆：」「车位：」「Vehicle:」）
     * 认不全，跟着冒号的、隔着空格或逗号的末一段（没有数字：「1号：」是地址）也当字段名去掉。剩下不到两个字的，或者那边占了 [m] 的大半
     * （「苏A12345D」像爱尔兰邮编），整个让掉；只圈出的、落在几行上的（上下两行的车牌）也整个让掉。
     */
    private fun yielded(rule: Rule, m: PageMatch, found: List<List<PageMatch>>, texts: List<String>): PageMatch? {
        if (rule.yieldsTo.isEmpty() && !m.match.outlineOnly) return m
        val over = rules.indices.filter { o -> rules[o] !== rule && (rules[o].kind in rule.yieldsTo || m.match.outlineOnly) }
            .flatMap { o -> found[o].filter { !it.match.outlineOnly && !it.match.needsAnchor && it.overlaps(m) } }
        if (over.isEmpty()) return m
        if (m.match.outlineOnly || m.also.isNotEmpty()) return null
        val range = m.match.range
        val text = texts[m.line]
        val taken = over.flatMap { o -> o.spans.filter { it.line == m.line && it.range.overlaps(range) }.map { it.range } }
        val cut = taken.minOf { it.first }
        if (cut <= range.first) return null
        val solid = range.count { !text[it].isWhitespace() }
        if (2 * range.count { i -> !text[i].isWhitespace() && taken.any { i in it } } > solid) return null
        var end = cut
        while (true) {
            val before = end
            while (end > range.first && (text[end - 1].isWhitespace() || text[end - 1] in TRAILING)) end--
            val label = Plates.LABEL_AT_END.find(text.substring(range.first, end))
            if (label != null) {
                end = range.first + label.range.first
                continue
            }
            if ((end until before).none { text[it] in COLONS }) break
            val word = (end - 1 downTo range.first).firstOrNull { text[it].isWhitespace() || text[it] in WORD_BREAKS } ?: break
            if ((word + 1 until end).any { text[it].isDigit() }) break
            end = word + 1
        }
        if ((range.first until end).count { !text[it].isWhitespace() } <= 1) return null
        return m.copy(match = m.match.copy(range = range.first until end))
    }

    private fun RectF.toBounds() = Bounds(left, top, right, bottom)

    private fun halfWidthDigits(text: String): String =
        if (text.none { it in '０'..'９' }) text else String(CharArray(text.length) { i ->
            text[i].let { c -> if (c in '０'..'９') '0' + (c - '０') else c }
        })

    private fun findAnchors(lines: List<TextLine>, kept: List<Int>, frame: ReadingFrame): List<FoundAnchor> = kept.flatMap { lineIndex ->
        val line = lines[lineIndex]
        anchors.flatMap { anchor ->
            // 单条旁证出错，当它没找到
            runCatching { anchor.finder.findIn(line.text) }.getOrElse { emptyList() }
                .map { FoundAnchor(anchor.reach, lineIndex, it.range, frame.bounds(line.quadForRange(it.range))) }
        }
    }

    /** 页面上找到的一处旁证：第 [lineIndex] 行的 [range]，框为 [box]（阅读方向上的）。 */
    private class FoundAnchor(
        val reach: NameAnchor.Reach,
        val lineIndex: Int,
        val range: IntRange,
        val box: RectF,
    ) {

        /** 这处旁证撑不撑得住 [lineIndex] 行（文字是 [text]）[range] 上、框为 [box] 的那个猜测。 */
        fun supports(text: String, lineIndex: Int, range: IntRange, box: RectF): Boolean = when (reach) {
            NameAnchor.Reach.ATTACHED -> lineIndex == this.lineIndex && touches(text, range)
            NameAnchor.Reach.NEARBY -> isNearby(lineIndex, range, box)
        }

        /**
         * 紧挨着：落在猜测里头（「王女士」），或者和它之间只隔着空格（「王小明先生」「王小明 先生」，
         * 逐字切分的「王 小 明 先 生」也是）。
         */
        private fun touches(text: String, range: IntRange): Boolean {
            if (range.overlaps(this.range)) return true
            val gap = if (this.range.first > range.last) range.last + 1 until this.range.first
            else this.range.last + 1 until range.first
            return text.substring(gap).isBlank()
        }

        /**
         * 附近：
         * - 同一行：不和它重叠就算。重叠说明猜测就落在旁证里面——地址里的「中山」不因为这条地址就成了人名；
         * - 同一排（竖直方向重叠过半）：算。OCR 常把一排拆成几行，左边的字段名和右边的值各成一行；
         * - 上下相邻：竖直间隙不超过 [ROW_GAP] 个字高、水平方向基本对齐（错开不超过 [COLUMN_GAP] 个字高）。
         *   滴滴出票页上名字在上、打星的证件号在下，就是这一种。
         *
         * 字高取两个框里高的那个：名字常比下面的号码字号大。
         */
        private fun isNearby(lineIndex: Int, range: IntRange, box: RectF): Boolean {
            if (lineIndex == this.lineIndex) return !range.overlaps(this.range)
            val h = max(box.height(), this.box.height())
            if (h <= 0f) return false
            val overlap = min(box.bottom, this.box.bottom) - max(box.top, this.box.top)
            if (overlap >= SAME_ROW * min(box.height(), this.box.height())) return true
            val horizontalGap = max(0f, max(box.left, this.box.left) - min(box.right, this.box.right))
            return -overlap <= ROW_GAP * h && horizontalGap <= COLUMN_GAP * h
        }
    }

    /**
     * 一组行当一页认：[kept] 是 lines 的下标，[frame] 是这一组的阅读方向，[own] 与 [kept] 对齐、是每一行按哪个方向量：
     * 一般就是 [frame]；自己的方向和它差 [TURNED] 度以上的（截图里一张转过、拍歪了的小照片上的字，不够单分一组）是它自己的。
     */
    private class Group(val kept: List<Int>, val frame: ReadingFrame, val own: List<ReadingFrame>)

    companion object {
        const val MIN_LINE_CONFIDENCE = 0.5f

        /**
         * 每一行按哪个阅读方向认（度，同 ReadingFrame.degrees），分法同 classify；置信度不到 [minLineConfidence] 的行为 null。
         * 别的判定器（Gemini Nano）的候选也带上它，合并时才同规则的候选一样按各自的方向量（Candidate.readingDegrees）。
         */
        fun readingDegrees(lines: List<TextLine>, minLineConfidence: Float = MIN_LINE_CONFIDENCE): List<Float?> {
            val out = arrayOfNulls<Float>(lines.size)
            groups(lines, lines.indices.filter { lines[it].confidence >= minLineConfidence }).forEach { g ->
                g.kept.forEachIndexed { k, i -> out[i] = g.own[k].degrees }
            }
            return out.toList()
        }

        private fun groups(lines: List<TextLine>, kept: List<Int>): List<Group> = orientationGroups(lines, kept).map { g ->
            val frame = ReadingFrame.of(g.map { lines[it].quad })
            Group(g, frame, g.map { i ->
                val q = lines[i].quad
                val own = if (hypot(q.p1.x - q.p0.x, q.p1.y - q.p0.y) > 0f) ReadingFrame.of(listOf(q)) else frame
                if (own.angleTo(frame) > TURNED) own else frame
            })
        }

        /**
         * 一页上走向不同的几组字各当一页认：聊天截图里一张转了九十度、倒着放的证件照，聊天的字横着，证件的字竖着、倒着，
         * 整页只有一个阅读方向的话，证件上「下一行」「右边一栏」全对不上。行的阅读方向（quad 的 p0→p1）离哪个直角不到
         * [QUARTER] 度就归哪个直角；最长的那组之外，至少 [GROUP_LINES] 行、占总长 [GROUP_SHARE] 的才单独成组，
         * 斜着的水印、零星几行竖排的字归最长的那组。只有一组就是整页，同以前一样。
         */
        private fun orientationGroups(lines: List<TextLine>, kept: List<Int>): List<List<Int>> {
            val length = kept.associateWith { lines[it].quad.let { q -> hypot(q.p1.x - q.p0.x, q.p1.y - q.p0.y).toDouble() } }
            val quarter = kept.associateWith { quarterOf(lines[it].quad) }
            val total = length.values.sum()
            val byQuarter = kept.filter { quarter[it] != null }.groupBy { quarter[it]!! }
            val main = byQuarter.maxByOrNull { (_, group) -> group.sumOf { length.getValue(it) } }?.key ?: return listOf(kept)
            val apart = byQuarter.filter { (q, group) ->
                q != main && group.size >= GROUP_LINES && group.sumOf { length.getValue(it) } >= GROUP_SHARE * total
            }.keys
            if (apart.isEmpty()) return listOf(kept)
            return listOf(kept.filter { quarter[it] !in apart }) + apart.map { q -> kept.filter { quarter[it] == q } }
        }

        /** 一行的阅读方向离哪个直角不到 [QUARTER] 度：0 朝右、1 朝下、2 朝左、3 朝上；都差得远（斜着的）为 null。 */
        private fun quarterOf(q: Quad): Int? {
            val d = Math.toDegrees(atan2((q.p1.y - q.p0.y).toDouble(), (q.p1.x - q.p0.x).toDouble()))
            val k = (d / 90).roundToInt()
            return if (abs(d - k * 90) <= QUARTER) Math.floorMod(k, 4) else null
        }

        /** 一行自己的方向和它那一组的差过这么多度，按它自己的量（见 Group）。 */
        private const val TURNED = 5f

        /** 让开以后截短的一段末尾去掉的标点。 */
        private const val TRAILING = ",，、;；:：|｜/／·•.。-－–—"

        /** 截短的一段末尾跟着它的，前面那一段是字段名（见 yielded）。 */
        private const val COLONS = ":："

        /** 字段名和前面的值之间：空格之外的（「12 Main Street, Vehicle:」）。 */
        private const val WORD_BREAKS = ",，、;；|｜"

        /** 竖直方向重叠过这么多（按矮的那个框算）就是同一排。 */
        private const val SAME_ROW = 0.5f

        /** 上下两行之间的空隙最多几个字高还算相邻。界面上的行距一般在半个到一个字高。 */
        private const val ROW_GAP = 1.5f

        /** 上下两行水平方向最多错开几个字高还算对齐。 */
        private const val COLUMN_GAP = 2f

        /** 阅读方向离直角这么多度以内算那个直角（见 orientationGroups）。 */
        private const val QUARTER = 25.0

        /** 走向不同的一组至少这么多行、占总长这么多，才单独当一页认。 */
        private const val GROUP_LINES = 3
        private const val GROUP_SHARE = 0.15
    }
}
