package com.youma.app.eval

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RectF
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.SensitiveKind
import org.json.JSONObject

/** 一处人工标注：这块像素区域里有一个此类型的敏感实体。 */
data class Annotation(val kind: SensitiveKind, val rect: RectF)

data class Sample(val name: String, val image: SourceImage, val truth: List<Annotation>)

/**
 * 从 androidTest/assets/samples/ 加载样本集。
 *
 * 目录结构：
 *   samples/chat-001.png
 *   samples/chat-001.json
 *
 * JSON 格式（坐标是图像像素，原点左上）：
 *   { "annotations": [ { "kind": "PAYMENT_CARD", "l": 120, "t": 340, "r": 520, "b": 380 } ] }
 *
 * 详见 docs/eval-sample-set.md。
 */
object SampleSet {

    fun loadFromAssets(context: Context, dir: String = "samples"): List<Sample> {
        val names = runCatching { context.assets.list(dir)?.toList() ?: emptyList() }.getOrDefault(emptyList())
        val images = names.filter { it.endsWith(".png") || it.endsWith(".jpg") }
        return images.mapNotNull { imageName ->
            val base = imageName.substringBeforeLast('.')
            val jsonName = "$base.json"
            if (jsonName !in names) return@mapNotNull null

            val bitmap = context.assets.open("$dir/$imageName").use { BitmapFactory.decodeStream(it) }
                ?: return@mapNotNull null
            val json = context.assets.open("$dir/$jsonName").use { it.readBytes().decodeToString() }
            Sample(
                name = base,
                image = SourceImage(bitmap, 1f, bitmap.width, bitmap.height, "image/png"),
                truth = parse(json),
            )
        }
    }

    private fun parse(json: String): List<Annotation> {
        val arr = JSONObject(json).getJSONArray("annotations")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Annotation(
                kind = SensitiveKind.valueOf(o.getString("kind")),
                rect = RectF(
                    o.getDouble("l").toFloat(), o.getDouble("t").toFloat(),
                    o.getDouble("r").toFloat(), o.getDouble("b").toFloat(),
                ),
            )
        }
    }
}
