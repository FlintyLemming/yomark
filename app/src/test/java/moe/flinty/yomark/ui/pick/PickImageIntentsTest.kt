package moe.flinty.yomark.ui.pick

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import moe.flinty.yomark.ui.EditorActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 别的应用来要图时列得出本应用（Telegram 用的是 ACTION_PICK，类型是任意图片），
 * 而交回图的那个编辑器入口别的应用打不开。
 */
@RunWith(RobolectricTestRunner::class)
class PickImageIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun handlers(intent: Intent) = context.packageManager
        .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        .map { it.activityInfo.name }

    @Test
    fun `apps asking for an image find the picker`() {
        val picker = PickImageActivity::class.java.name
        assertThat(handlers(Intent(Intent.ACTION_PICK).setType("image/*"))).contains(picker)
        assertThat(
            handlers(
                Intent(Intent.ACTION_PICK)
                    .setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "vnd.android.cursor.dir/image"),
            ),
        ).contains(picker)
        assertThat(
            handlers(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")),
        ).contains(picker)
    }

    @Test
    fun `apps asking for something other than an image do not`() {
        val picker = PickImageActivity::class.java.name
        assertThat(handlers(Intent(Intent.ACTION_PICK).setType("video/*"))).doesNotContain(picker)
        assertThat(handlers(Intent(Intent.ACTION_GET_CONTENT).setType("audio/*"))).doesNotContain(picker)
    }

    @Test
    fun `the editor entry that hands images back is not exported`() {
        val entry = context.packageManager.getActivityInfo(
            ComponentName(context.packageName, "${context.packageName}.ui.EditorForCaller"),
            0,
        )
        assertThat(entry.exported).isFalse()
        assertThat(entry.targetActivity).isEqualTo(EditorActivity::class.java.name)
    }
}
