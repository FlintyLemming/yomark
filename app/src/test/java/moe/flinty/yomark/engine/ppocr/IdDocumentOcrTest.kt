package moe.flinty.yomark.engine.ppocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.BIRTH_DATE
import moe.flinty.yomark.core.model.SensitiveKind.DATETIME
import moe.flinty.yomark.core.model.SensitiveKind.ID_NUMBER
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
 * 真模型、真图、出厂规则表，从像素到打码：合成的身份证（tools/test-images/IdCardPage.java）和驾驶证正页
 * （DriverLicensePage.java），内容全是编的。正着、转了直角、竖着拍歪了、压着斜水印，打码的都正好是那几处：
 * 姓名、出生日期、两行住址、证件号码；字段名一个都不遮。
 *
 * 0.15.0 上身份证的号码只圈成「长数字串」、住址第二行只遮了门牌；竖着的图读成了一列列；驾驶证上字段名和值隔得远，
 * 姓名、住址第一行都没遮；水印压住的「出」「7 月」跟着水印读成了字母，出生日期遮不上。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class IdDocumentOcrTest {

    /** 识别一次要一两秒，整个类共用。不用 @BeforeClass，理由见 PassportOcrTest。 */
    companion object {
        private val cached = HashMap<String, List<TextLine>>()

        private val ID_CARD = setOf(
            PERSON_NAME to "林知夏",
            BIRTH_DATE to "1993年7月18日",
            POSTAL_ADDRESS to "浙江省杭州市西湖区文三路",
            POSTAL_ADDRESS to "88号6幢2单元501室",
            ID_NUMBER to "330106199307180423",
        )

        private val DRIVING_LICENCE = setOf(
            PERSON_NAME to "周以宁",
            BIRTH_DATE to "1986-02-14",
            POSTAL_ADDRESS to "江苏省南京市玄武区北京东路",
            POSTAL_ADDRESS to "41号3栋2单元602室",
            ID_NUMBER to "320105198602143729",
        )

        /** 驾驶证上初次领证日期和有效期限的起止：没有字段名说明是生日，归日期时间，只圈不遮。 */
        private val LICENCE_DATES = setOf(DATETIME to "2008-05-20", DATETIME to "2018-05-20", DATETIME to "2028-05-20")
    }

    /** [res] 顺时针转 [degrees] 度读出来的行。 */
    private fun lines(res: String, degrees: Int = 0): List<TextLine> = cached.getOrPut("$res@$degrees") {
        val src = TestPages.res("/ppocr/$res")
        val bmp = if (degrees == 0) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        runBlocking {
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
        }
    }

    /**
     * 候选框（斜的就按斜的量）在它自己那一行上盖住的字，去掉空格。哪一行看 id：「rule-规则-行号-起点-序号」。
     * 水印压着的页上框会盖到别的行上的字母，按行取才是规则认出来的那几个字。
     */
    private fun Candidate.text(lines: List<TextLine>): String =
        lines[id.split('-')[2].toInt()].elements
            .filter { e -> e.quad.bounds().let { quad.contains(PointF(it.centerX(), it.centerY())) } }
            .joinToString("") { it.text }

    /** 打码的、只圈出的：（类型, 字）。 */
    private fun found(res: String, degrees: Int = 0): Pair<Set<Pair<SensitiveKind, String>>, Set<Pair<SensitiveKind, String>>> =
        runBlocking {
            val lines = lines(res, degrees)
            val all = RuleClassifier(DefaultRuleSet.rules).classify(lines)
            all.filter { it.enabledByDefault }.map { it.kind to it.text(lines) }.toSet() to
                all.filter { !it.enabledByDefault }.map { it.kind to it.text(lines) }.toSet()
        }

    @Test fun `the id card front whichever way it faces`() {
        listOf(0, 90, 180, 270).forEach { d ->
            val (masked, outlined) = found("id-card-front.jpg", d)
            assertWithMessage("转 $d°").that(masked).containsExactlyElementsIn(ID_CARD)
            assertWithMessage("转 $d°").that(outlined).isEmpty()
        }
    }

    /** 竖着放在桌上、拍歪了 4° 的照片：字段名「公 民身份 号码」读散了。 */
    @Test fun `the id card in a tilted photo`() {
        val (masked, outlined) = found("id-card-photo.jpg")
        assertThat(masked).containsExactlyElementsIn(ID_CARD)
        assertThat(outlined).isEmpty()
    }

    /**
     * 斜着平铺的「FOR BANK ACCOUNT OPENING ONLY」：压住的字横着重读出来（PpOcrEngine.underTilted）。
     * 水印那一行里也读出了「林知夏」（「林知夏OUNTOF」），旁边有「姓名」，同样打码：同一个名字两个框，叠在一起。
     */
    @Test fun `the id card under a diagonal watermark`() {
        val (masked, outlined) = found("id-card-watermark.jpg")
        assertThat(masked).containsExactlyElementsIn(ID_CARD)
        assertThat(outlined).isEmpty()
    }

    /**
     * 复印件上常见的密铺中文水印「仅供办理银行开户使用 他用无效」（IdCardPage.java 画的）：水印比证件上的字还长，
     * 页面按水印的走向读，证件上的字在原图上读、压住的几排横着重读。水印字是灰的，和证件上的字一样是汉字，
     * 重读时只接到墨色深过水印的地方。水印那几行里也读出了「林知夏」（「用 无 林知夏」），同样打码。
     * 以前住址第一行、出生日期都读丢了，只遮住了住址第二行。
     */
    @Test fun `the id card under a dense chinese watermark`() {
        val (masked, outlined) = found("id-card-watermark-cn.jpg")
        assertThat(masked).containsExactlyElementsIn(ID_CARD)
        assertThat(outlined).isEmpty()
    }

    /** 国徽面：「有效期限 2015.08.20-2035.08.20」不是银行卡的有效期，签发机关里也没有号码。两个日期只圈出。 */
    @Test fun `nothing is masked on the back of the id card`() {
        val (masked, outlined) = found("id-card-back.jpg")
        assertThat(masked).isEmpty()
        assertThat(outlined).containsExactly(DATETIME to "2015.08.20", DATETIME to "2035.08.20")
    }

    @Test fun `the driving licence reads with its bilingual labels`() {
        val texts = lines("driver-license.jpg").map { it.text }
        assertThat(texts).containsAtLeast("姓名 Name", "住址 Address", "江苏省南京市玄武区北京东路", "41号3栋2单元602室", "1986-02-14")
    }

    /** 字段名和值隔着大半栏，OCR 拆成了同一排的两段；「证号」没有译名，号码在右边一栏。 */
    @Test fun `the driving licence upright and sideways`() {
        listOf(0, 90).forEach { d ->
            val (masked, outlined) = found("driver-license.jpg", d)
            assertWithMessage("转 $d°").that(masked).containsExactlyElementsIn(DRIVING_LICENCE)
            assertWithMessage("转 $d°").that(outlined).containsExactlyElementsIn(LICENCE_DATES)
        }
    }
}
