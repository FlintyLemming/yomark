package moe.flinty.yomark

import moe.flinty.yomark.engine.ppocr.PpOcrEngine
import java.io.File
import javax.imageio.ImageIO

/**
 * 换模型对比 OCR：同一套 PpOcrEngine（app 原文件），检测、识别、字典三个文件由参数给。
 *
 * 用法：ocr-bench <输出目录> <det.onnx> <rec.onnx> <dict.txt> <device|full> <图1.png> [...]
 *   device = 按 SourceImageLoader 降采样（2 的幂、长边 ≤ 2048），与手机上同口径；
 *   full   = 原图直接识别，看降采样本身损失了多少。
 *
 * 写出 <输出目录>/<名>.json，格式与 OcrDump 相同；每张图另记识别耗时（毫秒）。
 */
fun main(args: Array<String>) {
    val out = File(args[0]).apply { mkdirs() }
    val engine = PpOcrEngine(
        detModel = File(args[1]).readBytes(),
        recModel = File(args[2]).readBytes(),
        dict = PpOcrEngine.parseDict(File(args[3]).readText()),
    )
    val device = args[4] == "device"
    args.drop(5).forEach { path ->
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
        engine.recognize(px, w, h)                       // 预热一次，计时只算第二次
        val t0 = System.nanoTime()
        val lines = engine.recognize(px, w, h)
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
