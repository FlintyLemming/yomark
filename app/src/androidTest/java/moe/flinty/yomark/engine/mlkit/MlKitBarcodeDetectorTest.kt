package moe.flinty.yomark.engine.mlkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.image.SourceImageLoader
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.BarcodeOption
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
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
        }
    }

    @Test
    fun a_decoded_code_is_masked_by_default() = runTest {
        val out = MlKitBarcodeDetector().detect(qrImage())
        assertThat(out).hasSize(1)
        assertThat(out.single().enabledByDefault).isTrue()
    }

    /**
     * 手画的条纹 ML Kit 认得出是条码、解不出内容：宽松模式只圈出，严格模式不报。
     * 疑似条码的 rawValue 是空串，以前按 != null 判，它被当成解出来的，默认打码。
     */
    @Test
    fun an_undecodable_code_is_only_outlined() = runTest {
        val loose = MlKitBarcodeDetector(BarcodeOption.LOOSE).detect(barcodeImage())
        assertThat(loose).isNotEmpty()
        loose.forEach { assertThat(it.enabledByDefault).isFalse() }
        assertThat(MlKitBarcodeDetector(BarcodeOption.STRICT).detect(barcodeImage())).isEmpty()
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

    /**
     * 4096×3072 的「照片」上一个 112 px 宽的二维码，码的左上角在原图 (2500, 1100)。[orientation] 是写进文件的 EXIF 方向：
     * 像素照原样存，转正交给读图的一方。
     */
    private fun photoWithCode(orientation: Int): File {
        val qr = context.assets.open("barcodes/qr-sample.png").use { BitmapFactory.decodeStream(it) }
        val photo = Bitmap.createBitmap(4096, 3072, Bitmap.Config.ARGB_8888)
        Canvas(photo).apply {
            drawColor(Color.rgb(236, 236, 236))
            drawBitmap(Bitmap.createScaledBitmap(qr, 112, 112 * qr.height / qr.width, true), 2500f, 1100f, null)
        }
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "photo-$orientation.jpg")
        file.outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (orientation != ExifInterface.ORIENTATION_NORMAL) {
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "$orientation"); saveAttributes() }
        }
        return file
    }

    /**
     * 照片降到 2048 宽识别，码在识别图上只剩 56 px，ML Kit 解不出；回原图分块扫，每块按原分辨率看，解得出。
     * 框换回识别图坐标：码的中心在原图 (2556, 1156)，识别图上是一半；EXIF 转了 90° 的，先转正再取一半。
     */
    @Test
    fun a_small_code_in_a_large_photo_is_found_on_the_original() = runTest {
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to PointF(1278f, 578f),
            ExifInterface.ORIENTATION_ROTATE_90 to PointF((3072 - 1156) / 2f, 2556 / 2f),
        )
        for ((orientation, center) in expected) {
            val image = SourceImageLoader.loadForAnalysis(photoWithCode(orientation), "image/jpeg")
            val detector = MlKitBarcodeDetector(BarcodeOption.STRICT)
            assertWithMessage("$orientation 只扫识别图").that(detector.detect(image.copy(original = null))).isEmpty()

            val out = detector.detect(image)
            assertWithMessage("$orientation").that(out).hasSize(1)
            val b = out.single().quad.bounds()
            assertWithMessage("$orientation x").that(b.centerX()).isWithin(8f).of(center.x)
            assertWithMessage("$orientation y").that(b.centerY()).isWithin(8f).of(center.y)
            assertWithMessage("$orientation").that(out.single().enabledByDefault).isTrue()
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
