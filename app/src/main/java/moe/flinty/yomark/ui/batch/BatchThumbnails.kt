package moe.flinty.yomark.ui.batch

import android.content.ContentResolver
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.LruCache
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 批量里各张图的缩略图：胶片条上画它，切图时画布载入的那一下也先垫上它。按 URI 记在内存里，不落盘。
 *
 * 相册分享来的、Photo Picker 选出来的 URI 走系统现成的缩略图，几毫秒；别的来源解原图，解的时候就按目标尺寸降采样。
 * 两条路都会按 EXIF 转正。读不出来就是 null，格子上留个底色，不影响编辑。
 *
 * @param sizePx 缩略图长边的上限
 */
class BatchThumbnails(private val resolver: ContentResolver, private val sizePx: Int) {

    private val cache = object : LruCache<Uri, ImageBitmap>(MAX_BYTES) {
        override fun sizeOf(key: Uri, value: ImageBitmap) = value.width * value.height * 4
    }

    /** 同时解几张。胶片条上每一格一出来就要它的缩略图，一次分享几十张时不能几十张一起解：别的来源要解原图，内存吃不消。 */
    private val decoding = Semaphore(PARALLEL_DECODES)

    /** 已经解好的，没有时为 null。画布垫底时先拿它，免得空一帧。 */
    fun cached(uri: Uri): ImageBitmap? = cache.get(uri)

    suspend fun load(uri: Uri): ImageBitmap? {
        cache.get(uri)?.let { return it }
        val bitmap = decoding.withPermit {
            cache.get(uri) ?: withContext(Dispatchers.IO) {
                try {
                    decode(uri)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
            }
        } ?: return null
        return bitmap.also { cache.put(uri, it) }
    }

    private fun decode(uri: Uri): ImageBitmap? {
        val system = runCatching { resolver.loadThumbnail(uri, Size(sizePx, sizePx), null) }.getOrNull()
        if (system != null) return system.asImageBitmap()
        val source = ImageDecoder.createSource(resolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longEdge = max(info.size.width, info.size.height)
            if (longEdge > sizePx) {
                val scale = sizePx.toFloat() / longEdge
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            // 软件位图：缩略图要在画布上裁一块画，硬件位图在测试和截图用的软件画布上画不出来
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }.asImageBitmap()
    }

    private companion object {
        /** 长边 300 来像素的缩略图一张不到 0.5 MB，几十张都放得下。 */
        const val MAX_BYTES = 24 * 1024 * 1024
        const val PARALLEL_DECODES = 4
    }
}
