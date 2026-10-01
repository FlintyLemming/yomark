package com.youma.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.youma.app.core.geometry.Quad
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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExporterTest {

    @get:Rule val tmp = TemporaryFolder()

    /** 记下最后一次写出的位图与编码参数，替代真实的 MediaStore。 */
    private class FakeSink : ImageSink {
        var bitmap: Bitmap? = null
        var format: Bitmap.CompressFormat? = null
        var quality: Int = -1
        var mimeType: String? = null
        override suspend fun write(
            bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
            displayName: String, mimeType: String,
        ): Uri {
            this.bitmap = bitmap; this.format = format
            this.quality = quality; this.mimeType = mimeType
            return Uri.parse("content://fake/1")
        }
    }

    private fun writeSource(name: String, w: Int, h: Int, png: Boolean = false): File {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawRect(RectF(0f, 0f, w / 2f, h / 2f), Paint().apply { color = Color.RED })
        }
        val f = tmp.newFile(name)
        f.outputStream().use {
            bmp.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 100, it)
        }
        // 塞进一些必须被剥掉的元数据
        if (!png) ExifInterface(f.absolutePath).apply {
            setAttribute(ExifInterface.TAG_MODEL, "Pixel-Test")
            setAttribute(ExifInterface.TAG_DATETIME, "2026:09:02 10:00:00")
            setLatLong(37.4220, -122.0841)
            saveAttributes()
        }
        bmp.recycle()
        return f
    }

    private fun planWith(vararg items: MaskItem) = MaskPlan(items.toList(), MaskStyle.SOLID, MaskOptions())

    private fun maskedItem(l: Float, t: Float, r: Float, b: Float, state: MaskState = MaskState.MASKED) =
        MaskItem("i-$l", Quad.fromRect(RectF(l, t, r, b)), SensitiveKind.MANUAL, DetectorSource.MANUAL, state)

    private fun exporter(sink: ImageSink) =
        Exporter(RendererRegistry.default(), WatermarkDrawer(), sink)

    @Test
    fun `output keeps the original resolution not the analysis resolution`() = runTest {
        val sink = FakeSink()
        val src = writeSource("big.jpg", 4000, 3000)
        val out = exporter(sink).export(
            ExportRequest(src, "image/jpeg", planWith(), analysisScale = 0.512f, applyWatermark = false)
        )
        assertThat(out).isInstanceOf(ExportOutcome.Success::class.java)
        assertThat(sink.bitmap!!.width).isEqualTo(4000)
        assertThat(sink.bitmap!!.height).isEqualTo(3000)
    }

    @Test
    fun `mask quads are scaled back to original coordinates`() = runTest {
        val sink = FakeSink()
        val src = writeSource("scale.jpg", 2000, 2000)
        // 分析图是 500x500（scale=0.25），遮罩画在 (100,100)-(200,200)
        // 反算后应覆盖原图的 (400,400)-(800,800)
        exporter(sink).export(
            ExportRequest(src, "image/jpeg", planWith(maskedItem(100f, 100f, 200f, 200f)), 0.25f, false)
        )
        val bmp = sink.bitmap!!
        assertThat(bmp.getPixel(600, 600)).isEqualTo(MaskOptions.SKY_BLUE)
        assertThat(bmp.getPixel(300, 300)).isNotEqualTo(MaskOptions.SKY_BLUE)
        assertThat(bmp.getPixel(900, 900)).isNotEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `outlined items are not drawn`() = runTest {
        val sink = FakeSink()
        val src = writeSource("outlined.jpg", 400, 400)
        exporter(sink).export(
            ExportRequest(src, "image/jpeg", planWith(maskedItem(50f, 50f, 150f, 150f, MaskState.OUTLINED)), 1f, false)
        )
        assertThat(sink.bitmap!!.getPixel(100, 100)).isNotEqualTo(MaskOptions.SKY_BLUE)
    }

    @Test
    fun `png source stays png and jpeg source becomes jpeg q95`() = runTest {
        val jpegSink = FakeSink()
        exporter(jpegSink).export(ExportRequest(writeSource("a.jpg", 200, 200), "image/jpeg", planWith(), 1f, false))
        assertThat(jpegSink.format).isEqualTo(Bitmap.CompressFormat.JPEG)
        assertThat(jpegSink.quality).isEqualTo(95)
        assertThat(jpegSink.mimeType).isEqualTo("image/jpeg")

        val pngSink = FakeSink()
        exporter(pngSink).export(ExportRequest(writeSource("a.png", 200, 200, png = true), "image/png", planWith(), 1f, false))
        assertThat(pngSink.format).isEqualTo(Bitmap.CompressFormat.PNG)
        assertThat(pngSink.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `encoded output carries no exif at all`() = runTest {
        val sink = FakeSink()
        val src = writeSource("exif.jpg", 300, 300)
        exporter(sink).export(ExportRequest(src, "image/jpeg", planWith(), 1f, false))

        val bytes = ByteArrayOutputStream().also { sink.bitmap!!.compress(sink.format!!, sink.quality, it) }.toByteArray()
        val exif = ExifInterface(bytes.inputStream())

        assertThat(exif.latLong).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME)).isNull()
        // 文件里根本没有 EXIF 段——这才是「不携带任何 EXIF」的真正断言。
        // 不能断言 TAG_ORIENTATION 为 null：androidx 的 ExifInterface 会从 JPEG 的 SOF 段
        // 合成 ImageWidth/ImageLength，并把缺失的方向报成 ORIENTATION_UNDEFINED(0)，
        // 那是库的默认值，不是文件里的数据。
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("Exif\u0000\u0000")
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            .isEqualTo(ExifInterface.ORIENTATION_UNDEFINED)
    }

    @Test
    fun `watermark is drawn only when requested`() = runTest {
        val withMark = FakeSink()
        exporter(withMark).export(ExportRequest(writeSource("w1.jpg", 600, 600), "image/jpeg", planWith(), 1f, true))
        val without = FakeSink()
        exporter(without).export(ExportRequest(writeSource("w2.jpg", 600, 600), "image/jpeg", planWith(), 1f, false))

        // 右下角水印落点：有水印那张与无水印那张必然有像素差异
        var differs = false
        for (x in 520 until 590 step 5) for (y in 520 until 590 step 5) {
            if (withMark.bitmap!!.getPixel(x, y) != without.bitmap!!.getPixel(x, y)) differs = true
        }
        assertThat(differs).isTrue()
    }

    @Test
    fun `output above 32 megapixels reports downscaling`() = runTest {
        val sink = FakeSink()
        val src = writeSource("huge.jpg", 8000, 5000)          // 40 MP
        val out = exporter(sink).export(ExportRequest(src, "image/jpeg", planWith(), 1f, false)) as ExportOutcome.Success
        assertThat(out.downscaled).isTrue()
        assertThat(out.width.toLong() * out.height).isAtMost(32_000_000L)
    }

    @Test
    fun `a broken source file yields Failure rather than throwing`() = runTest {
        val broken = tmp.newFile("broken.jpg").apply { writeText("not an image") }
        val out = exporter(FakeSink()).export(ExportRequest(broken, "image/jpeg", planWith(), 1f, false))
        assertThat(out).isInstanceOf(ExportOutcome.Failure::class.java)
    }
}
