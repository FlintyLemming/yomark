package com.youma.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import com.youma.app.core.image.SourceImageLoader
import com.youma.app.core.model.MaskPlan
import com.youma.app.core.model.MaskState
import com.youma.app.render.RendererRegistry
import kotlinx.coroutines.CoroutineDispatcher
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
    /** 用途水印文案（「仅供办理 XX 使用」）。与品牌水印无关，不受购买态影响。 */
    val purposeText: String? = null,
    /** 用途水印的颜色、角度、透明度、密度。 */
    val purposeStyle: PurposeWatermarkStyle = PurposeWatermarkStyle(),
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
    private val purposeWatermark: PurposeWatermarkDrawer = PurposeWatermarkDrawer(),
) {

    /** @param dispatcher 整条管线跑在哪个调度器上；默认 Default，调用方可换成自己的。 */
    suspend fun export(
        request: ExportRequest,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
    ): ExportOutcome = withContext(dispatcher) {
        runCatching {
            val decoded = SourceImageLoader.loadForExport(request.file, dispatcher)
            val bitmap = decoded.bitmap
            val canvas = Canvas(bitmap)

            // 分析坐标 → 导出坐标。导出图可能因 32MP 上限又降过一次，两个系数合成一个。
            val factor = 1f / (request.analysisScale * decoded.downsampleFactor)

            // 渲染器里的绝对像素常数是原图口径；导出图可能因 32MP 上限降过一次，按它折算。
            val options = request.plan.options.copy(renderScale = 1f / decoded.downsampleFactor)

            val maskedBounds = ArrayList<RectF>()
            request.plan.items
                .filter { it.state == MaskState.MASKED }
                .forEach { item ->
                    val quad = item.quad.scaled(factor)
                    registry[request.plan.style].render(canvas, bitmap, quad, options)
                    maskedBounds += quad.bounds()
                }

            // 顺序不能反：品牌水印必须画在最上层，否则用途水印的斜纹会压在它上面影响可读性。
            request.purposeText?.let {
                purposeWatermark.draw(canvas, bitmap.width, bitmap.height, it, request.purposeStyle)
            }
            if (request.applyWatermark) watermark.draw(canvas, bitmap, maskedBounds)

            val png = request.mimeType.equals("image/png", ignoreCase = true)
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val mime = if (png) "image/png" else "image/jpeg"
            val name = "YOUMA_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                if (png) ".png" else ".jpg"

            val uri = sink.write(bitmap, format, if (png) 100 else JPEG_QUALITY, name, mime)
            ExportOutcome.Success(uri, bitmap.width, bitmap.height, decoded.downscaled)
        }.getOrElse { ExportOutcome.Failure(it) }
    }

    private companion object { const val JPEG_QUALITY = 95 }
}
