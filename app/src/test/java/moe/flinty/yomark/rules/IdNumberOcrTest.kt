package moe.flinty.yomark.rules

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind.ID_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.LICENSE_PLATE
import moe.flinty.yomark.core.model.SensitiveKind.LONG_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.PAYMENT_CARD
import moe.flinty.yomark.core.model.SensitiveKind.VIN
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型、真图：合成身份证（tools/test-images/IdCardPage.java 画的，内容全是编的）上的证件号码。
 *
 * 0.15.0 上正面的号码只圈成「长数字串」，打码要用户自己点。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdNumberOcrTest {

    /** 识别一次要一两秒，整个类共用。同 PassportOcrTest，不用 @BeforeClass。 */
    companion object {
        private val cache = HashMap<String, List<TextLine>>()
    }

    private fun lines(res: String): List<TextLine> = cache.getOrPut(res) {
        runBlocking {
            val bytes = IdNumberOcrTest::class.java.getResourceAsStream("/ppocr/$res")!!.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/jpeg"))
        }
    }

    /** 候选框盖住的那几个字，去掉空格。 */
    private fun Candidate.text(lines: List<TextLine>): String {
        val b = quad.bounds()
        val inside = { l: TextLine -> l.elements.filter { e -> e.quad.bounds().let { b.contains(it.centerX(), it.centerY()) } } }
        return inside(lines.maxBy { inside(it).size }).joinToString("") { it.text }
    }

    private fun numbers(res: String) = runBlocking {
        val lines = lines(res)
        RuleClassifier(DefaultRuleSet.rules).classify(lines)
            .filter { it.kind in setOf(ID_NUMBER, PAYMENT_CARD, LONG_NUMBER, LICENSE_PLATE, VIN) }
            .map { Triple(it.kind, it.text(lines), it.enabledByDefault) }
    }

    @Test fun `the number on the front is an id number and masked`() {
        assertThat(numbers("id-card-front.jpg")).containsExactly(Triple(ID_NUMBER, "330106199307180423", true))
    }

    /** 背面的「有效期限 2015.08.20-2035.08.20」不是银行卡的有效期，签发机关里也没有号码。 */
    @Test fun `nothing on the back`() {
        assertThat(numbers("id-card-back.jpg")).isEmpty()
    }

    @Test fun `the number in the photo`() {
        assertThat(numbers("id-card-photo.jpg")).contains(Triple(ID_NUMBER, "330106199307180423", true))
    }
}
