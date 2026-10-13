package moe.flinty.yomark.engine.ppocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv6 small（检测 + 识别）跑在 ONNX Runtime 上（spec §14 的预留位）。
 *
 * 输入是 ARGB 像素数组而不是 Bitmap：核心不碰 android.graphics，单测才能在 JVM 上跑真模型。
 *
 * 流程与 PaddleOCR 的 predict_system 对齐：检测 → 按阅读顺序排框 → 逐框切图识别 →
 * 丢掉置信度 < 0.5 的行。另加两步 PaddleOCR 没有、而规则层需要的：
 * - 字符级的框：由 CTC 时间步推出每个字的横向位置，遮罩能精确到字（ML Kit 只到词）；
 * - 词界空格：见 WordGaps。
 * 最后把同一行上被检测拆开的几段拼回一条（RowGrouper），「取件码」和「1-58908」
 * 被拆成两个框时，标签锚定照样接得上。
 *
 * 版面不正的（竖着拍的证件、倒着的页、歪着的照片）先转正再读，见 [recognize]。
 *
 * 线程安全：ONNX Runtime 的 session 允许并发 run，但这里的调用方一次只跑一张图。
 */
class PpOcrEngine(
    detModel: ByteArray,
    recModel: ByteArray,
    private val dict: List<String>,
    threads: Int = 4,
) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val det = env.createSession(detModel, options)
    private val rec = env.createSession(recModel, options)

    /**
     * 先在原图上检测，看文字行的走向（[TextAxis]）：
     * - 横平的（大多数截图）：照常逐框读。读得磕磕绊绊（平均置信度不到 [UPRIGHT_SCORE]）的，拿最长的几行倒过来再读一遍，
     *   明显更好就是整页倒着，转半圈读；
     * - 竖着的、歪着的：转到行是横平的那个方向上读。差半圈的两个方向里挑哪个，问识别器自己——
     *   各读一遍最长的几行，取读得更有把握的；
     * - 看不出走向的（横竖都有）：照常读。
     *
     * 换方向要换得有底气：另一个方向得比默认的高出 [MARGIN]，自己也得读到 [UPRIGHT_SCORE]。
     * 识别器认不得的文字（韩文、阿拉伯文）正着倒着都读不好，两边比出来的高低不作数。
     *
     * 转正了读不重新检测：原图上的框逐点转到转正的画布上，排序、拼行（RowGrouper）都在画布上做，跟正着的页一样；
     * 最后把每行、每个字的框逐点映射回原图。四个角仍按「阅读方向的左上、右上、右下、左下」排，
     * TextLine.quad 的 p0→p1 就是原图上的阅读方向，规则层靠它转回来（ReadingFrame）。
     *
     * 只有顺着走向的框在画布上读。垂直的（竖着放的证件旁边正着的聊天记录、表单）、斜着的（水印）还在原图上照常读、照常拼行，
     * 一两个字的小框怎么分见 [readTurned]。行的先后：先是画布上的，按转正后的阅读顺序，再是原图上的。
     *
     * 斜着的框压住的那几排字（水印连着字检测成了一块），横着切出来再读一遍，见 [underTilted]。
     */
    fun recognize(pixels: IntArray, width: Int, height: Int): List<OcrLine> {
        val image = Argb(pixels, width, height)
        val boxes = inReadingOrder(detect(image))
        val axis = TextAxis.of(boxes)
        if (axis == null || axis == 0f) {
            val reads = boxes.map { read(image, it) }
            val lines = if (axis == null) RowGrouper.group(reads.mapNotNull { it?.line })
            else rowsIn(image, boxes.zip(reads.map { it?.line }), boxes)
            if (axis == null || score(reads) >= UPRIGHT_SCORE) return lines
            val sample = TextAxis.sample(boxes, 0f)
            val flipped = trial(image, boxes, sample, 180f)
            return if (flipped.beats(score(sample.map { reads[it] }))) readTurned(image, boxes, 0f, flipped, reads) else lines
        }
        // 转多少度行才横平；竖着的默认往下读（逆时针转 90°），同以前逐框转的方向
        val turn = -axis
        val other = if (turn > 0) turn - 180 else turn + 180
        val (preferred, alternative) = if (abs(axis) > 45 && turn > 0) other to turn else turn to other
        val sample = TextAxis.sample(boxes, axis)
        val first = trial(image, boxes, sample, preferred)
        // 默认的方向已经读到 1 − MARGIN 以上时，另一个不可能再高出这么多，不用试
        val second = if (first.score + MARGIN < 1f) trial(image, boxes, sample, alternative) else null
        return readTurned(image, boxes, axis, if (second != null && second.beats(first.score)) second else first, null)
    }

    /**
     * 试一个方向：原图顺时针转 [degrees] 度，读 [sample] 这几个框。读出来的结果留着，选中这个方向时不再读第二遍。
     * [score] 是按框长加权的平均置信度。
     */
    private class Trial(val view: Turned, val reads: Map<Int, Read?>, val score: Float) {

        /** 比默认的方向（读出 [default]）明显更好：高出 [MARGIN]，自己也读到 [UPRIGHT_SCORE]。 */
        fun beats(default: Float): Boolean = score >= UPRIGHT_SCORE && score > default + MARGIN
    }

    private fun trial(image: Pixels, boxes: List<Box>, sample: List<Int>, degrees: Float): Trial {
        val view = Turned(image, degrees)
        val reads = sample.associateWith { read(view, upright(view, boxes[it])) }
        return Trial(view, reads, score(sample.map { reads[it] }))
    }

    /** 原图上的框转到画布上，角重新按阅读方向排。 */
    private fun upright(view: Turned, box: Box): Box = DbPostProcess.order(box.corners().map(view::fromSource))

    /**
     * 按 [chosen] 的方向读整页，行和字的框映射回原图。顺着 [axis] 的框在画布上读，垂直的、斜着的在原图上照常读。
     * 方的小框（一两个字）跟着整页走；页上有垂直的行时，分不出它是哪边的，两边都读、取置信度高的——
     * 转了直角的字读出来置信度差得远，斜几十度的字倒还读得出，所以斜着的方框按边的方向分，不比置信度。
     * [inSource] 是原图上已经读过的结果（下标同 [boxes]），有就不再读。
     */
    private fun readTurned(image: Pixels, boxes: List<Box>, axis: Float, chosen: Trial, inSource: List<Read?>?): List<OcrLine> {
        val view = chosen.view
        val fits = boxes.map { TextAxis.fit(it, axis) }
        val crossed = TextAxis.Fit.CROSS in fits
        // 读不出字的也留着框：它可能是字和水印连成的一块（见 rowsIn）
        val turned = ArrayList<Pair<Box, OcrLine?>>()
        val level = ArrayList<Pair<Box, OcrLine?>>()
        boxes.forEachIndexed { i, box ->
            val onCanvas = upright(view, box)
            val canvasRead = { if (i in chosen.reads) chosen.reads[i] else read(view, onCanvas) }
            val sourceRead = { if (inSource != null) inSource[i] else read(image, box) }
            when (fits[i]) {
                TextAxis.Fit.ALONG -> turned += onCanvas to canvasRead()?.line
                TextAxis.Fit.CROSS, TextAxis.Fit.OTHER -> level += box to sourceRead()?.line
                TextAxis.Fit.SQUARE -> if (!crossed) canvasRead()?.line?.let { turned += onCanvas to it } else {
                    val c = canvasRead()
                    val s = sourceRead()
                    if (s != null && s.confidence > (c?.confidence ?: -1f)) s.line?.let { level += box to it }
                    else c?.line?.let { turned += onCanvas to it }
                }
            }
        }
        val ordered = turned.sortedWith(compareBy(readingOrder) { it.first })
        return rowsIn(view, ordered, boxes.map { upright(view, it) }).map { line ->
            line.copy(box = line.box.map(view::toSource), charBoxes = line.charBoxes.map { it?.map(view::toSource) })
        } + rowsIn(image, level, boxes)
    }

    /**
     * 一个画面（原图，或转正了的画布）上读的行拼成排。没有斜框的照常拼（RowGrouper）。
     *
     * 页上有斜框（水印）时：横平的行另外拼，压着斜框的那几排横着重读（[underTilted]）；斜框读出来的行另拼，
     * 不和横平的串成一行（以前「民 族」「18 日」串进了水印那一行）。转正了读的页上，原图上读的那些（水印比证件上的字还长时，
     * 证件反倒在原图上读）也这样拼一遍。
     *
     * 横平的框斜了 [LEVEL_SLACK] 度以上的也当斜框：那是字和水印连成的一块。中文水印斜二十几度时，
     * 「浙江省杭州市西湖区文三路」和压着它的「仅供办理银行开户使用」连成了一个斜 9° 的框，什么都读不出来。
     *
     * @param read 在 [view] 上读的框和读出来的行（读不出的为 null）；[all] 页上所有的框，都在 [view] 上。
     */
    private fun rowsIn(view: Pixels, read: List<Pair<Box, OcrLine?>>, all: List<Box>): List<OcrLine> {
        if (all.none { TextAxis.fit(it, 0f) == TextAxis.Fit.OTHER }) return RowGrouper.group(read.mapNotNull { it.second })
        val (level, rest) = read.partition { (box, _) -> isLevel(box) }
        val tilted = all.filter { TextAxis.fit(it, 0f) != TextAxis.Fit.CROSS && !isLevel(it) }
        val rows = RowGrouper.group(underTilted(view, level.mapNotNull { it.second }, tilted))
        return (rows + RowGrouper.group(rest.mapNotNull { it.second })).sortedWith(compareBy<OcrLine> { it.box.minY }.thenBy { it.box.minX })
    }

    /** 横平的一行字或一两个字的方框：上沿偏离横平不过 [LEVEL_SLACK] 度。竖排的一列不算。 */
    private fun isLevel(box: Box): Boolean =
        TextAxis.fit(box, 0f).let { it == TextAxis.Fit.ALONG || it == TextAxis.Fit.SQUARE } && abs(skew(box)) <= LEVEL_SLACK

    /** 框的上沿偏离横平几度，折到 (−45, 45]：方框哪条边算上沿说不准。 */
    private fun skew(b: Box): Double {
        var a = Math.toDegrees(atan2((b.tr.y - b.tl.y).toDouble(), (b.tr.x - b.tl.x).toDouble())) % 90
        if (a > 45) a -= 90
        if (a <= -45) a += 90
        return a
    }

    /**
     * 斜着压在字上的水印（证件复印件上的「仅供办理银行开户使用」）。DB 把水印和它压住的字连成一块，框是斜的，
     * 压住的字跟着水印读成了字母：合成卡上「出生 1993 年 7 月 18 日」只剩「生」「1993 年」「18 日」，名字成了「林知夏OUNTOF」。
     * 识别器隔着浅色的水印照样读得出横着的字，所以斜框穿过的一排横平的字，横着切出来重读一遍：
     * - 斜框盖住了这一排两段之间的空当（「1993 年」和「18 日」之间的「7 月」），整排重读；
     * - 斜框盖住了这一排的两头（离两头不过 [RowGrouper.REACH] 个字高），往外接着读，接到墨色深过水印的字为止（[inkedTo]）：
     *   「姓 名」右边的「林知夏」接上，再往右只有水印的字母，不接；
     * 同一排上被水印隔开的两截（「住 址」和「文三路」），接着读接到了一起，并成一截读。
     * 重读的留住了原先读出的字（[keeps]），才换掉那几段。没有斜框的页一个框都不多读。
     *
     * @param level 横平的行，[tilted] 斜着的框，都在 [view] 上。
     */
    private fun underTilted(view: Pixels, level: List<OcrLine>, tilted: List<Box>): List<OcrLine> {
        if (tilted.isEmpty()) return level
        val strips = ArrayList<Strip>()
        RowGrouper.rows(level).map { stripOf(view, it, tilted) }.sortedBy { it.left }.forEach { s ->
            val i = strips.indexOfFirst { o -> (o.reread || s.reread) && o.touches(s) }
            if (i < 0) strips += s else strips[i] = strips[i] + s
        }
        return strips.flatMap { s ->
            if (!s.reread) return@flatMap s.segments
            val reread = read(view, Box(Pt(s.left, s.top), Pt(s.right, s.top), Pt(s.right, s.bottom), Pt(s.left, s.bottom)))?.line
            if (reread != null && keeps(reread.text, s.segments.sortedBy { it.box.minX }.joinToString("") { it.text })) listOf(reread)
            else s.segments
        }
    }

    /** 一排字（[segments]）要横着重读的那一截：[left]..[right] × [top]..[bottom]。[reread] 为 false 的不用重读。 */
    private class Strip(
        val segments: List<OcrLine>, val left: Float, val right: Float, val top: Float, val bottom: Float, val h: Float,
        val reread: Boolean,
    ) {
        /** 同一排（竖直方向重叠过半），左右挨着（空当不到一个字高）。 */
        fun touches(o: Strip): Boolean =
            min(bottom, o.bottom) - max(top, o.top) >= 0.5f * min(bottom - top, o.bottom - o.top) &&
                max(left, o.left) - min(right, o.right) <= max(h, o.h)

        operator fun plus(o: Strip) = Strip(
            segments + o.segments, min(left, o.left), max(right, o.right), min(top, o.top), max(bottom, o.bottom), max(h, o.h), true,
        )
    }

    /** 斜框穿过的一排字往两头接、看中间的空当（见 [underTilted]）。 */
    private fun stripOf(view: Pixels, row: List<OcrLine>, tilted: List<Box>): Strip {
        val segments = row.sortedBy { it.box.minX }
        val top = segments.minOf { it.box.minY }
        val bottom = segments.maxOf { it.box.maxY }
        val h = segments.maxOf { it.box.height }
        val first = segments.first().box.minX
        val last = segments.maxOf { it.box.maxX }
        val unchanged = Strip(segments, first, last, top, bottom, h, reread = false)
        val spans = tilted.mapNotNull { spanIn(it, top, bottom) }
            .filter { (l, r) -> r >= first - RowGrouper.REACH * h && l <= last + RowGrouper.REACH * h }
        if (spans.isEmpty()) return unchanged
        val ink = Ink.of(view, segments.map { it.box }) ?: return unchanged
        val left = spans.filter { it.first < first }.minOfOrNull { it.first }
            ?.let { inkedTo(view, ink, top, bottom, h, first, it) } ?: first
        val right = spans.filter { it.second > last }.maxOfOrNull { it.second }
            ?.let { inkedTo(view, ink, top, bottom, h, last, it) } ?: last
        val gapCovered = segments.zipWithNext().any { (a, b) ->
            b.box.minX - a.box.maxX >= 0.5f * h && spans.any { (l, r) -> l < b.box.minX && r > a.box.maxX } &&
                inked(view, ink, top, bottom, a.box.maxX, b.box.minX)
        }
        return Strip(segments, left, right, top, bottom, h, reread = left < first || right > last || gapCovered)
    }

    /**
     * 一排字的墨色：这几段框里最深的那一小撮像素（[Ink.text]）和大多数像素（[Ink.paper]）的亮度。
     * 浅色的水印亮度在两者之间偏纸那一边。墨和纸差不到 [MIN_CONTRAST] 的看不出，为 null。
     */
    private class Ink(val text: Float, val paper: Float) {

        /** 比这个暗的是字的墨：从字往纸走四成。灰色的中文水印比浅色的英文水印深，正中间还挡不住它。 */
        val threshold: Float get() = text + 0.4f * (paper - text)

        companion object {
            fun of(view: Pixels, boxes: List<Box>): Ink? {
                val values = ArrayList<Float>()
                boxes.forEach { b ->
                    var y = b.minY
                    while (y < b.maxY) {
                        var x = b.minX
                        while (x < b.maxX) { values += view.sample(x, y).luma; x += 2f }
                        y += 2f
                    }
                }
                if (values.size < 16) return null
                values.sort()
                val ink = Ink(values[values.size / 50], values[values.size / 2])
                return ink.takeIf { it.paper - it.text >= MIN_CONTRAST }
            }

            /** 字的墨和纸至少差这么多亮度（0..255），才分得出水印。 */
            const val MIN_CONTRAST = 60f
        }
    }

    /**
     * 从 [from]（一排字的一头）往 [to] 一列列看，接到最后一列有字的墨的地方（再加一点边）：墨色见 [Ink]，
     * 中间空着不到一个字高的照接（字与字、字段名与值之间的空当）。
     */
    private fun inkedTo(view: Pixels, ink: Ink, top: Float, bottom: Float, h: Float, from: Float, to: Float): Float {
        val step = if (to > from) 1f else -1f
        var reached = from
        var x = from
        while ((to - x) * step > 0 && abs(x - reached) <= h) {
            if (inkedColumn(view, ink, top, bottom, x)) reached = x
            x += step
        }
        return if (reached == from) from else reached + step * 0.2f * h
    }

    /** [x0]..[x1] 之间有一列有字的墨。 */
    private fun inked(view: Pixels, ink: Ink, top: Float, bottom: Float, x0: Float, x1: Float): Boolean {
        var x = x0
        while (x <= x1) {
            if (inkedColumn(view, ink, top, bottom, x)) return true
            x += 1f
        }
        return false
    }

    /** 第 [x] 列在这一排中间那一截高里有比 [Ink.threshold] 暗的像素。上下两成不看：上下行的笔画会伸进来。 */
    private fun inkedColumn(view: Pixels, ink: Ink, top: Float, bottom: Float, x: Float): Boolean {
        var y = top + 0.2f * (bottom - top)
        while (y <= bottom - 0.2f * (bottom - top)) {
            if (view.sample(x, y).luma < ink.threshold) return true
            y += 1f
        }
        return false
    }

    /** [box] 落在 [top]..[bottom] 这条横带里的那一截，左右的 x；不相交时为 null。 */
    private fun spanIn(box: Box, top: Float, bottom: Float): Pair<Float, Float>? {
        val xs = ArrayList<Float>()
        val corners = box.corners()
        corners.forEachIndexed { i, p ->
            if (p.y in top..bottom) xs += p.x
            val q = corners[(i + 1) % corners.size]
            listOf(top, bottom).forEach { y ->
                if ((p.y - y) * (q.y - y) < 0) xs += p.x + (q.x - p.x) * (y - p.y) / (q.y - p.y)
            }
        }
        return if (xs.isEmpty()) null else xs.min() to xs.max()
    }

    /**
     * 重读出来的 [text] 留住了原先读出的 [before]：去掉空格后不比它短，它的字按次序至少有 [KEEP] 在里面。
     * 不要求一个不差：压着水印的单字原先常读错，重读时读对了（「出」读成的「山」）。
     */
    private fun keeps(text: String, before: String): Boolean {
        val a = before.filterNot { it == ' ' }
        val b = text.filterNot { it == ' ' }
        if (b.length < a.length) return false
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) for (j in b.indices) {
            d[i + 1][j + 1] = if (a[i] == b[j]) d[i][j] + 1 else max(d[i][j + 1], d[i + 1][j])
        }
        return d[a.length][b.length] >= KEEP * a.length
    }

    /** 按框长加权的平均置信度，丢掉的行（不到 [DROP_SCORE]）也算：要比的就是读得有多磕绊。 */
    private fun score(reads: List<Read?>): Float {
        var sum = 0.0
        var weight = 0.0
        reads.forEach { r ->
            if (r == null) return@forEach
            sum += r.confidence * r.length
            weight += r.length
        }
        return if (weight > 0) (sum / weight).toFloat() else 0f
    }

    private val readingOrder = compareBy<Box> { (it.minY / ROW_BUCKET).toInt() }.thenBy { it.minX }

    private fun inReadingOrder(boxes: List<Box>) = boxes.sortedWith(readingOrder)

    private fun Box.map(f: (Pt) -> Pt) = Box(f(tl), f(tr), f(br), f(bl))

    // ---------- 检测 ----------

    private fun detect(image: Pixels): List<Box> {
        // 截图长边 2000+，全分辨率检测在手机上太慢；1536 仍让常见字号的字高保持在 20px 以上
        val s = min(1f, DET_LIMIT / max(image.width, image.height))
        val nw = max(32, (image.width * s / 32).roundToInt() * 32)
        val nh = max(32, (image.height * s / 32).roundToInt() * 32)
        val input = FloatArray(3 * nh * nw)
        val sx = image.width.toFloat() / nw
        val sy = image.height.toFloat() / nh
        val plane = nw * nh
        for (y in 0 until nh) for (x in 0 until nw) {
            val c = image.sample((x + 0.5f) * sx - 0.5f, (y + 0.5f) * sy - 0.5f)
            val i = y * nw + x
            // 模型按 OpenCV 的 BGR 训练，均值方差按通道序号套
            input[i] = (c.b / 255f - 0.485f) / 0.229f
            input[plane + i] = (c.g / 255f - 0.456f) / 0.224f
            input[2 * plane + i] = (c.r / 255f - 0.406f) / 0.225f
        }
        val prob = run(det, input, longArrayOf(1, 3, nh.toLong(), nw.toLong())) { buf, _ ->
            FloatArray(plane).also { buf.get(it) }
        }
        return DbPostProcess.boxes(prob, nw, nh).map { b ->
            Box(b.tl.scale(sx, sy), b.tr.scale(sx, sy), b.br.scale(sx, sy), b.bl.scale(sx, sy))
        }
    }

    // ---------- 识别 ----------

    /**
     * 一个框读出来的结果。[line] 是按 [DROP_SCORE] 筛过的；[confidence] 是原样的（一个字也没读出来的按 0 算），
     * [length] 是框沿阅读方向的长度，试方向时拿这两个加权比。
     */
    private class Read(val line: OcrLine?, val confidence: Float, val length: Float)

    private fun read(image: Pixels, detected: Box): Read? {
        // 竖排的框转 90° 再识别（PaddleOCR 的 get_rotate_crop_image 同一个口径）
        val box = if (detected.height >= detected.width * 1.5f) {
            Box(detected.tr, detected.br, detected.bl, detected.tl)
        } else detected
        if (box.width < 1f || box.height < 1f) return null

        val w = ceil(REC_HEIGHT * box.width / box.height).toInt().coerceIn(REC_MIN_WIDTH, REC_MAX_WIDTH)
        val input = FloatArray(3 * REC_HEIGHT * w)
        val gray = FloatArray(REC_HEIGHT * w)
        val plane = REC_HEIGHT * w
        for (y in 0 until REC_HEIGHT) for (x in 0 until w) {
            val p = box.at((x + 0.5f) / w, (y + 0.5f) / REC_HEIGHT)
            val c = image.sample(p.x, p.y)
            val i = y * w + x
            input[i] = (c.b / 255f - 0.5f) / 0.5f
            input[plane + i] = (c.g / 255f - 0.5f) / 0.5f
            input[2 * plane + i] = (c.r / 255f - 0.5f) / 0.5f
            gray[i] = c.luma / 255f
        }

        val (result, steps) = run(rec, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), w.toLong())) { buf, shape ->
            CtcDecoder.decode(buf, shape[1].toInt(), shape[2].toInt(), dict).let { it to shape[1].toInt() }
        }
        val empty = result.chars.none { it.isNotBlank() }
        val line = if (empty || result.confidence < DROP_SCORE) null else line(box, w, result, steps, gray)
        return Read(line, if (empty) 0f else result.confidence, box.width)
    }

    private fun line(box: Box, w: Int, result: CtcDecoder.Decoded, steps: Int, gray: FloatArray): OcrLine {
        val step = w.toFloat() / steps
        val centers = result.steps.map { (it.first + it.last + 1) / 2f * step }
        val gaps = WordGaps.find(gray, w, REC_HEIGHT)
        val glyphs = result.chars.indices.filter { result.chars[it] != " " }

        val text = StringBuilder()
        val boxes = ArrayList<Box?>()
        glyphs.forEachIndexed { n, i ->
            if (n > 0) {
                val prev = glyphs[n - 1]
                val modelSpace = (prev + 1 until i).any { result.chars[it] == " " }
                if (modelSpace || WordGaps.isBreak(result.chars[prev], result.chars[i], centers[prev], centers[i], gaps)) {
                    text.append(' ')
                    boxes += null
                }
            }
            // 字的左右边界取与邻字中心的中点；首尾两个字延伸到框边，遮罩不会漏掉笔画边缘
            val left = if (n == 0) 0f else (centers[glyphs[n - 1]] + centers[i]) / 2
            val right = if (n == glyphs.lastIndex) w.toFloat() else (centers[i] + centers[glyphs[n + 1]]) / 2
            val charBox = box.slice(left / w, right / w)
            val ch = result.chars[i]
            text.append(ch)
            repeat(ch.length) { boxes += charBox }          // 字典里 BMP 以外的字占两个 UTF-16 单元
        }
        return OcrLine(text.toString(), result.confidence, box, boxes)
    }

    private fun <T> run(session: OrtSession, input: FloatArray, shape: LongArray, read: (FloatBuffer, LongArray) -> T): T =
        OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
            session.run(mapOf(session.inputNames.first() to tensor)).use { out ->
                val result = out.get(0) as OnnxTensor
                read(result.floatBuffer, result.info.shape)
            }
        }

    override fun close() {
        det.close()
        rec.close()
        options.close()
    }

    private fun Pt.scale(sx: Float, sy: Float) = Pt(x * sx, y * sy)

    companion object {
        const val DET_LIMIT = 1536f
        const val REC_HEIGHT = 48
        const val REC_MIN_WIDTH = 16
        /** 超长行按比例压扁。上限决定一行输出的大小：2000 宽（250 个时间步）× 18710 类 ≈ 19 MB。 */
        const val REC_MAX_WIDTH = 2000
        /** PaddleOCR 的 drop_score。 */
        const val DROP_SCORE = 0.5f
        /** 排序时把 y 相差不到这么多像素的框当作同一行，行内再按 x。 */
        private const val ROW_BUCKET = 10f

        /**
         * 横平的页读到这个平均置信度就不再怀疑它是倒着的。实测正着的页在 0.89–1.0，倒过来的身份证只有 0.43。
         * 也是换方向的底线：真倒着、真竖着的页，转对了的那个方向在 0.97 以上；认不得的文字两个方向都在 0.65 以下。
         */
        private const val UPRIGHT_SCORE = 0.8f

        /** 换到不是默认的那个方向，置信度得高出这么多：两边差不多时不折腾。 */
        private const val MARGIN = 0.1f

        /**
         * 页上有斜框时，顺着走向的框斜过这么多度就不是一行字（[levelRows]）。横平的字框差一两度，
         * 字和水印连成的一块斜九度上下。
         */
        private const val LEVEL_SLACK = 6.0

        /** 重读一排字时，原先读出的字至少要留住这么多（[keeps]）。 */
        private const val KEEP = 0.8f

        /** 字典文件：一行一个字，UTF-8。 */
        fun parseDict(text: String): List<String> = text.split('\n').map { it.trimEnd('\r') }.let {
            if (it.lastOrNull()?.isEmpty() == true) it.dropLast(1) else it
        }
    }
}

