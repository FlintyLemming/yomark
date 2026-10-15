package moe.flinty.yomark.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Matrix
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
import java.util.concurrent.Executor
import kotlin.math.min

/**
 * 人脸候选的构造。抽成对象是为了能单测，理由同 [BarcodeCandidates]：
 * ML Kit 的 Face 造不出来，但「给定外接框，外扩多少、产出什么状态的候选」跟 ML Kit 无关。
 *
 * ML Kit 给的是五官外接框，发际线与下巴常在框外，直接用会露脸。
 * 按短边比例外扩再裁回图内。
 */
object FaceCandidates {

    fun from(index: Int, trackingId: Int?, box: RectF, imageWidth: Int, imageHeight: Int): Candidate {
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
        )
    }

    /**
     * 转正的图上认出的脸（[Upright]）：在转正的图上外扩——发际线那一侧是转正后的上方——再把框映射回原图。
     * [imageWidth]、[imageHeight] 是原图的；id 带上转了几个直角，不和原图上那一遍的撞。
     */
    fun fromUpright(
        index: Int, trackingId: Int?, box: RectF, imageWidth: Int, imageHeight: Int, turns: Int,
    ): Candidate {
        val (w, h) = if (Math.floorMod(turns, 2) == 0) imageWidth to imageHeight else imageHeight to imageWidth
        val c = from(index, trackingId, box, w, h)
        return c.copy(id = "${c.id}-turn$turns", quad = Quad.fromRect(Upright.toSource(c.quad.bounds(), imageWidth, imageHeight, turns)))
    }

    private const val EXPAND_RATIO = 0.18f
    private const val CONFIDENCE = 0.95f
}

/**
 * 人脸检测（bundled）。
 *
 * 人脸不经过规则表的误报率分层（spec §6 末尾）：认出来就打码。设置里关掉人脸时根本不建它。
 *
 * ML Kit 认不出横躺着的脸：竖着拍的身份证上人像是横的。引擎看文字的方向知道页面转了几个直角，
 * 再让它在转正的图上看一遍（[needsUpright]）。
 */
class MlKitFaceDetector(
    private val mode: FaceOption = FaceOption.FAST,
) : RegionDetector {

    override val id = "mlkit-face-${mode.name.lowercase()}"
    override val kind = SensitiveKind.FACE
    override val needsUpright = true

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
            FaceCandidates.from(i, face.trackingId, RectF(face.boundingBox), image.width, image.height)
        }
    }

    /**
     * 自己把位图转正再交给 ML Kit，不靠 InputImage 的 rotationDegrees：框就落在这张转正的图上，
     * 换回原图的算法全在 [Upright] 里，单测验得到。外扩在转正的图上做（见 FaceCandidates.fromUpright）。
     */
    override suspend fun detect(image: SourceImage, quarterTurns: Int): List<Candidate> {
        val turns = Math.floorMod(quarterTurns, 4)
        if (turns == 0) return detect(image)
        val src = image.bitmap
        val upright = Bitmap.createBitmap(src, 0, 0, src.width, src.height, Upright.matrix(src.width, src.height, turns), true)
        val task = client.process(InputImage.fromBitmap(upright, 0))
        // 等 ML Kit 用完再回收：识别被取消时 await 当场返回，ML Kit 可能还在读这张位图
        task.addOnCompleteListener(Executor { it.run() }) { if (upright !== src) upright.recycle() }
        return task.await().mapIndexed { i, face ->
            FaceCandidates.fromUpright(i, face.trackingId, RectF(face.boundingBox), src.width, src.height, turns)
        }
    }

    private companion object {
        const val MIN_FACE_SIZE = 0.05f
    }
}

/**
 * 页面在图上顺时针转了 turns 个直角时，把图逆着转回来：转正的图和原图之间的坐标换算。
 * 坐标按像素边算，原图占 [0, width] × [0, height]。
 */
internal object Upright {

    /** 原图 → 转正的图。宽高为 [width] × [height] 的原图逆时针转 turns 个直角，平移回第一象限。 */
    fun matrix(width: Int, height: Int, turns: Int): Matrix = Matrix().apply {
        setRotate(-90f * turns)
        when (Math.floorMod(turns, 4)) {
            1 -> postTranslate(0f, width.toFloat())
            2 -> postTranslate(width.toFloat(), height.toFloat())
            3 -> postTranslate(height.toFloat(), 0f)
        }
    }

    /** 转正的图上的框 → 原图上的框。 */
    fun toSource(box: RectF, width: Int, height: Int, turns: Int): RectF {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (Math.floorMod(turns, 4)) {
            1 -> RectF(w - box.bottom, box.left, w - box.top, box.right)
            2 -> RectF(w - box.right, h - box.bottom, w - box.left, h - box.top)
            3 -> RectF(box.top, h - box.right, box.bottom, h - box.left)
            else -> RectF(box)
        }
    }
}
