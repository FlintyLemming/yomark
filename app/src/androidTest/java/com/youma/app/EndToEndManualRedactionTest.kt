package com.youma.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.ImageIntake
import com.youma.app.core.image.SourceImageLoader
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskPlan
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKind
import com.youma.app.export.ExportOutcome
import com.youma.app.export.ExportRequest
import com.youma.app.export.Exporter
import com.youma.app.export.MediaStoreSink
import com.youma.app.export.WatermarkDrawer
import com.youma.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class EndToEndManualRedactionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun manual_redaction_round_trip_hides_the_secret_and_strips_metadata() = runTest {
        // 1. 造一张「有秘密」的源图：白底 + 一块红色秘密区
        val src = File(context.cacheDir, "e2e-src.jpg")
        val w = 1600; val h = 1200
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).let { bmp ->
            Canvas(bmp).apply {
                drawColor(Color.WHITE)
                drawRect(RectF(400f, 400f, 800f, 600f), Paint().apply { color = Color.RED })
            }
            src.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 100, it) }
            bmp.recycle()
        }
        ExifInterface(src.absolutePath).apply {
            setLatLong(37.4220, -122.0841)
            setAttribute(ExifInterface.TAG_MODEL, "Secret-Device")
            saveAttributes()
        }

        // 2. 走真实入口：私有副本 → 分析用解码
        val intake = ImageIntake(context)
        val taken = intake.copyToPrivate(src.toUri())
        val image = SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType)

        // 3. 在分析坐标系里把秘密区框住
        val f = image.scale
        val plan = MaskPlan(
            items = listOf(
                MaskItem(
                    "e2e", Quad.fromRect(RectF(400f * f, 400f * f, 800f * f, 600f * f)),
                    SensitiveKind.MANUAL, DetectorSource.MANUAL, MaskState.MASKED,
                )
            ),
            style = MaskStyle.SOLID,
            options = MaskOptions(),
        )

        // 4. 导出
        val exporter = Exporter(RendererRegistry.default(), WatermarkDrawer(), MediaStoreSink(context))
        val outcome = exporter.export(
            ExportRequest(taken.file, taken.mimeType, plan, image.scale, applyWatermark = true)
        )
        assertThat(outcome).isInstanceOf(ExportOutcome.Success::class.java)
        val uri = (outcome as ExportOutcome.Success).uri

        // 5. 读回来校验
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val out = BitmapFactory.decodeStream(input)!!
            assertThat(out.width).isEqualTo(w)            // 原图分辨率，不是分析分辨率
            assertThat(out.height).isEqualTo(h)
            assertThat(out.getPixel(600, 500)).isEqualTo(Color.BLACK)   // 秘密被遮住
            assertThat(Color.red(out.getPixel(100, 100))).isGreaterThan(200)  // 其余不变
            out.recycle()
        }
        context.contentResolver.openInputStream(uri)!!.use { input ->
            val exif = ExifInterface(input)
            assertThat(exif.latLong).isNull()
            assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
        }

        context.contentResolver.delete(uri, null, null)
        intake.clear()
    }
}