/**
 * 把同一行上被检测拆开的几段拼回一条。
 *
 * DB 检测按间隙切框：「取件码」「1-58908」「复制」可能是三个框。规则是逐行跑的，
 * 标签和值分在两行，标签锚定就接不上——ML Kit 中文识别器同样会把一整行合成一条，
 * 规则层一直按这个前提写。
 *
 * 两段算同一行：竖直方向重叠超过较矮那段高度的一半，且水平间距不超过行高的 3 倍
 * （再远多半是左右两栏，拼起来会让「名字在电话前面」这类判断串栏）。
 *
 * 另有三条是拍的证件照上补的（护照样张上十来行被串成了一行）：
 * - 字高差一倍以上的不拼：斜着压在版面上的水印「SPECIMEN」，检测框高一百多像素，竖直范围里的每一行都和它重叠过半；
 * - 走向差 [MAX_TURN] 度以上的不拼：斜着平铺的一行行小字水印和证件上的字一样高，从一排字中间穿过去，
 *   外接框照样重叠过半（合成卡上「民 族」「18 日」被串进了水印那一行）。一两个字的方框看不出走向，不管；
 * - 左右并排才拼，上下叠着的不拼：证件上字段名下面紧贴着值，框常常上下咬住一截，重叠能过半。
 */
