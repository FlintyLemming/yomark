package moe.flinty.yomark.export

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 传进来的图是相册里的哪一张：Photo Picker、相册分享、文件选择器、Google 相册各一种写法，认不出的不猜。 */
@RunWith(RobolectricTestRunner::class)
class OriginalUrisTest {

    private fun item(uri: String, user: Int = 0) = OriginalUris.mediaItem(Uri.parse(uri), user)?.toString()

    @Test
    fun `a photo picked from this device maps to its gallery item`() {
        assertThat(item("content://media/picker/0/com.android.providers.media.photopicker/media/1000000033"))
            .isEqualTo("content://media/external/images/media/1000000033")
        assertThat(item("content://media/picker_get_content/0/com.android.providers.media.photopicker/media/42"))
            .isEqualTo("content://media/external/images/media/42")
    }

    /** Google 相册云端的图不在本机，工作资料里的图是另一个用户的：都写不回去。 */
    @Test
    fun `cloud photos and other users' photos are not traced back`() {
        assertThat(item("content://media/picker/0/com.google.android.apps.photos.cloudpicker/media/42")).isNull()
        assertThat(item("content://media/picker/10/com.android.providers.media.photopicker/media/42")).isNull()
        assertThat(item("content://media/picker/10/com.android.providers.media.photopicker/media/42", user = 10))
            .isEqualTo("content://media/external/images/media/42")
    }

    @Test
    fun `a gallery item stays itself`() {
        assertThat(item("content://media/external/images/media/7")).isEqualTo("content://media/external/images/media/7")
        assertThat(item("content://media/external_primary/images/media/7"))
            .isEqualTo("content://media/external_primary/images/media/7")
        assertThat(item("content://media/external/file/7")).isEqualTo("content://media/external/images/media/7")
        assertThat(item("content://media/external/downloads/7")).isEqualTo("content://media/external/images/media/7")
    }

    @Test
    fun `videos, albums and odd ids are not images to overwrite`() {
        assertThat(item("content://media/external/video/media/7")).isNull()
        assertThat(item("content://media/external/images/media")).isNull()
        assertThat(item("content://media/external/images/media/abc")).isNull()
        assertThat(item("content://media/external/images/media/0")).isNull()
    }

    @Test
    fun `the files app's image entries map to the gallery`() {
        assertThat(item("content://com.android.providers.media.documents/document/image%3A55"))
            .isEqualTo("content://media/external/images/media/55")
        assertThat(item("content://com.android.providers.media.documents/document/video%3A55")).isNull()
    }

    @Test
    fun `google photos shares carry the gallery item in their path`() {
        val shared = "content://com.google.android.apps.photos.contentprovider/-1/1/" +
            Uri.encode("content://media/external/images/media/1234") + "/ORIGINAL/NONE/image%2Fjpeg/1946452813"
        assertThat(item(shared)).isEqualTo("content://media/external/images/media/1234")
        assertThat(item("content://com.google.android.apps.photos.contentprovider/0/1/mediakey%3A%2Flocal%253A1/ORIGINAL"))
            .isNull()
    }

    /** 聊天应用、别的应用自己的文件：不知道是相册里哪一张。 */
    @Test
    fun `other apps' own files are not traced back`() {
        assertThat(item("content://com.tencent.mm.external.fileprovider/attachment/a.jpg")).isNull()
        assertThat(item("file:///sdcard/Pictures/a.jpg")).isNull()
    }
}
