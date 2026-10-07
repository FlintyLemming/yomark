package com.yomark.app.engine.ppocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCRv5 mobile（检测 + 识别）跑在 ONNX Runtime 上（spec §14 的预留位）。
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

    fun recognize(pixels: IntArray, width: Int, height: Int): List<OcrLine> {
        val image = Rgb(pixels, width, height)
        val lines = detect(image)
            .sortedWith(compareBy<Box> { (it.minY / ROW_BUCKET).toInt() }.thenBy { it.minX })
            .mapNotNull { recognizeBox(image, it) }
        return RowGrouper.group(lines)
    }

    // ---------- 检测 ----------

    private fun detect(image: Rgb): List<Box> {
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

    private fun recognizeBox(image: Rgb, detected: Box): OcrLine? {
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

        val decoded = run(rec, input, longArrayOf(1, 3, REC_HEIGHT.toLong(), w.toLong())) { buf, shape ->
            CtcDecoder.decode(buf, shape[1].toInt(), shape[2].toInt(), dict).let { it to shape[1].toInt() }
        }
        val (result, steps) = decoded
        if (result.chars.none { it.isNotBlank() } || result.confidence < DROP_SCORE) return null

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

    /** ARGB 像素上的双线性采样，越界按边缘像素延伸（等价于 OpenCV 的 BORDER_REPLICATE）。 */
    private class Rgb(val pixels: IntArray, val width: Int, val height: Int) {
        class Color(val r: Float, val g: Float, val b: Float)

        fun sample(x: Float, y: Float): Color {
            val fx = x.coerceIn(0f, width - 1f)
            val fy = y.coerceIn(0f, height - 1f)
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val ax = fx - x0
            val ay = fy - y0
            val p00 = pixels[y0 * width + x0]
            val p10 = pixels[y0 * width + x1]
            val p01 = pixels[y1 * width + x0]
            val p11 = pixels[y1 * width + x1]
            fun ch(shift: Int): Float {
                val top = ((p00 shr shift) and 0xFF) * (1 - ax) + ((p10 shr shift) and 0xFF) * ax
                val bottom = ((p01 shr shift) and 0xFF) * (1 - ax) + ((p11 shr shift) and 0xFF) * ax
                return top * (1 - ay) + bottom * ay
            }
            return Color(ch(16), ch(8), ch(0))
        }
    }

    private fun Pt.scale(sx: Float, sy: Float) = Pt(x * sx, y * sy)

    companion object {
        const val DET_LIMIT = 1536f
        const val REC_HEIGHT = 48
        const val REC_MIN_WIDTH = 16
        /** 超长行按比例压扁。上限决定一行输出的大小：2000 宽 × 18385 类 ≈ 18 MB。 */
        const val REC_MAX_WIDTH = 2000
        /** PaddleOCR 的 drop_score。 */
        const val DROP_SCORE = 0.5f
        /** 排序时把 y 相差不到这么多像素的框当作同一行，行内再按 x。 */
        private const val ROW_BUCKET = 10f

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
        val overlap = min(a.box.maxY, b.box.maxY) - max(a.box.minY, b.box.minY)
        return overlap >= 0.5f * min(a.box.maxY - a.box.minY, b.box.maxY - b.box.minY)
    }

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
