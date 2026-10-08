package moe.flinty.yomark

import moe.flinty.yomark.engine.genai.SemanticPrompt
import moe.flinty.yomark.engine.ppocr.PpOcrEngine
import java.io.File
import javax.imageio.ImageIO

/**
 * 用法：ocr-dump <输出目录> <图1.png> [<图2.png> ...]
 *
 * 每张图按设备口径先降采样（SourceImageLoader：2 的幂、长边 ≤ 2048，点采样），再跑随包的 PP-OCRv5，
 * 写出 <输出目录>/ocr/<名>.json 和同尺寸的 <输出目录>/analysis/<名>.png——看图的模型吃的也是这张。
 * 顺带把 app 里给 Gemini Nano 的提示词原样写到 <输出目录>/instructions.txt，脚本逐字复用，不手抄。
 */
fun main(args: Array<String>) {
    val out = File(args[0])
    File(out, "ocr").mkdirs(); File(out, "analysis").mkdirs()
    File(out, "instructions.txt").writeText(SemanticPrompt.INSTRUCTIONS)
    val assets = File("../../../app/src/main/assets/ppocr")
    val engine = PpOcrEngine(
        detModel = File(assets, "det.onnx").readBytes(),
        recModel = File(assets, "rec.onnx").readBytes(),
        dict = PpOcrEngine.parseDict(File(assets, "dict.txt").readText()),
    )
    args.drop(1).forEach { path ->
        val name = File(path).nameWithoutExtension
        val src = ImageIO.read(File(path))
        var s = 1
        val longEdge = maxOf(src.width, src.height)
        while (longEdge / (s * 2) >= 2048) s *= 2
        while (longEdge / s > 2048) s *= 2
        val w = src.width / s
        val h = src.height / s
        val small = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) for (x in 0 until w) small.setRGB(x, y, src.getRGB(x * s + s / 2, y * s + s / 2))
        ImageIO.write(small, "png", File(out, "analysis/$name.png"))
        val px = small.getRGB(0, 0, w, h, null, 0, w)
        val lines = engine.recognize(px, w, h)
        File(out, "ocr/$name.json").writeText(lines.joinToString(",\n", "{\"analysis\":[$w,$h],\"lines\":[\n", "\n]}") { l ->
            val t = l.text.replace("\\", "\\\\").replace("\"", "\\\"")
            "{\"text\":\"$t\",\"conf\":${l.confidence},\"box\":[${l.box.minX},${l.box.minY},${l.box.maxX},${l.box.maxY}]}"
        })
        println("$name: ${w}x$h, ${lines.size} lines")
    }
}
