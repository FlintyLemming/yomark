package moe.flinty.yomark.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * 交给别的应用的图：写进应用私有的缓存，经 FileProvider 给出 content:// URI，不进相册。读权限随 Intent 授予收到的应用。
 * 两处用它，各占缓存里的一个目录（[folder]）：别的应用来要图时交回去的（[HANDOVER]，见 ui/pick/PickImageActivity），
 * 和顶栏上分享出去的（[SHARE]，见 EditorViewModel 的 shareSink）。
 *
 * 交出去之后什么时候读由收到的应用定，所以不随手删，等下一次交图时再清（[clear]）：下一次有应用来要图、下一次分享。
 *
 * @param io 写文件在哪个调度器上。单测换成测试自己的，advanceUntilIdle 才等得到写完
 * @param uriFor 文件换成给出去的 URI。只有单测换它：FileProvider 按 / 拼路径，Windows 上跑 Robolectric 对不上
 */
class FileProviderSink(
    private val context: Context,
    /** 缓存里的哪个目录，和 res/xml/file_paths.xml 里的 cache-path 一致。 */
    private val folder: String = HANDOVER,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val uriFor: (File) -> Uri = { FileProvider.getUriForFile(context, authority(context), it) },
) : ImageSink {

    private val dir: File get() = File(context.cacheDir, folder).apply { mkdirs() }

    override suspend fun write(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        displayName: String,
        mimeType: String,
    ): Uri = withContext(io) {
        val target = vacant(displayName)
        try {
            target.outputStream().use { out ->
                if (!bitmap.compress(format, quality, out)) throw IOException("编码失败")
            }
        } catch (t: Throwable) {
            target.delete()                       // 不留半成品
            throw t
        }
        uriFor(target)
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    /** 文件名只精确到秒，批量里一秒内导出好几张：重名的往后加序号，不互相覆盖。 */
    private fun vacant(displayName: String): File {
        val ext = if ('.' in displayName) "." + displayName.substringAfterLast('.') else ""
        val stem = displayName.removeSuffix(ext)
        return generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) displayName else "${stem}_$n$ext") }
            .first { !it.exists() }
    }

    companion object {
        /** 别的应用来要图时交回去的。 */
        const val HANDOVER = "handover"

        /** 顶栏上分享出去的。 */
        const val SHARE = "share"

        /** 与 AndroidManifest 里 FileProvider 的 authorities 一致。 */
        fun authority(context: Context) = "${context.packageName}.files"
    }
}
