package com.yomark.app.core.image

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class IntakeResult(val file: File, val mimeType: String)

/**
 * 把外来 URI 立即复制进应用私有目录（spec §7.1）。
 *
 * 两个理由：
 * 1. ACTION_SEND 给的 content:// 授权在 Activity 重建后可能失效；
 * 2. 用户可能在编辑期间把原图删了。
 *
 * 此后全流程只读私有副本。副本在导出成功或 Activity 销毁时由调用方 clear()。
 */
class ImageIntake(private val context: Context) {

    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    suspend fun copyToPrivate(
        uri: Uri,
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): IntakeResult = withContext(dispatcher) {
        val mime = context.contentResolver.getType(uri) ?: DEFAULT_MIME
        val ext = if (mime.equals("image/png", ignoreCase = true)) "png" else "jpg"
        val target = File(dir, "src-${System.currentTimeMillis()}-${uri.hashCode()}.$ext")

        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: throw IOException("无法读取来源 URI：$uri")

        if (target.length() == 0L) {
            target.delete()
            throw IOException("来源 URI 读到 0 字节：$uri")
        }
        IntakeResult(target, mime)
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val DIR = "intake"
        const val DEFAULT_MIME = "image/jpeg"
    }
}
