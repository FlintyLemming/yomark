package moe.flinty.yomark.export

import android.graphics.Bitmap
import android.net.Uri

/** 导出的写出目的地。抽出来是为了让 Exporter 能在 JVM 单测里跑完整管线。 */
interface ImageSink {
    suspend fun write(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        displayName: String,
        mimeType: String,
    ): Uri
}
