package moe.flinty.yomark.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.flinty.yomark.export.MediaStoreSink
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ACTION_SEND_MULTIPLE 的批量流在真机上的闭环（计划 07 Task 43 的实机清单）。
 *
 * 素材经 MediaStoreSink 写入，因此是**本应用自己创建的**媒体文件——
 * 读回来不需要任何权限，这条测试本身也就顺带守着零权限的承诺。
 */
@RunWith(AndroidJUnit4::class)
class BatchFlowTest {

    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sourceUris(count: Int): ArrayList<Uri> = runBlocking {
        val stamp = System.currentTimeMillis()
        ArrayList((1..count).map { i ->
            val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
            MediaStoreSink(context)
                .write(bmp, Bitmap.CompressFormat.JPEG, 95, "yomark-batch-src-$stamp-$i.jpg", "image/jpeg")
                .also { bmp.recycle() }
        })
    }

    @Test
    fun jumps_between_the_shared_images_on_the_filmstrip_and_asks_before_leaving_any_out() {
        val uris = sourceUris(3)
        val intent = Intent(context, EditorActivity::class.java).apply {
            action = Intent.ACTION_SEND_MULTIPLE
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        try {
            ActivityScenario.launch<EditorActivity>(intent).use {
                compose.onNodeWithContentDescription("第 1 张，共 3 张").assertIsSelected()
                compose.onNodeWithText("导出").assertIsDisplayed()

                // 不必按顺序：直接跳到最后一张
                compose.onNodeWithContentDescription("第 3 张，共 3 张").performClick()
                compose.onNodeWithContentDescription("第 3 张，共 3 张").assertIsSelected()

                // 中间那张一眼都没看过，导出前先问——批量不做无人值守
                compose.onNodeWithText("导出").performClick()
                compose.onNodeWithText("还有 1 张没看过").assertIsDisplayed()
                compose.onNodeWithText("去看看").performClick()
                compose.onNodeWithContentDescription("第 2 张，共 3 张").assertIsSelected()
            }
        } finally {
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }
}
