package com.youma.app.core.image

import android.graphics.Bitmap
import com.youma.app.core.geometry.Quad

/**
 * 识别与预览用的图像：已按 EXIF 转正、已降采样。
 *
 * @param scale 降采样图边长 / 原图边长，取值 (0, 1]。导出时按 1/scale 反算坐标。
 * @param originalWidth/originalHeight **转正之后**的原图尺寸——全流程只有一个坐标系。
 */
data class SourceImage(
    val bitmap: Bitmap,
    val scale: Float,
    val originalWidth: Int,
    val originalHeight: Int,
    val mimeType: String,
) {
    val width: Int get() = bitmap.width
    val height: Int get() = bitmap.height

    /** 降采样坐标 → 原图坐标。 */
    fun toOriginal(quad: Quad): Quad = quad.scaled(1f / scale)
}

data class ExportBitmap(
    val bitmap: Bitmap,
    val downscaled: Boolean,
    val width: Int,
    val height: Int,
    /** 导出解码用的 inSampleSize：原图宽 = width * downsampleFactor。 */
    val downsampleFactor: Int = 1,
)
