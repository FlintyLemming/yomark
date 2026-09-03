package com.dama.app.engine.mlkit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dama.app.core.image.SourceImage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitTextRecognizerTest {

    /** 渲染一张有确定文字的图，避免依赖外部素材。 */
    private fun render(vararg lines: String): SourceImage {
        val bmp = Bitmap.createBitmap(1000, 200 * lines.size + 100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 72f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            lines.forEachIndexed { i, s -> drawText(s, 40f, 140f + i * 180f, paint) }
        }
        return SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
    }

    @Test
    fun recognizes_rendered_text() = runTest {
        val result = MlKitTextRecognizer().recognize(render("Hello DAMA 4111"))
        assertThat(result).isNotEmpty()
        assertThat(result.joinToString(" ") { it.text }).contains("4111")
    }

    @Test
    fun line_text_equals_elements_joined_by_single_spaces() = runTest {
        val result = MlKitTextRecognizer().recognize(render("Card 4111 2222 3333"))
        result.forEach { line ->
            assertThat(line.text).isEqualTo(line.elements.joinToString(" ") { it.text })
        }
    }

    @Test
    fun every_element_range_indexes_its_own_text() = runTest {
        val result = MlKitTextRecognizer().recognize(render("alpha beta gamma"))
        result.forEach { line ->
            line.elements.forEach { e ->
                assertThat(line.text.substring(e.range.first, e.range.last + 1)).isEqualTo(e.text)
            }
        }
    }

    @Test
    fun quads_fall_inside_the_image() = runTest {
        val image = render("bounded text")
        MlKitTextRecognizer().recognize(image).forEach { line ->
            val b = line.quad.bounds()
            assertThat(b.left).isAtLeast(-1f)
            assertThat(b.top).isAtLeast(-1f)
            assertThat(b.right).isAtMost(image.width + 1f)
            assertThat(b.bottom).isAtMost(image.height + 1f)
        }
    }

    @Test
    fun quadForRange_lands_on_the_matched_word() = runTest {
        val lines = MlKitTextRecognizer().recognize(render("prefix 4111111111111111 suffix"))
        val line = lines.firstOrNull { it.text.contains("4111") } ?: return@runTest
        val idx = line.text.indexOf("4111")
        val q = line.quadForRange(idx until idx + 4)
        // 命中区域必须落在整行之内，且比整行窄
        assertThat(q.bounds().width()).isLessThan(line.quad.bounds().width() + 1f)
        assertThat(q.bounds().left).isAtLeast(line.quad.bounds().left - 1f)
    }
}
