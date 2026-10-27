package moe.flinty.yomark.export

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.min

/**
 * 认原图用的指纹：文件多长，加上末尾一段的 SHA-256。覆盖原图之前拿它核对，原图还是打开时那一张才写（见 [Originals.overwrite]）。
 *
 * 只看末尾：本应用没有 ACCESS_MEDIA_LOCATION，系统交出照片时把 EXIF 里的位置抹成 0（长度不变），EXIF 在文件开头。
 * 同一张图从 Photo Picker 读和从相册读，抹没抹可能不一样，开头对不上；末尾是图像数据，总是一样的。
 * 换成另一张图，长度一字节不差、末尾 64 KB 也一样，碰不上。
 */
data class Fingerprint(val length: Long, val tail: String) {

    companion object {
        /** 末尾最多看这么长；不到两倍长的小文件看后一半。 */
        const val TAIL = 64 * 1024

        fun of(file: File): Fingerprint = file.inputStream().use { read(it) }

        /** 读完 [input]，顺手原样抄一份进 [copy]（覆盖前给原图留的备份，写坏了放回去）。 */
        fun read(input: InputStream, copy: OutputStream? = null): Fingerprint {
            // 不知道多长，边读边留最后 TAIL 个字节
            val ring = ByteArray(TAIL)
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                copy?.write(buffer, 0, n)
                var i = 0
                while (i < n) {
                    val at = ((total + i) % TAIL).toInt()
                    val run = min(n - i, TAIL - at)
                    System.arraycopy(buffer, i, ring, at, run)
                    i += run
                }
                total += n
            }
            val size = min(TAIL.toLong(), total / 2).toInt()
            val start = ((total - size) % TAIL).toInt()
            val first = min(size, TAIL - start)
            val digest = MessageDigest.getInstance("SHA-256").apply {
                update(ring, start, first)
                update(ring, 0, size - first)
            }.digest()
            return Fingerprint(total, digest.joinToString("") { "%02x".format(it) })
        }
    }
}
