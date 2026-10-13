package moe.flinty.yomark.engine.ppocr

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型、真图：读一张合成的停车场照片（tools/test-images/PlatePhoto.java 画的，号码全是编的）。
 *
 * 用户那张车的照片上「苏B·1LE22」的「1」是一根竖条，OCR 读成「苏B·ILE22」，0.15.0 一处都没认。
 * 这张图上的蓝牌照着画了竖条的「1」；另有新能源、两块上下两行的牌子（黄色双层牌、日本），英国、法国、德国各一块。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlatePhotoOcrTest {

    /** 识别一次要一两秒，整个类共用一份结果。第一次用到时才识别，理由见 PassportOcrTest。 */
    companion object {
        private var cached: List<TextLine>? = null
    }

    private val lines: List<TextLine>
        get() = cached ?: runBlocking {
            val bytes = PlatePhotoOcrTest::class.java.getResourceAsStream("/ppocr/plate-photo.jpg")!!.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/jpeg"))
        }.also { cached = it }

    private val candidates: List<Candidate> get() = runBlocking { RuleClassifier(DefaultRuleSet.rules).classify(lines) }

    /** 一个框盖住了哪几行：行的中心落在框里。 */
    private fun covered(c: Candidate): List<String> {
        val b = c.quad.bounds()
        return lines.filter { l -> l.quad.bounds().let { b.contains(it.centerX(), it.centerY()) } }.map { it.text }
    }

    @Test fun `the plates are read as the camera shows them`() {
        assertThat(lines.map { it.text }).containsAtLeast(
            "苏B·ILE22", "粤B·D12345", "苏·E", "05863", "品川 330", "さ 12-34", "AB12 CDE", "AB-123-CD", "B MW 1234",
        )
    }

    /** 写法独特的打码，上下两行的合成一个框；德国的写法不够独特，只圈出。别的类型一处都不认。 */
    @Test fun `every plate gets one box`() {
        val found = candidates.map { covered(it) to it.enabledByDefault }
        assertThat(candidates.map { it.kind }.distinct()).containsExactly(SensitiveKind.LICENSE_PLATE)
        assertThat(found).containsExactly(
            listOf("苏B·ILE22") to true,
            listOf("粤B·D12345") to true,
            listOf("苏·E", "05863") to true,
            listOf("品川 330", "さ 12-34") to true,
            listOf("AB12 CDE") to true,
            listOf("AB-123-CD") to true,
            listOf("B MW 1234") to false,
        )
    }
}
