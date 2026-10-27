package moe.flinty.yomark.export

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlin.random.Random

/** 认原图用的指纹：长度加末尾一段。开头（EXIF 里的位置被抹掉的地方）变了认得出是同一张，图像数据变了一个字节就不是。 */
class FingerprintTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(size: Int, seed: Int = 1) = Random(seed).nextBytes(size)

    private fun ByteArray.print() = Fingerprint.read(ByteArrayInputStream(this))

    private fun ByteArray.flip(at: Int) = copyOf().also { it[at] = (it[at] + 1).toByte() }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** 一次只给几百个字节，末尾那一段在环形缓冲里绕好几圈。 */
    private class Trickle(data: ByteArray, private val step: Int) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read() = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int) = inner.read(b, off, minOf(len, step))
    }

    @Test
    fun `the tail is the last 64 KB hashed`() {
        val data = bytes(200_003)
        val print = Fingerprint.read(Trickle(data, 777))

        assertThat(print.length).isEqualTo(200_003L)
        assertThat(print.tail).isEqualTo(sha256(data.copyOfRange(data.size - Fingerprint.TAIL, data.size)))
        assertThat(data.print()).isEqualTo(print)
    }

    @Test
    fun `a small file hashes its second half`() {
        val data = bytes(1001)
        assertThat(data.print().tail).isEqualTo(sha256(data.copyOfRange(1001 - 500, 1001)))
    }

    @Test
    fun `the same bytes give the same print`() {
        assertThat(bytes(150_000).print()).isEqualTo(bytes(150_000).print())
    }

    /** 系统交出照片时把 EXIF 里的位置抹成 0：开头变了，长度不变。 */
    @Test
    fun `a change near the head is the same image`() {
        val data = bytes(300_000)
        assertThat(data.flip(40).print()).isEqualTo(data.print())
        val small = bytes(2000)
        assertThat(small.flip(40).print()).isEqualTo(small.print())
    }

    @Test
    fun `a change in the image data is another image`() {
        val data = bytes(300_000)
        assertThat(data.flip(data.size - 1).print()).isNotEqualTo(data.print())
        assertThat(data.flip(data.size - Fingerprint.TAIL).print()).isNotEqualTo(data.print())
    }

    @Test
    fun `a different length is another image`() {
        val data = bytes(300_000)
        assertThat((byteArrayOf(0) + data).print()).isNotEqualTo(data.print())
    }

    @Test
    fun `reading leaves an exact copy behind`() {
        val data = bytes(130_000)
        val copy = ByteArrayOutputStream()
        Fingerprint.read(Trickle(data, 4096), copy)
        assertThat(copy.toByteArray()).isEqualTo(data)
    }

    @Test
    fun `a file and its stream give the same print`() {
        val data = bytes(90_000)
        val file = tmp.newFile("a.jpg").apply { writeBytes(data) }
        assertThat(Fingerprint.of(file)).isEqualTo(data.print())
    }

    @Test
    fun `an empty file still has a print`() {
        assertThat(ByteArray(0).print()).isEqualTo(Fingerprint(0, sha256(ByteArray(0))))
    }
}
