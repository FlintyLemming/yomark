package com.yomark.app.core.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.max

/**
 * 解码 + EXIF 转正 + 降采样（spec §5.1、§9.2）。
 *
 * 识别跑在长边 2048 的缩略图上（省时省内存），打码在原图上画。
 * 两者之间靠 SourceImage.scale 换算。
 */
object SourceImageLoader {

    const val ANALYSIS_MAX_EDGE = 2048
    const val EXPORT_MAX_PIXELS = 32_000_000

    /**
     * @param dispatcher 解码跑在哪个调度器上。默认 IO；调用方（如 EditorViewModel）
     * 传入自己的调度器，测试里就能用 TestDispatcher 把这段异步工作纳入调度控制。
     */
    suspend fun loadForAnalysis(
        file: File,
        mimeType: String,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): SourceImage = withContext(dispatcher) {
        val (rawW, rawH) = readSize(file)
        val orientation = readOrientation(file)
        val sample = sampleSizeForEdge(max(rawW, rawH), ANALYSIS_MAX_EDGE)

        val decoded = decode(file, sample)
        val upright = applyOrientation(decoded, orientation)

        // 转正后的原图尺寸：旋转 90/270 时宽高互换
        val swapped = orientation in ROTATED_90_OR_270
        val origW = if (swapped) rawH else rawW
        val origH = if (swapped) rawW else rawH

        SourceImage(
            bitmap = upright,
            scale = upright.width.toFloat() / origW.toFloat(),
            originalWidth = origW,
            originalHeight = origH,
            mimeType = mimeType,
        )
    }

    suspend fun loadForExport(
        file: File,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): ExportBitmap = withContext(dispatcher) {
        val (rawW, rawH) = readSize(file)
        val orientation = readOrientation(file)
        val sample = sampleSizeForPixels(rawW.toLong() * rawH.toLong(), EXPORT_MAX_PIXELS.toLong())

        val decoded = decode(file, sample)
        // 导出要在这张位图上直接画遮罩，必须可变：BitmapFactory 默认解出的是不可变位图，
        // 直接塞给 Canvas 会抛 "Immutable bitmap passed to Canvas constructor"。
        val upright = applyOrientation(decoded, orientation).ensureMutable()
        ExportBitmap(
            bitmap = upright,
            downscaled = sample > 1,
            width = upright.width,
            height = upright.height,
            downsampleFactor = sample,
        )
    }

    private fun readSize(file: File): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) throw IOException("无法解码：${file.name}")
        return opts.outWidth to opts.outHeight
    }

    private fun readOrientation(file: File): Int = runCatching {
        ExifInterface(file.absolutePath)
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun decode(file: File, sampleSize: Int): Bitmap {
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IOException("解码返回 null：${file.name}")
    }

    /** 2 的幂降采样，保证降采样后长边 ≤ maxEdge。 */
    internal fun sampleSizeForEdge(longEdge: Int, maxEdge: Int): Int {
        var s = 1
        while (longEdge / (s * 2) >= maxEdge) s *= 2
        // 上面的循环保证 longEdge/s < maxEdge*2；再收一次确保真的不超
        while (longEdge / s > maxEdge) s *= 2
        return s
    }

    internal fun sampleSizeForPixels(pixels: Long, maxPixels: Long): Int {
        var s = 1
        while (pixels / (s.toLong() * s.toLong()) > maxPixels) s *= 2
        return s
    }

    /**
     * 把方向烘进像素。此后不再有旋转角这个概念——
     * ML Kit 虽然接受 rotation 参数，但统一坐标系比到处传角度省事得多。
     */
    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.setRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> m.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.setRotate(-90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> m.setRotate(-90f)
            else -> return src
        }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** 只在真的需要时才拷贝——大图拷贝一次就是几十 MB。 */
    private fun Bitmap.ensureMutable(): Bitmap =
        if (isMutable) this else copy(Bitmap.Config.ARGB_8888, true).also { if (it !== this) recycle() }

    private val ROTATED_90_OR_270 = setOf(
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_ROTATE_270,
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_TRANSVERSE,
    )
}
