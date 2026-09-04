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
import kotlinx.coroutines.tasks.await

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
     *   仍然报出来（漏检是事故），但只圈不打码，交给导出拦截兜底。
     */
    fun from(index: Int, quad: Quad, decodable: Boolean) = Candidate(
        id = "barcode-$index",
        quad = quad,
        kind = SensitiveKind.BARCODE,
        source = DetectorSource.BARCODE,
        confidence = if (decodable) CONFIDENCE_DECODED else CONFIDENCE_POTENTIAL,
        enabledByDefault = decodable,
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
) : RegionDetector {

    override val id = "mlkit-barcode-${strategy.name.lowercase()}"
    override val kind = SensitiveKind.BARCODE

    /**
     * `enableAllPotentialBarcodes` 是这个类的关键一行：它让检测器连**解不出内容**的条码
     * 也一并返回。打码工具只关心「这里有一个条码」，从来不需要它的内容——
     * 默认配置下 ML Kit 只报解码成功的条码，倾斜、轻微失焦、缩放过的二维码
     * 会被静默丢掉（自造的 36 张评测图里丢了 6 张）。漏检是事故，误报只是麻烦。
     */
    private val client by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .apply { if (strategy == BarcodeOption.LOOSE) enableAllPotentialBarcodes() }
                .build()
        )
    }

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val barcodes = client.process(input).await()
        return barcodes.mapIndexedNotNull { i, code ->
            val quad = code.cornerPoints?.takeIf { it.size >= 4 }?.let {
                Quad(
                    PointF(it[0].x.toFloat(), it[0].y.toFloat()),
                    PointF(it[1].x.toFloat(), it[1].y.toFloat()),
                    PointF(it[2].x.toFloat(), it[2].y.toFloat()),
                    PointF(it[3].x.toFloat(), it[3].y.toFloat()),
                )
            } ?: code.boundingBox?.let { Quad.fromRect(RectF(it)) } ?: return@mapIndexedNotNull null

            // rawValue 只在这里做一次「有没有」的判断，绝不读出来、更不落盘。
            BarcodeCandidates.from(i, quad, decodable = code.rawValue != null)
        }
    }
}
