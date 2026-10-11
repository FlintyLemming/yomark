package moe.flinty.yomark.engine.ppocr

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind.BIRTH_DATE
import moe.flinty.yomark.core.model.SensitiveKind.DATETIME
import moe.flinty.yomark.core.model.SensitiveKind.PASSPORT
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
 * 真模型、真图：读一张合成的护照资料页（tools/test-images/PassportPage.java 画的，内容全是编的）。
 *
 * 照着用户反馈的美国护照样张排版：字距拉开的全大写值、字段名在值上面、斜压的水印「SPECIMEN」、等宽的机读码。
 * 0.11.1 在这张图上一处都没认出来：「MA RLOW E」「AN NA B EAT RI C E」、机读码每个字之间都是空格，
 * 「Place of birth」被水印串进了另一行。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PassportOcrTest {

    /**
     * 识别一次要一两秒，整个类共用一份结果。在测试方法里第一次用到时才识别，不用 @BeforeClass：
     * 那时 Robolectric 的沙箱还没配好，这个类单独跑会报 BootstrapMethodError。
     */
    companion object {
        private var cached: List<TextLine>? = null
    }

    private val lines: List<TextLine>
        get() = cached ?: runBlocking {
            val bytes = PassportOcrTest::class.java.getResourceAsStream("/ppocr/passport-page.jpg")!!.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/jpeg"))
        }.also { cached = it }

    private val texts get() = lines.map { it.text }

    /** 字距宽的大写字不再被拆开；字段名和它下面的值各是一行，没被水印串到一起。 */
    @Test fun `wide capitals stay whole and labels stay off their values`() {
        assertThat(texts).containsAtLeast(
            "MARLOWE",
            "ANNA BEATRICE",
            "12 AUG 1974",
            "MINISTRY OF FOREIGN AFFAIRS",
            "Date of birth/Date de naissance/Fecha de nacimiento",
            "Place of birth/Lieu de naissance/Lugar de nacimiento",
        )
    }

    @Test fun `the name line of the machine readable zone reads without spaces`() {
        assertThat(texts).contains("P<UTOMARLOWE<<ANNA<BEATRICE<<<<<<<<<<<<<<<<<")
    }

    /** 候选框盖住的那几个字（去掉空格比：机读码第二行里还会有 OCR 补的空格）。 */
    private fun Candidate.text(): String {
        val b = quad.bounds()
        val inside = { l: TextLine -> l.elements.filter { e -> e.quad.bounds().let { b.contains(it.centerX(), it.centerY()) } } }
        val hit = inside(lines.maxBy { inside(it).size })
        return hit.joinToString("") { it.text }
    }

    @Test fun `the rules find everything on the page`(): Unit = runBlocking {
        val found = RuleClassifier(DefaultRuleSet.rules).classify(lines).map { it.kind to it.text() }
        // 机读码第二行 OCR 把国籍「UTO」读成了「UT0」，照样按校验位认出来；整行都遮
        val mrz = lines.filter { '<' in it.text }
        assertThat(mrz).hasSize(2)
        assertThat(found).containsExactlyElementsIn(
            listOf(
                PASSPORT to "X52819374",
                PERSON_NAME to "MARLOWE",
                PERSON_NAME to "ANNABEATRICE",
                BIRTH_DATE to "12AUG1974",
                POSTAL_ADDRESS to "ZENITHCITY,UTOPIA",
                DATETIME to "03MAR2021",
                DATETIME to "02MAR2031",
                // 幽灵像旁边那行小字：拿机读码里的生日对出来的
                BIRTH_DATE to "120874",
            ) + mrz.map { PASSPORT to it.text.filter { c -> c != ' ' } }
        )
    }

    /** 出生日期、姓名、护照号、出生地、机读码打码；签发日期、有效期只圈出。 */
    @Test fun `only the issue and expiry dates are left outlined`(): Unit = runBlocking {
        val outlined = RuleClassifier(DefaultRuleSet.rules).classify(lines).filter { !it.enabledByDefault }
        assertThat(outlined.map { it.kind to it.text() }).containsExactly(DATETIME to "03MAR2021", DATETIME to "02MAR2031")
    }
}
