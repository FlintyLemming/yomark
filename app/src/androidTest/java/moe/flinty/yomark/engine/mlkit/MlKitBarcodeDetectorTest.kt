package moe.flinty.yomark.engine.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitBarcodeDetectorTest {

    private val context = InstrumentationRegistry.getInstrumentation().context

    /**
     * 一张**真实可解码**的二维码（自造数据，内容是 https://example.com/yomark-test）。
     *
     * 计划原本只给了手绘条纹图，而手绘条纹大概率解不出格式——那样每个断言都在空列表上
     * 空转，测试全绿却什么都没验证。M3 的出口指标是「二维码 100%」，
     * 至少要有一张真能检出的图守着它。
     */
    private fun qrImage(): SourceImage {
        val bmp = context.assets.open("barcodes/qr-sample.png").use { BitmapFactory.decodeStream(it) }
        return SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
    }

    /**
     * 手绘一个 21x21 的 QR（version 1）太脆弱；改成画一个高对比的
     * Code 128 风格条形码 —— ML Kit 对纯竖条同样能检出格式。
     * 若这张合成图检不出，改用 assets 里的一张真实二维码截图。
     */
    private fun barcodeImage(): SourceImage {
        val w = 800; val h = 400
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK }
            // EAN-13 "4006381333931" 的近似条纹布局：宽窄交替，静区留足
            val widths = intArrayOf(3,1,1,2,3,2,1,1,3,1,2,2,1,3,1,1,2,1,3,2,1,1,3,2,1,2,3,1,1,2)
            var x = 120f
            widths.forEachIndexed { i, wUnit ->
                val bar = wUnit * 6f
                if (i % 2 == 0) drawRect(x, 80f, x + bar, 320f, ink)
                x += bar
            }
        }
        return SourceImage(bmp, 1f, w, h, "image/png")
    }

    private fun blank(): SourceImage {
        val bmp = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        return SourceImage(bmp, 1f, 400, 400, "image/png")
    }

    @Test
    fun a_blank_image_yields_no_barcodes() = runTest {
        assertThat(MlKitBarcodeDetector().detect(blank())).isEmpty()
    }

    @Test
    fun a_real_qr_code_is_detected() = runTest {
        assertThat(MlKitBarcodeDetector().detect(qrImage())).isNotEmpty()
    }

    @Test
    fun detected_barcodes_carry_the_right_kind_and_source() = runTest {
        val out = MlKitBarcodeDetector().detect(qrImage()) + MlKitBarcodeDetector().detect(barcodeImage())
        assertThat(out).isNotEmpty()
        out.forEach {
            assertThat(it.kind).isEqualTo(SensitiveKind.BARCODE)
            assertThat(it.source).isEqualTo(DetectorSource.BARCODE)
            assertThat(it.enabledByDefault).isTrue()
        }
    }

    @Test
    fun detector_does_not_throw_on_a_noisy_image() = runTest {
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            val p = Paint()
            for (x in 0 until 300 step 3) for (y in 0 until 300 step 3) {
                p.color = if ((x * y) % 7 < 3) Color.BLACK else Color.WHITE
                drawRect(x.toFloat(), y.toFloat(), x + 3f, y + 3f, p)
            }
        }
        MlKitBarcodeDetector().detect(SourceImage(bmp, 1f, 300, 300, "image/png"))
    }

    @Test
    fun quads_stay_inside_the_image() = runTest {
        val image = qrImage()
        val out = MlKitBarcodeDetector().detect(image)
        assertThat(out).isNotEmpty()
        out.forEach {
            val b = it.quad.bounds()
            assertThat(b.left).isAtLeast(-1f)
            assertThat(b.right).isAtMost(image.width + 1f)
        }
    }

    /** 倾斜的二维码：cornerPoints 必须比外接矩形更贴合，否则会盖住旁边的字。 */
    @Test
    fun a_rotated_qr_uses_corner_points_rather_than_the_bounding_box() = runTest {
        val src = qrImage().bitmap
        val canvasSize = 700
        val bmp = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            save()
            rotate(20f, canvasSize / 2f, canvasSize / 2f)
            drawBitmap(src, (canvasSize - src.width) / 2f, (canvasSize - src.height) / 2f, null)
            restore()
        }
        val out = MlKitBarcodeDetector().detect(SourceImage(bmp, 1f, canvasSize, canvasSize, "image/png"))
        assertThat(out).isNotEmpty()
        val quad = out.first().quad
        // 旋转 20° 的四边形，面积必须明显小于它的轴对齐外接矩形
        val bounds = quad.bounds()
        assertThat(quad.area()).isLessThan(bounds.width() * bounds.height() * 0.95f)
    }
}
