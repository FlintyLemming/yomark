package moe.flinty.yomark.engine.ppocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect

/**
 * 拿测试资源里现成的图拼出来的页面，不画字（Robolectric 里没有中文字体）：
 * 字全是从物流页、身份证上剪下来的像素，每次拼出来一模一样。
 */
internal object TestPages {

    fun res(name: String): Bitmap {
        val bytes = TestPages::class.java.getResourceAsStream(name)!!.readBytes()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, true)
    }

    /**
     * 正着的表单上贴着一张顺时针转了 [cardDegrees]（90 的整数倍）的身份证人像面：上面两行剪自物流页
     * （「送至 长江路88号阳光新村5幢302室」「王小明 86-139****5678 号码保护中」），证件上的字比这两行长，整页的走向跟着证件。
     */
    fun formWithCard(cardDegrees: Int): Bitmap = withCard(cardDegrees, Rect(0, 650, 1080, 820), 300)

    /**
     * 一整页正着的物流页下面贴着同一张转过的身份证：横着的字比证件上的多得多，整页的走向跟着物流页，
     * 同聊天截图里发了一张转过的证件照。
     */
    fun screenshotWithCard(cardDegrees: Int): Bitmap = withCard(cardDegrees, Rect(0, 0, 1080, 1500), 1540)

    /** 物流页上剪下 [from] 那一块贴在页顶，下面 [cardTop] 处居中贴一张顺时针转了 [cardDegrees] 的身份证人像面。 */
    private fun withCard(cardDegrees: Int, from: Rect, cardTop: Int): Bitmap {
        val page = res("/ppocr/logistics-page.png")
        val front = res("/ppocr/id-card-front.jpg")
        val card = Bitmap.createBitmap(front, 0, 0, front.width, front.height, Matrix().apply { postRotate(cardDegrees.toFloat()) }, true)
        val out = Bitmap.createBitmap(1080, cardTop + card.height + 100, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)
            drawBitmap(page, from, Rect(0, 20, 1080, 20 + from.height()), null)
            drawBitmap(card, (1080 - card.width) / 2f, cardTop.toFloat(), null)
        }
        return out
    }
}
