package moe.flinty.yomark.engine.mlkit

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RegionDetector
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.min

/**
 * 人脸候选的构造。抽成对象是为了能单测，理由同 [BarcodeCandidates]：
 * ML Kit 的 Face 造不出来，但「给定外接框，外扩多少、产出什么状态的候选」跟 ML Kit 无关。
 *
 * ML Kit 给的是五官外接框，发际线与下巴常在框外，直接用会露脸。
 * 按短边比例外扩再裁回图内。
 */
object FaceCandidates {

    /** @param masked 一进编辑器就打码（true），还是只圈出（false）。来自设置里人脸的处理方式。 */
    fun from(index: Int, trackingId: Int?, box: RectF, imageWidth: Int, imageHeight: Int, masked: Boolean): Candidate {
        val pad = min(box.width(), box.height()) * EXPAND_RATIO
        val expanded = RectF(
            (box.left - pad).coerceAtLeast(0f),
            (box.top - pad * 1.4f).coerceAtLeast(0f),        // 上方多留一点给发际线
            (box.right + pad).coerceAtMost(imageWidth.toFloat()),
            (box.bottom + pad).coerceAtMost(imageHeight.toFloat()),
        )
        return Candidate(
            id = "face-${trackingId ?: index}-$index",
            quad = Quad.fromRect(expanded),
            kind = SensitiveKind.FACE,
            source = DetectorSource.FACE,
            confidence = CONFIDENCE,
            enabledByDefault = masked,
        )
    }

    private const val EXPAND_RATIO = 0.18f
    private const val CONFIDENCE = 0.95f
}

/**
 * 人脸检测（bundled）。
 *
 * 人脸不经过规则表的误报率分层（spec §6 末尾）：出厂打码，设置里可以改成仅圈出（[masked]）。
 */
class MlKitFaceDetector(
    private val mode: FaceOption = FaceOption.FAST,
    private val masked: Boolean = true,
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
            FaceCandidates.from(i, face.trackingId, RectF(face.boundingBox), image.width, image.height, masked)
        }
    }

    private companion object {
        const val MIN_FACE_SIZE = 0.05f
    }
}
