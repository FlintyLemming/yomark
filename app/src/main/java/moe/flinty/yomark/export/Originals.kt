package moe.flinty.yomark.export

import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import java.io.IOException
import java.io.OutputStream

/**
 * 覆盖原图（顶栏导出右边的小箭头里，设置里默认导出选了它时就是导出本身）：打好码的图写回原图，不另存一份。
 * 抽成接口是为了 EditorViewModel 的单测不碰系统的相册和确认框；应用里是 [MediaOriginals]。
 */
interface Originals {

    /** 传进来的图写回到哪儿。认不出（别的应用自己的文件，比如聊天应用给的）是 null。 */
    suspend fun locate(source: Uri): Uri?

    /**
     * 要写的这几张里还有没拿到许可的：返回系统的确认框，都拿到了（或者问不了）是 null。
     * Android 11 起一个框问完全部；Android 10 一次问一张，问完再来要下一个。问不了的写的时候抛 [OriginalUnavailable]。
     */
    suspend fun writeRequest(targets: List<Uri>): IntentSender?

    /**
     * 把 [encode] 写出的图整个写进 [target]，返回写进去的这一份的指纹。
     * 先编码进缓存，核对 [target] 还是 [expected] 那一张，最后才动原图；写到一半出错，把原来的放回去。
     *
     * @throws OriginalUnavailable 原图变了、没了，或者没有许可：原图一个字节都没动
     */
    suspend fun overwrite(target: Uri, expected: Fingerprint, encode: (OutputStream) -> Unit): Fingerprint

    companion object {
        /**
         * 写回去的格式和原图一样才覆盖：导出只编 PNG 和 JPEG（见 Exporter），HEIC、WebP 之类的原图写成 JPEG，
         * 文件名、类型就和里面的东西对不上了。
         */
        fun keepsFormat(mimeType: String): Boolean = mimeType.lowercase() in SAME_FORMAT

        private val SAME_FORMAT = setOf("image/png", "image/jpeg", "image/jpg", "image/pjpeg")
    }
}

/** 写不回原图，原图一点没动：调用方改成另存一份。 */
class OriginalUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 导出管线（见 Exporter）的写出目的地换成原图 [target]。[expected] 是打开时原图的指纹，写之前拿它核对（见 [Originals.overwrite]）。
 * 写成了，[written] 是写进去的这一份的指纹：同一张图再覆盖一次时拿它核对。
 */
class OverwriteSink(
    private val originals: Originals,
    private val target: Uri,
    private val expected: Fingerprint,
) : ImageSink {

    var written: Fingerprint? = null
        private set

    override suspend fun write(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat,
        quality: Int,
        displayName: String,
        mimeType: String,
    ): Uri {
        written = originals.overwrite(target, expected) { out ->
            if (!bitmap.compress(format, quality, out)) throw IOException("编码失败")
        }
        return target
    }
}
