package moe.flinty.yomark.export

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * 传进来的图是相册（MediaStore）里的哪一张：覆盖原图要写回去的 URI。认不出就是 null。
 *
 * 几种来路：
 * - 相册直接分享来的：本来就是 content://media/<卷>/images/media/<id>（也有 file/、downloads/ 底下同一个 id 的）。
 * - 首页 Photo Picker 选的：content://media/picker/<用户>/<来源>/media/<id>，只读。来源是本机相册
 *   （com.android.providers.media.photopicker）时 id 就是 MediaStore 里的 _id；Google 相册云端的不在本机，写不回去。
 * - 系统文件选择器「图片」那一栏的：content://com.android.providers.media.documents/document/image:<id>。
 * - Google 相册分享来的：它自己的 provider，路径里夹着原图的 MediaStore URI（content%3A%2F%2Fmedia%2F…）。
 *
 * Picker 的 id、Google 相册的路径都不是公开的约定，认错了也写不到别的图上：写之前先核对原图（见 [Fingerprint]）。
 * 系统的确认框上也画着要改的是哪一张。
 */
internal object OriginalUris {

    /** 本机相册在 Photo Picker 里的来源。 */
    private const val LOCAL_PICKER = "com.android.providers.media.photopicker"
    private const val MEDIA_DOCUMENTS = "com.android.providers.media.documents"
    private const val GOOGLE_PHOTOS = "com.google.android.apps.photos.contentprovider"

    private val PICKER = setOf("picker", "picker_get_content")

    /** 不分图片、视频的集合，同一个 id 在图片的集合里也是它。 */
    private val FLAT = setOf("file", "downloads")

    /** @param user 本应用跑在哪个用户下：Picker 里选的工作资料（另一个用户）的图写不了 */
    fun mediaItem(uri: Uri, user: Int): Uri? {
        val segments = uri.pathSegments
        return when (uri.authority) {
            MediaStore.AUTHORITY -> when {
                // content://media/picker/0/com.android.providers.media.photopicker/media/1000000033
                segments.size == 5 && segments[0] in PICKER -> segments
                    .takeIf { it[1] == user.toString() && it[2] == LOCAL_PICKER && it[3] == "media" }
                    ?.let { image(MediaStore.VOLUME_EXTERNAL, it[4]) }
                segments.size == 4 && segments[1] == "images" && segments[2] == "media" -> image(segments[0], segments[3])
                segments.size == 3 && segments[1] in FLAT -> image(segments[0], segments[2])
                else -> null
            }
            MEDIA_DOCUMENTS -> segments.takeIf { it.size == 2 && it[0] == "document" && it[1].startsWith(IMAGE_DOCUMENT) }
                ?.let { image(MediaStore.VOLUME_EXTERNAL, it[1].removePrefix(IMAGE_DOCUMENT)) }
            GOOGLE_PHOTOS -> segments.firstOrNull { it.startsWith("content://${MediaStore.AUTHORITY}/") }
                ?.let { mediaItem(Uri.parse(it), user) }
            else -> null
        }
    }

    private const val IMAGE_DOCUMENT = "image:"

    private fun image(volume: String, id: String): Uri? = id.toLongOrNull()?.takeIf { it > 0 }
        ?.let { ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), it) }
}
