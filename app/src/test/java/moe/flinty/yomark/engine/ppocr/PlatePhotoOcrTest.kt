package moe.flinty.yomark.engine.ppocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
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
        private val cached = HashMap<String, List<TextLine>>()
    }

    private fun read(key: String, image: () -> Bitmap): List<TextLine> = cached.getOrPut(key) {
        val bmp = image()
        runBlocking {
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/jpeg"))
        }
    }

    /** 那张照片，整张顺时针转了 [degrees] 度。 */
    private fun photo(degrees: Int = 0): List<TextLine> = read("photo@$degrees") {
        val bytes = PlatePhotoOcrTest::class.java.getResourceAsStream("/ppocr/plate-photo.jpg")!!.readBytes()
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (degrees == 0) bmp
        else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    }

    private val lines: List<TextLine> get() = photo()

    private val candidates: List<Candidate> get() = classify(lines)

    private fun classify(lines: List<TextLine>): List<Candidate> = runBlocking { RuleClassifier(DefaultRuleSet.rules).classify(lines) }

    /** 一个框盖住了 [lines] 里的哪几行：行的中心落在框里。 */
    private fun covered(c: Candidate, lines: List<TextLine> = this.lines): List<String> {
        val b = c.quad.bounds()
        return lines.filter { l -> l.quad.bounds().let { b.contains(it.centerX(), it.centerY()) } }.map { it.text }
    }

    /**
     * 七块牌子各认出来没有：每块牌子用它身上一段转过来、读法变了也还在的字认（「PAB-123-CD」「品川330」「BB MW 1234」照样对得上），
     * 给回（那段字, 框盖住不止一行吗, 打码吗）；车牌以外的类型给回类型。
     */
    private fun plates(lines: List<TextLine>): List<Any> {
        val found = classify(lines)
        val marks = listOf("ILE22", "D12345", "05863", "12-34", "AB12", "123-CD", "MW")
        return found.map { c ->
            if (c.kind != SensitiveKind.LICENSE_PLATE) return@map c.kind
            val texts = covered(c, lines)
            Triple(marks.filter { m -> texts.any { m in it } }, texts.size > 1, c.enabledByDefault)
        }
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

    /**
     * 整张照片竖着、倒着：七块牌子和正着的一样认出来。转 90° 时法国的牌子读成「PAB-123-CD」（欧盟蓝条上的 F 读成 P、粘在号码上），
     * 以前它没认出来，旁边德国的那块也因为它「附近有字」丢了。
     */
    @Test fun `a turned photo finds every plate the upright one does`() {
        val upright = plates(photo())
        assertThat(upright).containsExactlyElementsIn(sevenPlates)
        listOf(90, 270).forEach { d -> assertWithMessage("转 $d°").that(plates(photo(d))).containsExactlyElementsIn(upright) }
    }

    /** 七块牌子，同正着读这张照片：写法独特的打码，上下两行的合成一个框，德国的只圈出（见 [plates]）。 */
    private val sevenPlates = listOf(
        Triple(listOf("ILE22"), false, true), Triple(listOf("D12345"), false, true), Triple(listOf("05863"), true, true),
        Triple(listOf("12-34"), true, true), Triple(listOf("AB12"), false, true), Triple(listOf("123-CD"), false, true),
        Triple(listOf("MW"), false, false),
    )

    /**
     * 一整页正着的截图下面贴着倒过来的这张照片：截图上的字多，照片上读得磕绊的几行只占横框总长的一小截，
     * 以前按整页算够不上倒过来试的门槛，七块牌子一块都没认出（「AB-23-CD」「品30」「5B32」）。现在按它们围起来的那一块算。
     */
    @Test fun `an upside-down photo under a full screenshot`() {
        val lines = read("screenshot@180") { TestPages.screenshotWith("plate-photo.jpg", 180f, 0.5f) }
        assertThat(plates(lines).filterIsInstance<Triple<*, *, *>>()).containsExactlyElementsIn(sevenPlates)
        assertThat(plates(lines)).containsAtLeast(SensitiveKind.POSTAL_ADDRESS, SensitiveKind.PERSON_NAME, SensitiveKind.PHONE)
    }

    /**
     * 截图下面并排贴着这张照片和一张身份证人像面，一张顺时针转 90°、一张转 270°：竖着的框不分朝哪边读，整页挑了一个方向转正，
     * 另一张在画布上是倒着的，以前读成乱码（「BMW 34」「PA---CD」，大的照片转对了时身份证读成「3423 公 号」），六块牌子或者证件上的五处全丢了。
     * 照片倒着、证件转 90° 的，照片在原图上读，也是倒着的，七块牌子全丢了。现在两拨里读得磕绊的一组都倒过来再读。两张转同一个方向的照旧。
     */
    @Test fun `a plate photo and an id card turned opposite ways in one screenshot`() {
        fun page(plate: Float, plateScale: Float, card: Float, cardScale: Float, cardLeft: Float) = TestPages.screenshotWith(
            860,
            TestPages.Pasted(TestPages.turned("plate-photo.jpg", plate, plateScale), 10f, 880f),
            TestPages.Pasted(TestPages.turned("id-card-front.jpg", card, cardScale), cardLeft, 880f),
        )
        val cases = mapOf(
            "照片 90° 证件 270°" to { page(90f, 0.4f, 270f, 0.55f, 600f) },
            "照片 270° 证件 90°" to { page(270f, 0.4f, 90f, 0.55f, 600f) },
            "大照片 90° 小证件 270°" to { page(90f, 0.55f, 270f, 0.45f, 810f) },
            // 证件转正了读，照片上的字和证件的垂直、在原图上读，是倒着的
            "照片 180° 证件 90°" to { page(180f, 0.4f, 90f, 0.55f, 600f) },
            "照片 90° 证件 90°" to { page(90f, 0.4f, 90f, 0.55f, 600f) },
        )
        cases.forEach { (name, image) ->
            val lines = read(name, image)
            assertWithMessage(name).that(plates(lines).filterIsInstance<Triple<*, *, *>>()).containsExactlyElementsIn(sevenPlates)
            val masked = classify(lines).filter { it.enabledByDefault }
            listOf(
                SensitiveKind.PERSON_NAME to "林知夏", SensitiveKind.BIRTH_DATE to "1993年7月18日",
                SensitiveKind.POSTAL_ADDRESS to "浙江省杭州市西湖区文三路", SensitiveKind.POSTAL_ADDRESS to "88号6幢2单元501室",
                SensitiveKind.ID_NUMBER to "330106199307180423",
            ).forEach { (kind, text) -> assertWithMessage("$name $kind $text").that(masks(lines, masked, kind, text)).isTrue() }
        }
    }

    /**
     * 截图下面这张照片拍歪了十几二十度：过了 15° 的斜框以前一律当水印，按图的横竖拼行，上下错开的两块牌子拼成了一行
     * （「苏·E 粤B·D12345」「AB12 CDE 12-34」），照片上读成横平方框的「さ」还被横着往「12-34」那边重读成「さ 12-2」「さ120」，
     * 黄色双层牌、日本牌没认出。现在挨在一起、走向一致、中间没有横平的字的一片斜框当一张照片（PpOcrEngine.photos），
     * 按它自己的走向拼行，不拿它重读旁边的字。
     * 歪 −16° 时德国的牌子读成「BBMW 1234」（认不出），挨着它的法国牌子不算孤零零一行；歪 −20° 时「苏·E」「品川330」OCR 没读出来：
     * 这两个只看拼行。
     */
    @Test fun `a photo tilted past 15 degrees inside an upright screenshot`() {
        val plateTexts = listOf("ILE22", "D12345", "05863", "12-34", "AB12", "123-CD", "MW", "苏·E", "品川")
        listOf(15f, 16f, 18f, -14f, -16f, -20f).forEach { d ->
            val lines = read("screenshot-tilt@$d") { TestPages.screenshotWith("plate-photo.jpg", d, 0.5f) }
            // 照片上的每一行最多一块牌子的字
            lines.forEach { l ->
                assertWithMessage("歪 $d° ${l.text}").that(plateTexts.count { it in l.text.replace(" ", "") }).isAtMost(1)
            }
            if (d == -16f || d == -20f) return@forEach
            val found = plates(lines).filterIsInstance<Triple<*, *, *>>().filter { it.third == true }
            assertWithMessage("歪 $d°").that(found).containsAtLeast(
                Triple(listOf("ILE22"), false, true), Triple(listOf("D12345"), false, true), Triple(listOf("05863"), true, true),
                Triple(listOf("12-34"), true, true), Triple(listOf("AB12"), false, true), Triple(listOf("123-CD"), false, true),
            )
        }
    }

    /** [text] 那几个字（去掉空格比）每个字的中心都落在 [kind] 的打码框里。 */
    private fun masks(lines: List<TextLine>, masked: List<Candidate>, kind: SensitiveKind, text: String): Boolean {
        val boxes = masked.filter { it.kind == kind }.map { it.quad }
        return lines.any { line ->
            val chars = line.elements
            val compact = chars.joinToString("") { it.text }
            val at = compact.indexOf(text)
            at >= 0 && chars.subList(at, at + text.length).all { e ->
                e.quad.bounds().let { b -> boxes.any { it.contains(PointF(b.centerX(), b.centerY())) } }
            }
        }
    }

    /**
     * 正着的截图下面贴着这张照片，缩小了、拍歪了：照片上的字少，不单分一组。上下两行的牌子按照片上的字自己的方向量才拼得上，
     * 以前按整页量，两行的框咬住太多，没拼上、没遮（歪 ±8° 时日本的那块，歪 10° 时黄牌：「苏·E」只有三个字，读出来的框是横平的，
     * 要按下一行「05863」的方向量）。截图上的字照旧认，不和照片上的混。
     */
    @Test fun `a tilted photo inside an upright screenshot`() {
        listOf(Triple(8f, 0.55f, 200f), Triple(-8f, 0.55f, 200f), Triple(10f, 0.5f, 0f)).forEach { (d, scale, left) ->
            val lines = read("screenshot@$d/$scale/$left") { TestPages.screenshotWithPlatePhoto(d, scale, left) }
            val photoLines = lines.filter { it.quad.bounds().top > 1500f }
            val found = plates(lines).filterIsInstance<Triple<*, *, *>>()
            assertWithMessage("歪 $d°").that(found.filter { it.third == true }).containsExactly(
                Triple(listOf("ILE22"), false, true), Triple(listOf("D12345"), false, true), Triple(listOf("05863"), true, true),
                Triple(listOf("12-34"), true, true), Triple(listOf("AB12"), false, true), Triple(listOf("123-CD"), false, true),
            )
            val yellow = classify(lines).single { c -> covered(c, lines).any { "05863" in it } }
            assertWithMessage("歪 $d°").that(covered(yellow, photoLines).map { it.filter { c -> c != ' ' } }).containsExactly("苏·E", "05863")
            assertWithMessage("歪 $d°").that(plates(lines)).containsAtLeast(SensitiveKind.POSTAL_ADDRESS, SensitiveKind.PERSON_NAME)
        }
    }
}
