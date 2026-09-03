package com.dama.app.eval

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.SensitiveKind

/**
 * 合成样本：渲染已知内容的文本行，真值就是渲染时的文本框。
 *
 * 它替代不了 spec §13 要求的 200 张真实截图——真实截图有压缩噪点、
 * 深色模式、异体字号、重叠 UI，这些都是合成图给不出的。
 * 合成样本的作用是让 harness 从今天起就能跑，回归时先在这里失败一次。
 */
object SyntheticSamples {

    private data class Entry(val text: String, val kind: SensitiveKind?)

    /** 每条：一行文本 + 它应当被识别成什么（null 表示这行不该产生候选）。 */
    private val ENTRIES = listOf(
        Entry("Card 4111111111111111", SensitiveKind.PAYMENT_CARD),
        Entry("Visa 5500005555555559", SensitiveKind.PAYMENT_CARD),
        Entry("IBAN DE89370400440532013000", SensitiveKind.IBAN),
        Entry("IBAN GB82WEST12345698765432", SensitiveKind.IBAN),
        Entry("SSN 123-45-6789", SensitiveKind.SSN),
        Entry("mail alice@example.com", SensitiveKind.EMAIL),
        Entry("mail bob.smith+tag@mail.co.uk", SensitiveKind.EMAIL),
        Entry("call +1 415 555 2671", SensitiveKind.PHONE),
        Entry("mac 00:1A:2B:3C:4D:5E", SensitiveKind.MAC_ADDR),
        Entry("key sk-aB3xQ9zL2mR7tK1vP0wY8n", SensitiveKind.API_KEY),
        Entry("host 192.168.1.10", SensitiveKind.IP_ADDR),
        Entry("open https://app.com/r/AbCdEf123", SensitiveKind.URL),
        Entry("ship 1Z12345E0205271688", SensitiveKind.TRACKING_NO),
        Entry("Total 42.50 USD", null),
        Entry("Delivered on Tuesday", null),
        Entry("Thanks for your order", null),
    )

    /**
     * 一张截图尺寸（1080×2400）的合成图，把全部 ENTRIES 竖排堆进去。
     * 延迟指标（spec §13：1080×2400 截图 < 800 ms）必须在这个尺寸上量，
     * generate() 产出的 1080×300 单行条测不出真实开销。
     */
    fun screenshot(): Sample {
        val bmp = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        val paint = textPaint()
        val canvas = Canvas(bmp).apply { drawColor(Color.WHITE) }
        val truth = mutableListOf<Annotation>()
        ENTRIES.forEachIndexed { i, entry ->
            val baseline = 120f + i * 140f
            canvas.drawText(entry.text, 40f, baseline, paint)
            entry.kind?.let {
                val w = paint.measureText(entry.text)
                truth += Annotation(it, RectF(40f, baseline + paint.ascent(), 40f + w, baseline + paint.descent()))
            }
        }
        return Sample("synthetic-screenshot", SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"), truth)
    }

    private fun textPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 64f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    fun generate(): List<Sample> = ENTRIES.mapIndexed { i, entry ->
        val bmp = Bitmap.createBitmap(1080, 300, Bitmap.Config.ARGB_8888)
        val paint = textPaint()
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawText(entry.text, 40f, 180f, paint)
        }

        // 真值：整行文本的外接框（宁可宽，覆盖判定用 IoU > 0 的相交）
        val width = paint.measureText(entry.text)
        val truth = entry.kind?.let {
            listOf(Annotation(it, RectF(40f, 180f + paint.ascent(), 40f + width, 180f + paint.descent())))
        } ?: emptyList()

        Sample(
            name = "synthetic-%02d".format(i),
            image = SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"),
            truth = truth,
        )
    }
}
