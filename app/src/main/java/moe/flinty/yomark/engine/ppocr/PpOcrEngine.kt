package moe.flinty.yomark.engine.ppocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.abs
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
     */
    fun recognize(pixels: IntArray, width: Int, height: Int): List<OcrLine> {
        val image = Argb(pixels, width, height)
        val boxes = inReadingOrder(detect(image))
        val axis = TextAxis.of(boxes)
        if (axis == null || axis == 0f) {
            val reads = boxes.map { read(image, it) }
            val lines = RowGrouper.group(reads.mapNotNull { it?.line })
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
        val turned = ArrayList<Pair<Box, OcrLine>>()
        val level = ArrayList<OcrLine>()
        boxes.forEachIndexed { i, box ->
            val onCanvas = upright(view, box)
            val canvasRead = { if (i in chosen.reads) chosen.reads[i] else read(view, onCanvas) }
            val sourceRead = { if (inSource != null) inSource[i] else read(image, box) }
            when (fits[i]) {
                TextAxis.Fit.ALONG -> canvasRead()?.line?.let { turned += onCanvas to it }
                TextAxis.Fit.CROSS, TextAxis.Fit.OTHER -> sourceRead()?.line?.let { level += it }
                TextAxis.Fit.SQUARE -> if (!crossed) canvasRead()?.line?.let { turned += onCanvas to it } else {
                    val c = canvasRead()
                    val s = sourceRead()
                    if (s != null && s.confidence > (c?.confidence ?: -1f)) s.line?.let { level += it }
                    else c?.line?.let { turned += onCanvas to it }
                }
            }
        }
        val ordered = turned.sortedWith(compareBy(readingOrder) { it.first }).map { it.second }
        return RowGrouper.group(ordered).map { line ->
            line.copy(box = line.box.map(view::toSource), charBoxes = line.charBoxes.map { it?.map(view::toSource) })
        } + RowGrouper.group(level)
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
            gray[i] = (0.299f * c.r + 0.587f * c.g + 0.114f * c.b) / 255f
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
 * 另有两条是拍的证件照上补的（护照样张上十来行被串成了一行）：
 * - 字高差一倍以上的不拼：斜着压在版面上的水印「SPECIMEN」，检测框高一百多像素，竖直范围里的每一行都和它重叠过半；
 * - 左右并排才拼，上下叠着的不拼：证件上字段名下面紧贴着值，框常常上下咬住一截，重叠能过半。
 */
object RowGrouper {

    fun group(lines: List<OcrLine>): List<OcrLine> {
        val rows = ArrayList<MutableList<OcrLine>>()
        for (line in lines.sortedBy { it.box.minX }) {
            val row = rows.firstOrNull { r -> r.any { sameRow(it, line) } && near(r.maxOf { it.box.maxX }, line) }
            if (row != null) row += line else rows += mutableListOf(line)
        }
        return rows.map(::join).sortedWith(compareBy<OcrLine> { it.box.minY }.thenBy { it.box.minX })
    }

    private fun sameRow(a: OcrLine, b: OcrLine): Boolean {
        val lower = min(a.box.height, b.box.height)
        if (max(a.box.height, b.box.height) > MAX_HEIGHT_RATIO * lower) return false
        val overlap = min(a.box.maxY, b.box.maxY) - max(a.box.minY, b.box.minY)
        if (overlap < 0.5f * min(a.box.maxY - a.box.minY, b.box.maxY - b.box.minY)) return false
        val sideBySide = min(a.box.maxX, b.box.maxX) - max(a.box.minX, b.box.minX)
        return sideBySide <= 0.5f * lower
    }

    /** 同一排的两段，字高最多差这么多倍。 */
    private const val MAX_HEIGHT_RATIO = 2f

    private fun near(rowRight: Float, line: OcrLine): Boolean {
        val h = line.box.maxY - line.box.minY
        return line.box.minX - rowRight <= 3f * h
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
