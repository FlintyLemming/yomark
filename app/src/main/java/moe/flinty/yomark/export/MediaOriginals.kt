package moe.flinty.yomark.export

import android.app.RecoverableSecurityException
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * 覆盖原图，写的是相册里的那个文件（见 [Originals]）。不要任何权限：
 * - 手里本来就有写这张图的授权（系统文件选择器选的图）就直接写；
 * - 相册里的图，系统弹框问用户（Android 11 起是 MediaStore.createWriteRequest，Android 10 是写的时候抛出的
 *   RecoverableSecurityException 里带着的那个框）。用户点了允许，编辑器开着期间这几张就写得了。
 *
 * 写之前先编码进缓存、核对原图、给原图留一份备份，最后整个写进去；写到一半出错就把备份放回去。
 *
 * @param io 读写文件在哪个调度器上。单测换成测试自己的
 * @param granted 手里有没有写这个 URI 的授权。只有单测换它：Robolectric 里 checkUriPermission 问不到系统
 */
class MediaOriginals(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val granted: (Uri) -> Boolean = { uri ->
        context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) ==
            PackageManager.PERMISSION_GRANTED
    },
) : Originals {

    private val resolver get() = context.contentResolver

    /** 编码出来的图、原图的备份放这儿，写完就删。 */
    private val dir: File get() = File(context.cacheDir, DIR).apply { mkdirs() }

    override suspend fun locate(source: Uri): Uri? = withContext(io) {
        if (granted(source)) source else OriginalUris.mediaItem(source, Process.myUid() / PER_USER_RANGE)
    }

    override suspend fun writeRequest(targets: List<Uri>): IntentSender? = withContext(io) {
        // 点过允许的不再问。授权记在系统那儿，问 checkUriPermission；万一它认不出，开一下写得了也算（见 [probe]）
        val lacking = targets.distinct().asSequence().filterNot(granted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 只有相册里的图问得了；别的（授权没了的）写的时候抛 OriginalUnavailable，另存一份
            lacking.filter { it.authority == MediaStore.AUTHORITY && probe(it) != null }.toList().takeIf { it.isNotEmpty() }
                ?.let { MediaStore.createWriteRequest(resolver, it).intentSender }
        } else {
            // Android 10 没有 createWriteRequest：开的那一下抛出的异常里带着问用户的框，一次一张。
            // 问到一张就停，后面的等这张问完再开
            lacking.firstNotNullOfOrNull { (probe(it) as? RecoverableSecurityException)?.userAction?.actionIntent?.intentSender }
        }
    }

    override suspend fun overwrite(target: Uri, expected: Fingerprint, encode: (OutputStream) -> Unit): Fingerprint =
        withContext(io) {
            val staged = File.createTempFile("out", null, dir)
            val backup = File.createTempFile("was", null, dir)
            try {
                staged.outputStream().use(encode)
                val now = try {
                    resolver.openInputStream(target)?.use { input -> backup.outputStream().use { Fingerprint.read(input, it) } }
                } catch (e: SecurityException) {
                    throw OriginalUnavailable("读不了原图：$target", e)
                } catch (e: FileNotFoundException) {
                    null
                }
                if (now != expected) throw OriginalUnavailable("原图和打开时不一样了：$target")
                try {
                    copy(staged, target)
                } catch (e: SecurityException) {
                    // 打开时就被拒了，一个字节还没写
                    throw OriginalUnavailable("没有写原图的许可：$target", e)
                } catch (t: Throwable) {
                    runCatching { copy(backup, target) }
                    throw t
                }
                Fingerprint.of(staged)
            } finally {
                staged.delete()
                backup.delete()
            }
        }

    /** 开一下试试写不写得了（"rw" 不截断，不动内容）：写得了是 null，写不了是抛出来的异常。 */
    private fun probe(uri: Uri): Exception? = try {
        resolver.openFileDescriptor(uri, "rw")?.close()
        null
    } catch (e: Exception) {
        e
    }

    /** "wt"：先截断再写。光 "w" 在 Android 10 的相册里不截断，新图比原图短时尾巴上会剩一截原来的。 */
    private fun copy(from: File, to: Uri) {
        val out = resolver.openOutputStream(to, "wt") ?: throw IOException("打不开：$to")
        out.use { o -> from.inputStream().use { it.copyTo(o) } }
    }

    private companion object {
        const val DIR = "overwrite"

        /** 一个用户占的 uid 段（android.os.UserHandle.PER_USER_RANGE）：uid 除以它就是用户号。 */
        const val PER_USER_RANGE = 100_000
    }
}
