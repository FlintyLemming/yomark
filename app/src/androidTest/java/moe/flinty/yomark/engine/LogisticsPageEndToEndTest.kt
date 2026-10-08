package moe.flinty.yomark.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.mlkit.MlKitTextRecognizer
import moe.flinty.yomark.engine.mlkit.TextScript
import moe.flinty.yomark.rules.HanView
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 菜鸟快递详情页的收件人块，走真的中文识别器（单测版见 LogisticsPageRegressionTest）。
 *
 * 单测只能**假设**中文识别器怎么切 element——切到词、还是切到字——两种都覆盖了。
 * 真机上到底是哪一种，这里把每行的 element 打到 logcat（YOMARK-OCR），顺便断言
 * 不管哪一种，规则都认得出姓名、打了星的电话、地址和取件码。
 */
@RunWith(AndroidJUnit4::class)
class LogisticsPageEndToEndTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun render(vararg lines: String): SourceImage {
        val bmp = Bitmap.createBitmap(1600, 150 * lines.size + 100, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            drawColor(Color.WHITE)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 60f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            }
            lines.forEachIndexed { i, s -> drawText(s, 40f, 120f + i * 150f, paint) }
        }
        return SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png")
    }

    private val page = render(
        "送至 祁门路33号四方新村23幢605室",
        "王小明 186****3392",
        "取件码 1-58908",
    )

    @Test
    fun chinese_lines_read_back_as_contiguous_text_for_the_rules() = runTest {
        val lines = MlKitTextRecognizer(TextScript.CHINESE).recognize(page)
        lines.forEach { line ->
            println("YOMARK-OCR elements=${line.elements.joinToString("|") { it.text }} view=${HanView.of(line.text).text}")
        }
        val views = lines.joinToString("\n") { HanView.of(it.text).text }
        assertThat(views).contains("祁门路")
        assertThat(views).contains("取件码")
    }

    @Test
    fun the_recipient_block_is_found_end_to_end() = runTest {
        val kinds = buildEngine(context).analyze(page).candidates
            .filter { it.enabledByDefault }
            .map { it.kind }
        assertThat(kinds).containsAtLeast(
            SensitiveKind.POSTAL_ADDRESS,
            SensitiveKind.PERSON_NAME,
            SensitiveKind.PHONE,
            SensitiveKind.PICKUP_CODE,
        )
    }
}
