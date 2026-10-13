package moe.flinty.yomark.core.image

import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * 识别图是从哪个文件降采样来的。识别图上太小的东西（照片里的二维码）回原图上按块再看一遍，见 MlKitBarcodeDetector。
 *
 * 文件里的像素没转正（EXIF 方向只记在文件头里），按块读出来的也没转正：块上找到的点用 [toUpright] 换到转正后的原图坐标。
 *
 * @param orientation EXIF 方向（ExifInterface.ORIENTATION_*）
 * @param width/height 文件里没转正的像素尺寸
 */
data class OriginalFile(val file: File, val orientation: Int, val width: Int, val height: Int) {

    /** 没转正的像素坐标 → 转正后的原图坐标。转法同 SourceImageLoader.applyOrientation，按像素边的口径。 */
    fun toUpright(x: Float, y: Float): PointF {
        val w = width.toFloat()
        val h = height.toFloat()
        return when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> PointF(w - x, y)
            ExifInterface.ORIENTATION_ROTATE_180 -> PointF(w - x, h - y)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> PointF(x, h - y)
            ExifInterface.ORIENTATION_TRANSPOSE -> PointF(y, x)
            ExifInterface.ORIENTATION_ROTATE_90 -> PointF(h - y, x)
            ExifInterface.ORIENTATION_TRANSVERSE -> PointF(h - y, w - x)
            ExifInterface.ORIENTATION_ROTATE_270 -> PointF(y, w - x)
            else -> PointF(x, y)
        }
    }
}
