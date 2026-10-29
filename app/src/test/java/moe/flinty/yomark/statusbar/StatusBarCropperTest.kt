package moe.flinty.yomark.statusbar

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.CropFinding
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 真模型：PP-OCR 读出来的行加上位图，走 [StatusBarCropper] 这一层。规则的细账在 [StatusBarTest] 的合成页上。
 *
 * coloros-top.png 是 ColorOS 手机上的一张真截图（Yomark 编辑器）顶上的 280 行：状态栏「14:21」、通知图标、网速、信号、电池，
 * 底下是编辑器的顶栏。摆回 1080×2376 的整页上读（长度按短边量，只给顶上一截量不对）；
 * 胶囊是照着流体云的样子画上去的黑色圆角条。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatusBarCropperTest {

    /** 识别一次要一两秒，整个类共用。 */
    companion object {
        private val cached = HashMap<String, Pair<SourceImage, List<TextLine>>>()
    }

    /**
     * 读一张测试图：[page] 不为 null 时把它摆到这么高的整页顶上（页面其余部分涂成图上最底下那一行的颜色），
     * 在原尺寸上画 [extra]（胶囊），[jpeg] 时再压一遍 JPEG，最后按 [scale] 缩（0.5 是出厂的识别分辨率：1080 宽的截图缩到 540）。
     */
    private fun read(
        res: String,
        scale: Float = 1f,
        page: Int? = null,
        jpeg: Boolean = false,
        key: String = "",
        extra: (Canvas) -> Unit = {},
    ): Pair<SourceImage, List<TextLine>> = cached.getOrPut("$res@$scale/$page/$jpeg/$key") {
        val bytes = StatusBarCropperTest::class.java.getResourceAsStream(res)!!.readBytes()
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val full = Bitmap.createBitmap(decoded.width, page ?: decoded.height, Bitmap.Config.ARGB_8888)
        Canvas(full).apply {
            drawColor(decoded.getPixel(decoded.width / 2, decoded.height - 1))
            drawBitmap(decoded, 0f, 0f, null)
            extra(this)
        }
        val shot = if (!jpeg) full else ByteArrayOutputStream().use {
            full.compress(Bitmap.CompressFormat.JPEG, 75, it)
            BitmapFactory.decodeByteArray(it.toByteArray(), 0, it.size()).copy(Bitmap.Config.ARGB_8888, false)
        }
        val bmp = if (scale == 1f) shot else {
            Bitmap.createScaledBitmap(shot, (shot.width * scale).roundToInt(), (shot.height * scale).roundToInt(), true)
        }
        val image = SourceImage(bmp, scale, shot.width, shot.height, "image/png")
        image to runBlocking { PaddleTextRecognizer(ApplicationProvider.getApplicationContext()).recognize(image) }
    }

    private fun find(read: Pair<SourceImage, List<TextLine>>): CropFinding =
        runBlocking { StatusBarCropper().find(read.first, read.second) }

    /** 流体云那样的黑色圆角条：两头是半圆，[top]、[bottom] 是原图（1080 宽）上的行。 */
    private fun capsule(top: Float, bottom: Float, width: Float = 260f): (Canvas) -> Unit = { canvas ->
        val r = (bottom - top) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() }
        canvas.drawRoundRect(RectF(540f - width / 2f, top, 540f + width / 2f, bottom), r, r, paint)
    }

    @Test fun `a ColorOS status bar is cut at twice the clock's middle`() {
        val full = find(read("/statusbar/coloros-top.png", page = 2376))
        assertWithMessage("${full.notes}").that(full.top).isIn(range(114f, 124f))
        val half = find(read("/statusbar/coloros-top.png", scale = 0.5f, page = 2376))
        assertWithMessage("${half.notes}").that(half.top).isIn(range(57f, 62f))
    }

    /** 收着的胶囊只围着摄像头，在状态栏里头：照样按状态栏裁。 */
    @Test fun `a small capsule inside the status bar changes nothing`() {
        val found = find(read("/statusbar/coloros-top.png", scale = 0.5f, page = 2376, key = "small", extra = capsule(24f, 100f, 150f)))
        assertWithMessage("${found.notes}").that(found.top).isIn(range(57f, 62f))
    }

    /** 展开的胶囊比时间高得多，下沿伸出了状态栏：裁到它底下，一点黑边都不留。 */
    @Test fun `a capsule hanging out of the status bar is cut away with it`() {
        listOf(1f, 0.5f).forEach { scale ->
            val found = find(read("/statusbar/coloros-top.png", scale = scale, page = 2376, key = "big", extra = capsule(18f, 150f)))
            val bottom = 150f * scale
            assertWithMessage("缩放 $scale：${found.notes}").that(found.top).isIn(range(bottom, bottom + 6f * scale + 3f))
        }
    }

    /** 截图存成 JPEG：胶囊边上一圈糊开的灰边也裁掉。 */
    @Test fun `a capsule in a JPEG screenshot is cut away with its fringe`() {
        val found = find(read("/statusbar/coloros-top.png", scale = 0.5f, page = 2376, jpeg = true, key = "big", extra = capsule(18f, 150f)))
        assertWithMessage("${found.notes}").that(found.top).isIn(range(75f, 81f))
    }

    /**
     * 上架截图、README 里的截图：状态栏 100 高（1080 宽），和顶栏一个颜色，按估的裁。
     * 订单页的时间换了字体、字心高了两三个像素，估在 95：留下的几行和顶栏一个颜色，看不出来。
     */
    @Test fun `store and readme screenshots lose their status bars`() {
        listOf("/play/chat-page.png", "/readme/order-page.png", "/ppocr/descenders-chat.png").forEach { res ->
            val found = find(read(res))
            assertWithMessage("$res：${found.notes}").that(found.top).isIn(range(94f, 104f))
        }
    }

    /** 物流页上的「16:08」是列表里的时间，右边没有图标；拍的证件照上根本没有时间。 */
    @Test fun `pages without a status bar are not cut`() {
        assertThat(find(read("/ppocr/logistics-page.png")).top).isNull()
        assertThat(find(read("/ppocr/id-card-photo.jpg")).top).isNull()
    }

    /** 页面转了直角（竖着拍的、转过的图）：状态栏不在图顶上，不找。 */
    @Test fun `a page turned sideways is not looked at`() {
        val (image, lines) = read("/statusbar/coloros-top.png", page = 2376)
        val turned = lines.map { it.turned(image.height.toFloat()) }
        val found = runBlocking { StatusBarCropper().find(image, turned) }
        assertThat(found.top).isNull()
    }

    private fun range(lo: Float, hi: Float) = com.google.common.collect.Range.closed(lo, hi)

    /** 整行连同每个字顺时针转 90°（图高 [h]）：竖着读的页。 */
    private fun TextLine.turned(h: Float): TextLine {
        fun PointF.turn() = PointF(h - y, x)
        fun Quad.turn() = Quad(p0.turn(), p1.turn(), p2.turn(), p3.turn())
        return copy(quad = quad.turn(), elements = elements.map { it.copy(quad = it.quad.turn()) }, segments = segments.map { it.turn() })
    }
}
