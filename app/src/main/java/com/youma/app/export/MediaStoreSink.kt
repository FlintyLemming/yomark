package com.youma.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 写入相册（spec §9.1 第 5 步）。
 *
 * 全程 IS_PENDING 事务：写入期间对相册不可见，避免半成品被扫到。
 * minSdk 29 起写自己创建的媒体文件不需要任何权限——这是 minSdk 定在 29 的原因。
 */
class MediaStoreSink(private val context: Context) : ImageSink {

    override suspend fun write(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        displayName: String,
        mimeType: String,
    ): Uri = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Youma")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore.insert 返回 null")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(format, quality, out)) throw IOException("编码失败")
            } ?: throw IOException("无法打开输出流：$uri")

            resolver.update(uri, ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }, null, null)
            uri
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)     // 事务失败：不留半成品
            throw t
        }
    }
}
