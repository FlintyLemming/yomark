package com.youma.app.engine.mlkit

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.Candidate
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.engine.FaceOption
import com.youma.app.engine.RegionDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min

/**
 * 人脸检测（bundled）。
 *
 * 人脸不经过规则表的误报率分层（spec §6 末尾）——默认状态恒为打码。
 *
 * ML Kit 给的是五官外接框，发际线与下巴常在框外，直接用会露脸。
 * 按短边比例外扩再裁回图内。
 */
class MlKitFaceDetector(
    private val mode: FaceOption = FaceOption.FAST,
) : RegionDetector {

    override val id = "mlkit-face-${mode.name.lowercase()}"
    override val kind = SensitiveKind.FACE

    private val client by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    if (mode == FaceOption.ACCURATE) FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE
                    else FaceDetectorOptions.PERFORMANCE_MODE_FAST
                )
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build()
        )
    }

    override suspend fun detect(image: SourceImage): List<Candidate> {
        val input = InputImage.fromBitmap(image.bitmap, 0)
        val faces = client.process(input).await()
        return faces.mapIndexed { i, face ->
            val box = RectF(face.boundingBox)
            val pad = min(box.width(), box.height()) * EXPAND_RATIO
            val expanded = RectF(
                (box.left - pad).coerceAtLeast(0f),
                (box.top - pad * 1.4f).coerceAtLeast(0f),        // 上方多留一点给发际线
                (box.right + pad).coerceAtMost(image.width.toFloat()),
                (box.bottom + pad).coerceAtMost(image.height.toFloat()),
            )
            Candidate(
                id = "face-${face.trackingId ?: i}-$i",
                quad = Quad.fromRect(expanded),
                kind = SensitiveKind.FACE,
                source = DetectorSource.FACE,
                confidence = CONFIDENCE,
                enabledByDefault = true,
            )
        }
    }

    private companion object {
        const val MIN_FACE_SIZE = 0.05f
        const val EXPAND_RATIO = 0.18f
        const val CONFIDENCE = 0.95f
    }
}
