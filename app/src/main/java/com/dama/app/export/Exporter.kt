package com.dama.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import com.dama.app.core.image.SourceImageLoader
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskState
import com.dama.app.render.RendererRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExportRequest(
    val file: File,
    val mimeType: String,
    val plan: MaskPlan,
    /** SourceImage.scale：分析图边长 / 原图边长。遮罩按 1/scale 反算。 */
    val analysisScale: Float,
    val applyWatermark: Boolean,
)

sealed interface ExportOutcome {
    data class Success(val uri: Uri, val width: Int, val height: Int, val downscaled: Boolean) : ExportOutcome
    data class Failure(val cause: Throwable) : ExportOutcome
}

/**
 * 导出管线（spec §9.1）：
 *   原图全分辨率解码 + EXIF 转正 → 逐项渲染 MASKED → 品牌水印 → 重编码 → 写出
 *
 * 不携带任何 EXIF 由「重编码」天然保证——Bitmap.compress 不写 EXIF。
 * 但这条由 ExporterTest 的断言守，不靠推断。
 */
class Exporter(
    private val registry: RendererRegistry,
    private val watermark: WatermarkDrawer,
    private val sink: ImageSink,
) {

    suspend fun export(request: ExportRequest): ExportOutcome = withContext(Dispatchers.Default) {
        runCatching {
            val decoded = SourceImageLoader.loadForExport(request.file)
            val bitmap = decoded.bitmap
            val canvas = Canvas(bitmap)

            // 分析坐标 → 导出坐标。导出图可能因 32MP 上限又降过一次，两个系数合成一个。
            val factor = 1f / (request.analysisScale * decoded.downsampleFactor)

            val maskedBounds = ArrayList<RectF>()
            request.plan.items
                .filter { it.state == MaskState.MASKED }
                .forEach { item ->
                    val quad = item.quad.scaled(factor)
                    registry[request.plan.style].render(canvas, bitmap, quad, request.plan.options)
                    maskedBounds += quad.bounds()
                }

            if (request.applyWatermark) watermark.draw(canvas, bitmap, maskedBounds)

            val png = request.mimeType.equals("image/png", ignoreCase = true)
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val mime = if (png) "image/png" else "image/jpeg"
            val name = "DAMA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                if (png) ".png" else ".jpg"

            val uri = sink.write(bitmap, format, if (png) 100 else JPEG_QUALITY, name, mime)
            ExportOutcome.Success(uri, bitmap.width, bitmap.height, decoded.downscaled)
        }.getOrElse { ExportOutcome.Failure(it) }
    }

    private companion object { const val JPEG_QUALITY = 95 }
}