object RowGrouper {

    fun group(lines: List<OcrLine>): List<OcrLine> =
        rows(lines).map(::join).sortedWith(compareBy<OcrLine> { it.box.minY }.thenBy { it.box.minX })

    /** 分好的排，每排里是拼之前的那几段。 */
    fun rows(lines: List<OcrLine>): List<List<OcrLine>> {
        val rows = ArrayList<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.box.minX }) {
            val row = rows.firstOrNull { r -> r.any { sameRow(it, line) } && near(r.maxOf { it.box.maxX }, line) }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val lower = min(a.box.height, b.box.height)
        if (max(a.box.height, b.box.height) > MAX_HEIGHT_RATIO * lower) return false
        if (turned(a.box, b.box)) return false
        val overlap = min(a.box.maxY, b.box.maxY) - max(a.box.minY, b.box.minY)
        if (overlap < 0.5f * min(a.box.maxY - a.box.minY, b.box.maxY - b.box.minY)) return false
        val sideBySide = min(a.box.maxX, b.box.maxX) - max(a.box.minX, b.box.minX)
        return sideBySide <= 0.5f * lower
    }

    /** 两个都看得出走向（长边过短边 [ELONGATED] 倍）的框，走向差 [MAX_TURN] 度以上。 */
    private fun turned(a: Box, b: Box): Boolean {
        if (a.width < ELONGATED * a.height || b.width < ELONGATED * b.height) return false
        val d = abs(direction(a) - direction(b)) % 360
        return min(d, 360 - d) > MAX_TURN
    }

    /** 框的阅读方向（tl→tr），度。 */
    private fun direction(b: Box): Double = Math.toDegrees(atan2((b.tr.y - b.tl.y).toDouble(), (b.tr.x - b.tl.x).toDouble()))

    /** 同一排的两段，字高最多差这么多倍。 */
    private const val MAX_HEIGHT_RATIO = 2f

    /** 同一排的两段，走向最多差这么多度：拍照的透视让同一排差出几度，水印一般斜二三十度以上。 */
    private const val MAX_TURN = 15.0

    /** 长边过短边这么多倍才看得出走向，同 TextAxis。 */
    private const val ELONGATED = 1.3f

    /** 同一排的两段最远隔几个字高。 */
    const val REACH = 3f

    private fun near(rowRight: Float, line: OcrLine): Boolean {
        val h = line.box.maxY - line.box.minY
        return line.box.minX - rowRight <= REACH * h
    }

    private fun join(row: List<OcrLine>): OcrLine {
        if (row.size == 1) return row.single()
        val sorted = row.sortedBy { it.box.minX }
        val text = sorted.joinToString(" ") { it.text }
        val boxes = sorted.flatMapIndexed { i, l -> if (i == 0) l.charBoxes else listOf(null) + l.charBoxes }
        val conf = sorted.sumOf { it.confidence.toDouble() * it.text.length } / sorted.sumOf { it.text.length }
        val box = Box(
            Pt(sorted.minOf { it.box.minX }, sorted.minOf { it.box.minY }),
            Pt(sorted.maxOf { it.box.maxX }, sorted.minOf { it.box.minY }),
            Pt(sorted.maxOf { it.box.maxX }, sorted.maxOf { it.box.maxY }),
            Pt(sorted.minOf { it.box.minX }, sorted.maxOf { it.box.maxY }),
        )
        return OcrLine(text, conf.toFloat(), box, boxes)
    }
}
