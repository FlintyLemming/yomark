package com.dama.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.net.toUri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dama.app.core.geometry.Quad
import com.dama.app.core.image.ImageIntake
import com.dama.app.core.image.SourceImageLoader
import com.dama.app.core.model.DetectorSource
import com.dama.app.core.model.MaskItem
import com.dama.app.core.model.MaskOptions
import com.dama.app.core.model.MaskPlan
import com.dama.app.core.model.MaskState
import com.dama.app.core.model.MaskStyle
import com.dama.app.core.model.SensitiveKind
import com.dama.app.engine.mlkit.MlKitTextRecognizer
import com.dama.app.render.RendererRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 不可还原（spec §12 M4、§13）。
 *
 * 判据用 OCR：如果 ML Kit 还能从导出图里读出那串卡号，那它显然没被遮住。
 * 这守得住「盖漏了」这类错误；守不住「像素化块太小可被算法还原」——
 * 后者必须人工拿去码工具验，见 Step 3。
 */
@RunWith(AndroidJUnit4::class)
class IrreversibilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val secret = "4111111111111111"

    private fun sourceWithSecret(): Pair<File, RectF> {
        val bmp = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 96f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        Canvas(bmp).apply {
            drawColor(Color.rgb(245, 245, 247))
            drawText(secret, 60f, 240f, paint)
        }
        val w = paint.measureText(secret)
        val rect = RectF(50f, 240f + paint.ascent() - 10f, 70f + w, 240f + paint.descent() + 10f)

        val f = File(context.cacheDir, "irreversible-src.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f to rect
    }

    private suspend fun exportWith(style: MaskStyle): Bitmap {
        val (src, rect) = sourceWithSecret()
        val intake = ImageIntake(context)
        val taken = intake.copyToPrivate(src.toUri())
        val image = SourceImageLoader.loadForAnalysis(taken.file, taken.mimeType)

        val plan = MaskPlan(
            items = listOf(MaskItem(
                "s", Quad.fromRect(RectF(
                    rect.left * image.scale, rect.top * image.scale,
                    rect.right * image.scale, rect.bottom * image.scale,
                )),
                SensitiveKind.PAYMENT_CARD, DetectorSource.RULE, MaskState.MASKED,
            )),
            style = style,
            options = MaskOptions(),
        )

        var captured: Bitmap? = null
        val sink = object : ImageSink {
            override suspend fun write(
                bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int,
                displayName: String, mimeType: String,
            ): android.net.Uri {
                // 走一遍真实的编码-解码往返：JPEG 压缩后是否仍然遮住
                val bytes = java.io.ByteArrayOutputStream()
                    .also { bitmap.compress(format, quality, it) }.toByteArray()
                captured = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                return android.net.Uri.parse("content://fake/1")
            }
        }
        Exporter(RendererRegistry.default(), WatermarkDrawer(), sink)
            .export(ExportRequest(taken.file, taken.mimeType, plan, image.scale, applyWatermark = false))
        intake.clear()
        return captured!!
    }

    private suspend fun ocrText(bmp: Bitmap): String =
        MlKitTextRecognizer()
            .recognize(com.dama.app.core.image.SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
            .joinToString(" ") { it.text }

    @Test
    fun solid_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.SOLID))).doesNotContain("4111")
    }

    @Test
    fun pixelate_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.PIXELATE))).doesNotContain("4111")
    }

    @Test
    fun emoji_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.EMOJI))).doesNotContain("4111")
    }

    @Test
    fun erase_output_is_unreadable() = runTest {
        assertThat(ocrText(exportWith(MaskStyle.ERASE))).doesNotContain("4111")
    }

    @Test
    fun marker_output_is_deliberately_still_readable() {
        // 马克笔的定位就是「仅标记、不遮蔽」。这条测试守着这个语义：
        // 如果哪天有人把马克笔改成不透明的，UI 上的标注就撒谎了。
        kotlinx.coroutines.runBlocking {
            val text = ocrText(exportWith(MaskStyle.MARKER))
            assertThat(text).contains("4111")
        }
    }
}
