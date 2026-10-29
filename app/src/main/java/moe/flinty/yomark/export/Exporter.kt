package moe.flinty.yomark.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.RectF
import android.net.Uri
import moe.flinty.yomark.core.image.SourceImageLoader
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.RendererRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

data class ExportRequest(
    val file: File,
    val mimeType: String,
    val plan: MaskPlan,
    /** SourceImage.scale：分析图边长 / 原图边长。遮罩按 1/scale 反算。 */
    val analysisScale: Float,
    val applyWatermark: Boolean,
    /** 品牌水印用哪一版字样；[applyWatermark] 为 false 时不看它。 */
    val watermarkStyle: BrandWatermarkStyle = BrandWatermarkStyle.ICON_ZH_EN,
    /** 用途水印文案（「仅供办理 XX 使用」）。与品牌水印无关，不受购买态影响。 */
    val purposeText: String? = null,
    /** 用途水印的颜色、角度、透明度、密度。 */
    val purposeStyle: PurposeWatermarkStyle = PurposeWatermarkStyle(),
)

sealed interface ExportOutcome {
    /** @param mimeType 写出的格式（PNG 还是 PNG，别的都编成 JPEG）：分享时要告诉分享面板 */
    data class Success(val uri: Uri, val width: Int, val height: Int, val downscaled: Boolean, val mimeType: String) : ExportOutcome
    data class Failure(val cause: Throwable) : ExportOutcome
}

/**
 * 导出管线（spec §9.1）：
 *   原图全分辨率解码 + EXIF 转正 → 逐项渲染 MASKED → 裁掉顶上的状态栏（实验室） → 用途水印、品牌水印 → 重编码 → 写出
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

    /** 同一条管线，写到 [sink]：分享的图写进缓存，不进相册（见 EditorViewModel 的 shareSink）。 */
    fun writingTo(sink: ImageSink) = Exporter(registry, watermark, sink, purposeWatermark)

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
            val renderScale = 1f / decoded.downsampleFactor

            // 每块码按它自己的样式画（spec §7.5 修订），顺序与预览一致（见 MaskOrder）。
            // 近似色先在还没画码的原图上全部取好，再一块块地画：后画的模糊、马赛克读的是底下已经画上去的码。
            val maskedBounds = ArrayList<RectF>()
            MaskOrder.forDrawing(request.plan.items)
                .map { item ->
                    val quad = item.quad.scaled(factor)
                    val options = item.look.options.copy(renderScale = renderScale)
                    Triple(item, quad, MaskOrder.pinNearColor(options, bitmap, quad))
                }
                .forEach { (item, quad, options) ->
                    registry[item.look.style].render(canvas, bitmap, quad, options, item.ink, item.text)
                    maskedBounds += quad.bounds()
                }

            // 顶上那一条（实验室的「裁切状态栏」）在码画完以后裁：码和预览一样画在整张图上，模糊、近似色取的是裁之前四周的像素。
            // 水印画在裁好的图上：用途水印铺满裁好的图，品牌水印落在它的角上，躲开的码跟着往上挪
            val top = (request.plan.cropTop * factor).roundToInt().coerceIn(0, bitmap.height - 1)
            val out = if (top == 0) bitmap else cropTop(bitmap, top)
            val outCanvas = if (top == 0) canvas else Canvas(out)
            if (top > 0) maskedBounds.forEach { it.offset(0f, -top.toFloat()) }

            // 顺序不能反：品牌水印必须画在最上层，否则用途水印的斜纹会压在它上面影响可读性。
            request.purposeText?.let {
                purposeWatermark.draw(outCanvas, out.width, out.height, it, request.purposeStyle)
            }
            if (request.applyWatermark) watermark.draw(outCanvas, out, maskedBounds, request.watermarkStyle)

            val png = request.mimeType.equals("image/png", ignoreCase = true)
            val format = if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
            val mime = if (png) "image/png" else "image/jpeg"
            val name = "YOMARK_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) +
                if (png) ".png" else ".jpg"

            val uri = sink.write(out, format, if (png) 100 else JPEG_QUALITY, name, mime)
            ExportOutcome.Success(uri, out.width, out.height, decoded.downscaled, mime)
        }.getOrElse { ExportOutcome.Failure(it) }
    }

    /**
     * [bitmap] 顶上 [top] 行裁掉：画进一张新的可变位图（还要往上画水印），颜色空间不变，原来那张随即释放。
     * 不用 Bitmap.createBitmap(source, x, y, w, h)：它给的位图文档上说不可变。
     */
    private fun cropTop(bitmap: Bitmap, top: Int): Bitmap {
        val out = Bitmap.createBitmap(
            bitmap.width, bitmap.height - top, bitmap.config ?: Bitmap.Config.ARGB_8888, bitmap.hasAlpha(),
            bitmap.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB),
        )
        Canvas(out).drawBitmap(bitmap, 0f, -top.toFloat(), null)
        bitmap.recycle()
        return out
    }

    private companion object { const val JPEG_QUALITY = 95 }
}
