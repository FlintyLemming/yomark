package moe.flinty.yomark.engine.ppocr

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.BIRTH_DATE
import moe.flinty.yomark.core.model.SensitiveKind.PERSON_NAME
import moe.flinty.yomark.core.model.SensitiveKind.POSTAL_ADDRESS
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型、真图：合成的身份证人像面（tools/test-images/IdCardPage.java）和驾驶证正页（DriverLicensePage.java），
 * 内容全是编的。姓名、出生日期、两行住址都要打码。
 *
 * 0.15.0 在身份证上漏了住址第二行的门牌（只遮了「6幢2单元501 室」）；驾驶证上字段名和值隔得远，OCR 拆成两行，
 * 姓名、住址第一行都没遮上。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdDocumentOcrTest {

    /** 识别一次要一两秒，整个类共用。不用 @BeforeClass，理由见 PassportOcrTest。 */
    companion object {
        private val cached = HashMap<String, List<TextLine>>()
    }

    private fun lines(res: String): List<TextLine> = cached.getOrPut(res) {
        runBlocking {
            val bytes = IdDocumentOcrTest::class.java.getResourceAsStream("/ppocr/$res")!!.readBytes()
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

    /** 打码的那几处：（类型, 字）。 */
    private fun masked(res: String): List<Pair<SensitiveKind, String>> = runBlocking {
        val lines = lines(res)
        RuleClassifier(DefaultRuleSet.rules).classify(lines).filter { it.enabledByDefault }.map { it.kind to it.text(lines) }
    }

    @Test fun `the id card front`() {
        assertThat(masked("id-card-front.jpg")).containsAtLeast(
            PERSON_NAME to "林知夏",
            BIRTH_DATE to "1993年7月18日",
            POSTAL_ADDRESS to "浙江省杭州市西湖区文三路",
            POSTAL_ADDRESS to "88号6幢2单元501室",
        )
    }

    @Test fun `the driving licence reads with its bilingual labels`() {
        val texts = lines("driver-license.jpg").map { it.text }
        assertThat(texts).containsAtLeast("姓名 Name", "住址 Address", "江苏省南京市玄武区北京东路", "41号3栋2单元602室", "1986-02-14")
    }

    @Test fun `the driving licence`() {
        assertThat(masked("driver-license.jpg")).containsAtLeast(
            PERSON_NAME to "周以宁",
            BIRTH_DATE to "1986-02-14",
            POSTAL_ADDRESS to "江苏省南京市玄武区北京东路",
            POSTAL_ADDRESS to "41号3栋2单元602室",
        )
        // TODO 集成身份证号规则后：证号「320105198602143729」也要打码（现在只按长数字串圈出）
    }
}
