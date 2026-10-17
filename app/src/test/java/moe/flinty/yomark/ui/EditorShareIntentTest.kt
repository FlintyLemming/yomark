package moe.flinty.yomark.ui

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 顶栏的分享交给系统分享面板的 Intent（见 EditorActivity.shareIntent / shareChooser）：一张是 ACTION_SEND，多张是 ACTION_SEND_MULTIPLE，
 * 读权限随 clipData 授予；套上分享面板后图和权限都还在，面板上才看得到图、收到的应用才读得到。面板上不列有码自己。
 */
@RunWith(RobolectricTestRunner::class)
class EditorShareIntentTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private val a = Uri.parse("content://moe.flinty.yomark.files/share/YOMARK_20261017_120000.jpg")
    private val b = Uri.parse("content://moe.flinty.yomark.files/share/YOMARK_20261017_120000_2.jpg")

    private fun Intent.grantsRead() = (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0

    private fun Intent.clipUris() = clipData!!.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } }

    @Test
    fun `one image goes out as a single send`() {
        val intent = EditorActivity.shareIntent(listOf(a), "image/jpeg")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.type).isEqualTo("image/jpeg")
        assertThat(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)).isEqualTo(a)
        assertThat(intent.clipUris()).containsExactly(a)
        assertThat(intent.grantsRead()).isTrue()
    }

    @Test
    fun `several images go out together, in order`() {
        val intent = EditorActivity.shareIntent(listOf(a, b), "image/jpeg")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND_MULTIPLE)
        assertThat(IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)).containsExactly(a, b).inOrder()
        assertThat(intent.clipUris()).containsExactly(a, b).inOrder()
        assertThat(intent.grantsRead()).isTrue()
    }

    @Test
    fun `the share sheet keeps the images and the read permission`() {
        val chooser = EditorActivity.shareChooser(context, listOf(a, b), "image/*")

        assertThat(chooser.action).isEqualTo(Intent.ACTION_CHOOSER)
        assertThat(chooser.clipUris()).containsExactly(a, b).inOrder()
        assertThat(chooser.grantsRead()).isTrue()
    }

    /** 有码自己也收分享来的图，分享面板上不列它。 */
    @Test
    fun `the share sheet leaves out yomark itself`() {
        val chooser = EditorActivity.shareChooser(context, listOf(a), "image/jpeg")

        val excluded = IntentCompat.getParcelableArrayExtra(chooser, Intent.EXTRA_EXCLUDE_COMPONENTS, ComponentName::class.java)
        assertThat(excluded!!.toList()).containsExactly(ComponentName(context, EditorActivity::class.java))
    }
}
