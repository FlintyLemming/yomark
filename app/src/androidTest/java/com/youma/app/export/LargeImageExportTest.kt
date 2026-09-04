package com.youma.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImageLoader
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskPlan
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
import com.youma.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * spec §15.7：大图导出的内存上限。
 *
 * `EXPORT_MAX_PIXELS = 32 MP` 是一个工程估计值，需要实机确认「刚好压在上限上的图
 * 能不能导完而不 OOM」。32 MP 的 ARGB_8888 位图本身就是 128 MB，
 * 导出路径上同时活着源位图与输出位图，因此 largeHeap 是必须的。
 *
 * 源图刻意按 2 的幂之外的尺寸造，确保它落在「不降采样」那一档（32 MP 以内），
 * 量的就是最坏情况。
 */
@RunWith(AndroidJUnit4::class)
class LargeImageExportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class NoSink : ImageSink {
        var width = 0
        var height = 0
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri {
            width = bitmap.width
            height = bitmap.height
            return Uri.parse("content://fake/large")
        }
    }

    /** 5656×5656 = 31,990,336 px，刚好压在 EXPORT_MAX_PIXELS 之下，不触发降采样。 */
    private fun hugeSource(): File {
        val side = 5656
        val file = File(context.cacheDir, "huge-$side.jpg")
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(RectF(100f, 100f, 2000f, 900f), Paint().apply { color = Color.RED })
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return file
    }

    @Test
    fun exports_a_32_megapixel_image_at_full_resolution_without_running_out_of_memory() = runTest {
        val file = hugeSource()
        val sink = NoSink()
        val plan = MaskPlan(
            items = listOf(
                MaskItem(
                    "m1", Quad.fromRect(RectF(100f, 100f, 2000f, 900f)),
                    SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED,
                )
            ),
            style = MaskStyle.SOLID,
            options = MaskOptions(),
        )

        val outcome = Exporter(RendererRegistry.default(), WatermarkDrawer(), sink)
            .export(ExportRequest(file, "image/jpeg", plan, analysisScale = 1f, applyWatermark = true))

        assertThat(outcome).isInstanceOf(ExportOutcome.Success::class.java)
        val success = outcome as ExportOutcome.Success
        // 32 MP 以内不该被降采样：导出必须是原图分辨率（spec §9.2）
        assertThat(success.downscaled).isFalse()
        assertThat(sink.width.toLong() * sink.height.toLong())
            .isAtMost(SourceImageLoader.EXPORT_MAX_PIXELS.toLong())
        assertThat(sink.width).isEqualTo(5656)

        file.delete()
    }
}
