package moe.flinty.yomark

import moe.flinty.yomark.engine.ppocr.PpOcrEngine
import java.io.File
import javax.imageio.ImageIO

/**
 * 换模型对比 OCR：同一套 PpOcrEngine（app 原文件），检测、识别、字典三个文件由参数给。
 *
 * 用法：ocr-bench <输出目录> <det.onnx> <rec.onnx> <dict.txt> <device|full> <图1.png | 图目录> [...]
 *   device = 按 SourceImageLoader 降采样（2 的幂、长边 ≤ 2048），与手机上同口径；
 *   full   = 原图直接识别，看降采样本身损失了多少。
 *
 * 写出 <输出目录>/<名>.json，格式与 OcrDump 相同；每张图另记识别耗时（毫秒）。
 * 环境变量 OCR_BENCH_WARMUP=0 时不预热（只比准确率、图又多的时候省一半时间，耗时就不准了）。
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    // 设了 OCR_SPACES / OCR_DB 就用评测台的副本 PpOcrEngineX（见那个文件），否则用 app 的原类
    val det = File(args[1]).readBytes()
    val rec = File(args[2]).readBytes()
    val dict = PpOcrEngine.parseDict(File(args[3]).readText())
    val variant = System.getenv("OCR_SPACES") != null || System.getenv("OCR_DB") != null
    val recognize: (IntArray, Int, Int) -> List<moe.flinty.yomark.engine.ppocr.OcrLine> =
        if (variant) moe.flinty.yomark.bench.PpOcrEngineX(det, rec, dict)::recognize else PpOcrEngine(det, rec, dict)::recognize
    val device = args[4] == "device"
    // 图可以逐个给，也可以给一个目录（取其中的 .png）：几十张图的路径拼成一长串参数时 gradle run 起来的 JVM 会直接崩
    val images = args.drop(5).flatMap { p ->
        val f = File(p)
        if (f.isDirectory) f.listFiles { x -> x.name.endsWith(".png") }!!.sortedBy { it.name }.map { it.path } else listOf(p)
    }
    images.forEach { path ->
        val name = File(path).nameWithoutExtension
        val src = ImageIO.read(File(path))
        var s = 1
        if (device) {
            val longEdge = maxOf(src.width, src.height)
            while (longEdge / s > 2048) s *= 2
        }
        val w = src.width / s
        val h = src.height / s
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) px[y * w + x] = src.getRGB(x * s + s / 2, y * s + s / 2)
        if (System.getenv("OCR_BENCH_WARMUP") != "0") recognize(px, w, h)   // 预热一次，计时只算第二次
        val t0 = System.nanoTime()
        val lines = recognize(px, w, h)
        val ms = (System.nanoTime() - t0) / 1_000_000
        File(out, "$name.json").writeText(
            lines.joinToString(",\n", "{\"analysis\":[$w,$h],\"ms\":$ms,\"lines\":[\n", "\n]}") { l ->
                val t = l.text.replace("\\", "\\\\").replace("\"", "\\\"")
                "{\"text\":\"$t\",\"conf\":${l.confidence},\"box\":[${l.box.minX},${l.box.minY},${l.box.maxX},${l.box.maxY}]}"
            }
        )
        println("$name: ${w}x$h, ${lines.size} lines, $ms ms")
    }
}
