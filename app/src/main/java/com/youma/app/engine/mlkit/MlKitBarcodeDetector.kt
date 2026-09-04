package com.youma.app.engine.mlkit

import android.graphics.PointF
import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.engine.RegionDetector
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await

/**
 * 条码检测（bundled）：二维码与条形码。
 *
 * 用 cornerPoints 而不是 boundingBox —— 倾斜拍摄的二维码用外接矩形会盖住旁边的字。
 *
 * **绝不把 barcode.rawValue 写进日志、状态或任何持久化位置**：那是敏感内容本身。
 * 这个类只关心「哪里有一个条码」，不关心它是什么。
 */
class MlKitBarcodeDetector : RegionDetector {

    override val id = "mlkit-barcode"
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
                .enableAllPotentialBarcodes()
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

            Candidate(
                id = "barcode-$i",
                quad = quad,
                kind = SensitiveKind.BARCODE,
                source = DetectorSource.BARCODE,
                confidence = CONFIDENCE,
                enabledByDefault = true,
            )
        }
    }

    private companion object { const val CONFIDENCE = 0.98f }
}
