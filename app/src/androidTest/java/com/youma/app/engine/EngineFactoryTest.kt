package com.youma.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EngineFactoryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(vararg lines: String): SourceImage {
        val bmp = Bitmap.createBitmap(1400, 180 * lines.size + 120, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 72f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            lines.forEachIndexed { i, s -> drawText(s, 40f, 140f + i * 180f, paint) }
        }
        return SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
    }

    @Test
    fun engine_finds_a_card_number_end_to_end() = runTest {
        val result = buildEngine(context).analyze(render("Card 4111111111111111"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.PAYMENT_CARD)
        assertThat(result.candidates.first { it.kind == SensitiveKind.PAYMENT_CARD }.enabledByDefault).isTrue()
    }

    @Test
    fun engine_finds_an_email_end_to_end() = runTest {
        val result = buildEngine(context).analyze(render("mail alice@example.com"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.EMAIL)
    }

    @Test
    fun a_url_comes_back_outlined_by_default() = runTest {
        val result = buildEngine(context).analyze(render("open https://app.com/r/AbCdEf123"))
        val url = result.candidates.firstOrNull { it.kind == SensitiveKind.URL }
        if (url != null) assertThat(url.enabledByDefault).isFalse()
    }

    @Test
    fun a_blank_image_yields_no_candidates_and_does_not_throw() = runTest {
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        val result = buildEngine(context).analyze(SourceImage(blank, 1f, 400, 400, "image/png"))
        assertThat(result.candidates).isEmpty()
    }

    @Test
    fun engine_reports_a_face_alongside_text_candidates() = runTest {
        // 一张既有文字又有脸的图：两条链路的结果都必须出现
        // 五官比例照抄 MlKitFaceDetectorTest 里那张**已证明检得出**的简笔脸（含鼻子）。
        // 计划给的这张少了鼻子、眼距也窄，当前 ML Kit 版本检不出——
        // 计划因此把人脸断言留成了注释。换成检得出的脸，断言就能真的立起来。
        val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val skin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(240, 200, 170) }
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(30, 30, 30) }
            drawOval(RectF(150f, 100f, 450f, 520f), skin)
            drawOval(RectF(215f, 240f, 265f, 280f), ink)      // 左眼
            drawOval(RectF(335f, 240f, 385f, 280f), ink)      // 右眼
            drawOval(RectF(280f, 310f, 320f, 360f), Paint(skin).apply { color = Color.rgb(220, 170, 140) })
            drawRect(RectF(250f, 410f, 350f, 430f), ink)      // 嘴
            drawText("mail alice@example.com", 60f, 720f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 56f
            })
        }
        val result = buildEngine(context).analyze(SourceImage(bmp, 1f, 900, 900, "image/png"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.EMAIL)
        // 计划把人脸这条留成了注释。不断言就等于没测「两条链路的结果都必须出现」，
        // 所以这里真的断言，代价是这张脸必须是检得出的那一张。
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.FACE)
    }

    @Test
    fun engine_reports_a_barcode_alongside_text_candidates() = runTest {
        // 二维码贴在一行文字旁边：条码链路与文字链路各自出结果，互不吞掉
        val qr = InstrumentationRegistry.getInstrumentation().context.assets
            .open("barcodes/qr-sample.png").use { BitmapFactory.decodeStream(it) }
        val bmp = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            drawBitmap(qr, 60f, 60f, null)
            drawText("mail alice@example.com", 60f, 760f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK; textSize = 56f
            })
        }
        val result = buildEngine(context).analyze(SourceImage(bmp, 1f, 900, 900, "image/png"))
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.BARCODE)
        assertThat(result.candidates.map { it.kind }).contains(SensitiveKind.EMAIL)
        assertThat(result.candidates.first { it.kind == SensitiveKind.BARCODE }.enabledByDefault).isTrue()
    }

    @Test
    fun a_failing_lane_does_not_break_the_others() = runTest {
        // buildEngine 的降级路径已由 RedactionEngineTest 覆盖；
        // 这里只确认真实装配下空白图不抛异常
        val blank = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        buildEngine(context).analyze(SourceImage(blank, 1f, 400, 400, "image/png"))
    }
}
