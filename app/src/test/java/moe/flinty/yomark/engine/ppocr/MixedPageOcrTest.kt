package moe.flinty.yomark.engine.ppocr

import android.graphics.Bitmap
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.textUnder
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 真模型：页上不只一个方向的字。转正了读竖着的页之后，这几种页上原来认得出的不能丢。
 * - 正着的表单上贴着一张竖着的身份证（拼出来的，见 [TestPages.formWithCard]）；
 * - 身份证上斜着平铺的英文水印，比证件上的字还长（id-card-watermark.jpg，合成的卡加水印）；
 * - 识别器认不得的韩文，夹着一行英文的电话和邮箱（korean-contact.png）；
 * - 正着的界面文字，旁边一列竖排的长字（ui-vertical-strip.png）。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MixedPageOcrTest {

    /** 识别一次要一两秒，整个类共用；第一次用到时才识别，理由同 PassportOcrTest。 */
    companion object {
        private val cached = HashMap<String, List<TextLine>>()
    }

    private fun read(key: String, image: () -> Bitmap): List<TextLine> = cached.getOrPut(key) {
        val bmp = image()
        runBlocking {
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
        }
    }

    private fun compact(s: String) = s.filter { it != ' ' }

    private fun found(lines: List<TextLine>) = runBlocking {
        RuleClassifier(DefaultRuleSet.rules).classify(lines).map { it.kind to compact(textUnder(it.quad, lines)) }
    }

    /** 一行在图上是横平的：p0→p1 竖直方向差不到一个像素。 */
    private fun TextLine.isLevel() = abs(quad.p1.y - quad.p0.y) < 1f

    /**
     * 证件转正了读，表单上那两行还在原图上照常读：一个字不差，不和证件上的字串成一行。
     * 以前整页转过去，正着的行在转过的图上是竖的，按竖排读成了乱码，名字和地址都没遮。
     */
    @Test fun `a sideways card leaves the upright form lines as they were`() {
        listOf(90, 270).forEach { d ->
            val lines = read("form$d") { TestPages.formWithCard(d) }
            val texts = lines.map { compact(it.text) }
            assertWithMessage("证件转 $d°").that(texts).containsAtLeast(
                "送至长江路88号阳光新村5幢302室", "王小明86-139****5678号码保护中",
                "姓名林知夏", "出生1993年7月18日", "住址浙江省杭州市西湖区文三路", "88号6幢2单元501室",
            )
            assertWithMessage("证件转 $d°").that(lines.filter { "王小明" in it.text }.single().isLevel()).isTrue()
            assertWithMessage("证件转 $d°").that(found(lines)).containsAtLeast(
                SensitiveKind.PERSON_NAME to "王小明",
                SensitiveKind.POSTAL_ADDRESS to "长江路88号阳光新村5幢302室",
                SensitiveKind.PERSON_NAME to "林知夏",
                SensitiveKind.BIRTH_DATE to "1993年7月18日",
            )
        }
    }

    /**
     * 点字打码按段打（TextLine.segments）：转正了读的、压着水印重读的行，段的框和字的框一起映射回原图，
     * 每个字都落在它那一行的某一段里，点哪个字都点得中。
     */
    @Test fun `every character falls inside a segment of its line`() {
        listOf(
            "form90" to { TestPages.formWithCard(90) },
            "form270" to { TestPages.formWithCard(270) },
            "watermark" to { TestPages.res("/ppocr/id-card-watermark.jpg") },
        ).forEach { (key, image) ->
            read(key, image).forEach { line ->
                line.elements.forEach { e ->
                    val c = e.quad.bounds()
                    assertWithMessage("$key「${line.text}」的「${e.text}」")
                        .that(line.segments.any { it.contains(PointF(c.centerX(), c.centerY())) }).isTrue()
                }
            }
        }
    }

    /**
     * 斜着平铺的水印比证件上的字长，页面仍按横平的读：名字、号码、地址照样认得出。
     * 以前整页转到水印的角度上读，名字那一行没了，号码读成了三段。
     *
     * 水印压住的字横着重读出来（PpOcrEngine.underTilted），水印的字母不再串进证件的行里：
     * 以前「出」「7 月」「女」「汉」跟着水印读成了字母，「民 族」「18 日」串进了水印那一行，出生日期遮不上。
     */
    @Test fun `a long diagonal watermark does not tilt the card`() {
        val lines = read("watermark") { TestPages.res("/ppocr/id-card-watermark.jpg") }
        assertThat(ReadingFrame.of(lines.map { it.quad }).isUpright).isTrue()
        assertThat(lines.map { compact(it.text) }).containsAtLeast(
            "姓名林知夏", "性别女民族汉", "出生1993年7月18日", "住址浙江省杭州市西湖区文三路", "88号6幢2单元501室",
            "公民身份号码330106199307180423",
        )
        val found = found(lines)
        assertThat(found.filter { it.first == SensitiveKind.PERSON_NAME }.map { it.second }.any { "林知夏" in it }).isTrue()
        assertThat(found.filter { it.first == SensitiveKind.ID_NUMBER }.map { it.second }.single { "330106199307180423" in it }).isNotEmpty()
        assertThat(found).contains(SensitiveKind.POSTAL_ADDRESS to "88号6幢2单元501室")
    }

    /**
     * 认不得的字正着倒着都读不好，比出来的高低不作数：不把整页倒过来读，英文那一行照常读出来。
     * 以前倒过来的韩文「读得没那么差」，整页转了半圈，邮箱成了 wanxxcom。
     */
    @Test fun `text the model cannot read is not turned upside down`() {
        val lines = read("korean") { TestPages.res("/ppocr/korean-contact.png") }
        assertThat(lines.map { it.text }).contains("Tel 13912345678 Mail lisi@example.org")
        assertThat(found(lines)).contains(SensitiveKind.EMAIL to "lisi@example.org")
    }

    /**
     * 一列竖排的长字让整页的走向成了竖的，旁边正着的两个字的词照常读：竖的那列单独成行，不再和「李四」串在一起。
     * 以前两个字的词也被转过去读，「张三」读成了「三米」。
     */
    @Test fun `a vertical strip leaves the short upright words alone`() {
        val lines = read("strip") { TestPages.res("/ppocr/ui-vertical-strip.png") }
        assertThat(lines.map { compact(it.text) }).containsAtLeast(
            "姓名", "张三电话", "订单确定取消", "地址备注", "王五", "李四", "13812345678", "本页内容仅供内部使用请勿外传谢谢合作",
        )
        assertThat(found(lines)).contains(SensitiveKind.PERSON_NAME to "张三")
    }
}
