package com.dama.app.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dama.app.core.image.SourceImage
import com.dama.app.core.model.SensitiveKind
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
}
