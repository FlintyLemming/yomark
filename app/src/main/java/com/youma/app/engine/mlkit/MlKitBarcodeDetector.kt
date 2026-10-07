package com.youma.app.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.RegionDetector
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 条码候选的构造（2026-09-04 增补设计 §1）。
 *
 * 抽成对象是为了能单测：ML Kit 的 Barcode 造不出来，但「给定四边形与解码与否，
 * 该产出什么状态的候选」这件事本身跟 ML Kit 无关。
 */
object BarcodeCandidates {

    /**
     * @param decodable 内容解得出来。解不出的（potential）多半只是布料印花、
     *   格纹这类有规律的纹理——真机上商品图就是这么被整块涂黑的。
     *   彩色照片在进这里之前已经被 [TwoInks] 筛掉；剩下的仍然报出来（漏检是事故），
     *   但只圈不打码，交给导出拦截兜底。
     * @param masked 设置里条码的处理方式是「打码」。设成「仅圈出」时解得出的也只圈出。
     */
    fun from(index: Int, quad: Quad, decodable: Boolean, masked: Boolean = true) = Candidate(
        id = "barcode-$index",
        quad = quad,
        kind = SensitiveKind.BARCODE,
        source = DetectorSource.BARCODE,
        confidence = if (decodable) CONFIDENCE_DECODED else CONFIDENCE_POTENTIAL,
        enabledByDefault = decodable && masked,
    )

    private const val CONFIDENCE_DECODED = 0.98f
    private const val CONFIDENCE_POTENTIAL = 0.5f
}

/**
 * 条码检测（bundled）：二维码与条形码。
 *
 * 用 cornerPoints 而不是 boundingBox —— 倾斜拍摄的二维码用外接矩形会盖住旁边的字。
 *
 * **绝不把 barcode.rawValue 写进日志、状态或任何持久化位置**：那是敏感内容本身。
 * 这个类只关心「哪里有一个条码」，不关心它是什么。
 */
class MlKitBarcodeDetector(
    private val strategy: BarcodeOption = BarcodeOption.LOOSE,
    /** 解得出内容的条码一进编辑器就打码（true），还是只圈出（false）。 */
    private val masked: Boolean = true,
) : RegionDetector {

    override val id = "mlkit-barcode-${strategy.name.lowercase()}"
    override val kind = SensitiveKind.BARCODE

    /**
     * 两行配置，管的是两件相反的事，别把它们混起来看：
     *
     * `setBarcodeFormats` **收窄**格式到 [BarcodeFormats.ALLOWED]。不设置等于全格式开启，
     * 而全格式里有三种没有强制校验位，会把按钮和文字解成垃圾数字再整块涂黑（见 BarcodeFormats）。
     *
     * `enableAllPotentialBarcodes` **放宽**到连解不出内容的条码也返回。打码工具只关心
     * 「这里有一个条码」，从来不需要它的内容——默认配置下 ML Kit 只报解码成功的条码，
     * 倾斜、轻微失焦、缩放过的二维码会被静默丢掉（自造的 36 张评测图里丢了 6 张）。
     * 漏检是事故，误报只是麻烦。
     *
     * 收窄格式**之后**再放宽解码要求，两者不冲突：格式白名单决定「拿哪几种形状去匹配」，
     * potential 决定「匹配上了但校验没过要不要报」。
     *
     * 放宽之后 ML Kit 会把彩色照片（真机上是一张酒店缩略图）也当疑似条码报上来，
     * 所以 detect 里对解不出的那部分再加一道像素判断，见 [TwoInks]。
     */
    private val client by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    BarcodeFormats.ALLOWED.first(),
                    *BarcodeFormats.ALLOWED.drop(1).toIntArray(),
                )
                .apply { if (strategy == BarcodeOption.LOOSE) enableAllPotentialBarcodes() }
                .build()
        )
    }

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val barcodes = client.process(input).await()
        // 疑似条码要逐像素看一遍（TwoInks），不放在主线程上
        return withContext(Dispatchers.Default) {
            barcodes.mapIndexedNotNull { i, code ->
                val quad = code.cornerPoints?.takeIf { it.size >= 4 }?.let {
                    Quad(
                        PointF(it[0].x.toFloat(), it[0].y.toFloat()),
                        PointF(it[1].x.toFloat(), it[1].y.toFloat()),
                        PointF(it[2].x.toFloat(), it[2].y.toFloat()),
                        PointF(it[3].x.toFloat(), it[3].y.toFloat()),
                    )
                } ?: code.boundingBox?.let { Quad.fromRect(RectF(it)) } ?: return@mapIndexedNotNull null

                // rawValue 只在这里做一次「有没有」的判断，绝不读出来、更不落盘。
                val decodable = code.rawValue != null
                // 解不出内容的，像素上不是两种墨色（彩色照片）就整个丢掉，连框都不画。
                // 判不了（读像素出错）就留着：这一步只许少报误报，不许让整个条码检测失败。
                if (!decodable && !runCatching { TwoInks.looksPrinted(image.bitmap, quad) }.getOrDefault(true)) {
                    return@mapIndexedNotNull null
                }
                BarcodeCandidates.from(i, quad, decodable, masked)
            }
        }
    }
}
