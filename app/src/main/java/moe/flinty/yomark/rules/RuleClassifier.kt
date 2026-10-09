package moe.flinty.yomark.rules

import android.graphics.RectF
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.SensitivityClassifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * 判定层的规则实现（spec §4.3）。
 * 规则永远在 classifiers 列表的第一位且永不缺席——它是全部识别能力的地基。
 *
 * 规则逐行认；只有「要旁证的猜测」（[NeedsAnchor]，目前是从字面猜出的人名）在这里看整页：
 * 同一行、同一排或上下相邻一行有旁证（[anchors]）的照规则的设置出；附近没有的，按 [keepUnanchored]
 * 丢掉或只圈出。一行文字里看不出它下面那行是不是打了星的证件号，OCR 的框看得出。
 */
class RuleClassifier(
    private val rules: List<Rule>,
    /**
     * 附近没有旁证的猜测怎么办：false 丢掉（出厂），true 只圈不打码。
     * 设置 › 文字 › 人名的页上「没有旁证的也圈出」那个开关。
     */
    private val keepUnanchored: Boolean = false,
    /** 什么算旁证。见 DefaultRuleSet.nameAnchors。 */
    private val anchors: Finder = DefaultRuleSet.nameAnchors,
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
        // 旁证只在有猜测要核对时才找：大多数页面上一个猜出来的人名都没有
        val anchorsOnPage by lazy { findAnchors(lines) }
        lines.forEachIndexed { lineIndex, line ->
            if (line.confidence < minLineConfidence) return@forEachIndexed
            rules.forEach { rule ->
                // 单条规则出错不能拖垮整次判定
                val matches = runCatching { rule.findIn(line.text) }.getOrElse { emptyList() }
                matches.forEachIndexed { i, m ->
                    val quad = line.quadForRange(m.range)
                    val anchored = !m.needsAnchor ||
                        anchorsOnPage.any { it.supports(lineIndex, m.range, quad.bounds()) }
                    if (!anchored && !keepUnanchored) return@forEachIndexed
                    out += Candidate(
                        id = "rule-${rule.id}-$lineIndex-${m.range.first}-$i",
                        quad = quad,
                        kind = rule.kind,
                        source = DetectorSource.RULE,
                        confidence = m.confidence,
                        // 有旁证的猜测照规则的设置打码；没有旁证、又被设置留下来的只圈出
                        enabledByDefault = rule.enabledByDefault && anchored,
                    )
                }
            }
        }
        out
    }

    private fun findAnchors(lines: List<TextLine>): List<Anchor> = lines.flatMapIndexed { lineIndex, line ->
        if (line.confidence < minLineConfidence) return@flatMapIndexed emptyList()
        runCatching { anchors.findIn(line.text) }.getOrElse { emptyList() }
            .map { Anchor(lineIndex, it.range, line.quadForRange(it.range).bounds()) }
    }

    private class Anchor(val lineIndex: Int, val range: IntRange, val box: RectF) {

        /**
         * 这处旁证撑不撑得住 [lineIndex] 行 [range] 上、框为 [box] 的那个猜测。
         *
         * - 同一行：不和它重叠就算。重叠说明猜测就落在旁证里面——地址里的「中山」不因为这条地址就成了人名；
         * - 同一排（竖直方向重叠过半）：算。OCR 常把一排拆成几行，左边的字段名和右边的值各成一行；
         * - 上下相邻：竖直间隙不超过 [ROW_GAP] 个字高、水平方向基本对齐（错开不超过 [COLUMN_GAP] 个字高）。
         *   滴滴出票页上名字在上、打星的证件号在下，就是这一种。
         *
         * 字高取两个框里高的那个：名字常比下面的号码字号大。
         */
        fun supports(lineIndex: Int, range: IntRange, box: RectF): Boolean {
            if (lineIndex == this.lineIndex) return !range.overlaps(this.range)
            val h = max(box.height(), this.box.height())
            if (h <= 0f) return false
            val overlap = min(box.bottom, this.box.bottom) - max(box.top, this.box.top)
            if (overlap >= SAME_ROW * min(box.height(), this.box.height())) return true
            val horizontalGap = max(0f, max(box.left, this.box.left) - min(box.right, this.box.right))
            return -overlap <= ROW_GAP * h && horizontalGap <= COLUMN_GAP * h
        }
    }

    companion object {
        const val MIN_LINE_CONFIDENCE = 0.5f

        /** 竖直方向重叠过这么多（按矮的那个框算）就是同一排。 */
        private const val SAME_ROW = 0.5f

        /** 上下两行之间的空隙最多几个字高还算相邻。界面上的行距一般在半个到一个字高。 */
        private const val ROW_GAP = 1.5f

        /** 上下两行水平方向最多错开几个字高还算对齐。 */
        private const val COLUMN_GAP = 2f
    }
}
