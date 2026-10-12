package moe.flinty.yomark.engine.ppocr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.textUnder
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.TextRecognizer
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * 真模型、真图：合成的身份证人像面（tools/test-images/IdCardPage.java 画的，内容全是编的）转四个方向，
 * 再加一张竖着放在桌上拍歪了 4° 的照片。
 *
 * 0.15.0 在竖着的图上把一列列字串成了一行（「住 址 … 出 生 … 姓 名 林知夏」），倒着的读成乱码，规则遮的是一串不相干的字。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RotatedCardOcrTest {

    /** 一张图：卡面转了多少度（0、90、180、270 是整张图转；[PHOTO] 是那张照片），和卡面坐标到图上坐标的换算。 */
    private class Shot(val degrees: Int) {
        fun toImage(x: Float, y: Float): PointF = when (degrees) {
            0 -> PointF(x, y)
            90 -> PointF(H - y, x)
            180 -> PointF(W - x, H - y)
            270 -> PointF(y, W - x)
            // IdCardPage.photo()：卡面中心移到 (450, 600)，顺时针转 94°，缩到 0.98
            else -> {
                val r = Math.toRadians(94.0)
                val dx = 0.98 * (x - W / 2)
                val dy = 0.98 * (y - H / 2)
                PointF((450 + dx * cos(r) - dy * sin(r)).toFloat(), (600 + dx * sin(r) + dy * cos(r)).toFloat())
            }
        }

        /** 卡面上的一块 → 图上的四边形。 */
        fun region(l: Float, t: Float, r: Float, b: Float) = Quad(toImage(l, t), toImage(r, t), toImage(r, b), toImage(l, b))

        /** 页面在图上顺时针转了几个直角。 */
        val quarterTurns get() = if (degrees == PHOTO) 1 else degrees / 90
    }

    /**
     * 识别一次要一两秒，五张图整个类共用一份结果。在测试方法里第一次用到时才识别，不用 @BeforeClass：
     * 那时 Robolectric 的沙箱还没配好（同 PassportOcrTest）。
     */
    companion object {
        private const val W = 1000f
        private const val H = 630f
        private const val PHOTO = -1
        private val cached = HashMap<Int, List<TextLine>>()

        private val SHOTS = listOf(0, 90, 180, 270, PHOTO)
    }

    private fun lines(degrees: Int): List<TextLine> = cached.getOrPut(degrees) {
        val name = if (degrees == PHOTO) "id-card-photo.jpg" else "id-card-front.jpg"
        val bytes = RotatedCardOcrTest::class.java.getResourceAsStream("/ppocr/$name")!!.readBytes()
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val bmp = if (degrees == PHOTO || degrees == 0) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        runBlocking {
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/jpeg"))
        }
    }

    private fun compact(s: String) = s.filter { it != ' ' }

    private fun label(degrees: Int) = if (degrees == PHOTO) "照片" else "转 $degrees°"

    /** 每一行各是一行：竖着的图上不再把一列列串成一行，倒着的不再是乱码。 */
    @Test fun `each line of the card comes out on its own whichever way it faces`() {
        SHOTS.forEach { d ->
            val texts = lines(d).map { compact(it.text) }
            assertWithMessage(label(d)).that(texts).containsAtLeast(
                "姓名林知夏", "出生1993年7月18日", "住址浙江省杭州市西湖区文三路", "88号6幢2单元501室",
            )
            assertWithMessage(label(d)).that(texts.filter { "330106199307180423" in it }).hasSize(1)
            assertWithMessage(label(d)).that(texts.single { "330106199307180423" in it }).doesNotContain("住址")
        }
    }

    /**
     * 整张图转直角：转正之后的像素和正着的一模一样，读出来的字也一模一样，连先后都不变。
     * 置信度不比：检测是在原图（转过的像素）上做的，框和正着检测出来的差一两个像素，个别行差到 0.05。
     */
    @Test fun `a quarter-turned card reads exactly like the upright one`() {
        val upright = lines(0)
        listOf(90, 180, 270).forEach { d ->
            assertWithMessage(label(d)).that(lines(d).map { it.text }).containsExactlyElementsIn(upright.map { it.text }).inOrder()
        }
    }

    /** 框映射回了原图：名字那三个字、号码那一行的每个字，都落在图上画着它们的地方。 */
    @Test fun `the boxes land where the text is on the turned image`() {
        SHOTS.forEach { d ->
            val shot = Shot(d)
            val lines = lines(d)
            val name = lines.single { "林知夏" in it.text }
            val nameAt = shot.region(170f, 70f, 305f, 130f)
            name.elements.filter { it.text in listOf("林", "知", "夏") }.also { assertThat(it).hasSize(3) }.forEach { e ->
                val b = e.quad.bounds()
                assertWithMessage("${label(d)} ${e.text}").that(nameAt.contains(PointF(b.centerX(), b.centerY()))).isTrue()
            }
            val id = lines.single { "330106199307180423" in compact(it.text) }
            val idAt = shot.region(60f, 500f, 830f, 560f)
            id.elements.forEach { e ->
                val b = e.quad.bounds()
                assertWithMessage("${label(d)} ${e.text}").that(idAt.contains(PointF(b.centerX(), b.centerY()))).isTrue()
            }
            val lineAt = shot.region(55f, 65f, 310f, 135f)
            val c = name.quad.bounds()
            assertWithMessage(label(d)).that(lineAt.contains(PointF(c.centerX(), c.centerY()))).isTrue()
        }
    }

    /** 每行的 quad 按阅读方向排角：p0→p1 在图上就是这一页的阅读方向，人脸检测按它转正。 */
    @Test fun `line quads point the way the card reads`() {
        SHOTS.forEach { d ->
            assertWithMessage(label(d)).that(ReadingFrame.quarterTurns(lines(d).map { it.quad })).isEqualTo(Shot(d).quarterTurns)
        }
    }

    /** 规则在转过的卡上认出的，和正着的一样：同样的类、同样的字、同样打码还是圈出。 */
    @Test fun `the rules find the same things on a turned card`(): Unit = runBlocking {
        suspend fun found(d: Int) = lines(d).let { l ->
            RuleClassifier(DefaultRuleSet.rules).classify(l).map { Triple(it.kind, compact(textUnder(it.quad, l)), it.enabledByDefault) }
        }
        val upright = found(0)
        assertThat(upright.map { it.second }).containsAtLeast("林知夏", "1993年7月18日", "浙江省杭州市西湖区文三路")
        listOf(90, 180, 270, PHOTO).forEach { d ->
            assertWithMessage(label(d)).that(found(d)).containsExactlyElementsIn(upright)
        }
    }

    /** 合并之后也一样：上下两行地址在竖着的图上是左右挨着的两列，不会被当成同一行拼成一块。 */
    @Test fun `merging keeps stacked lines apart on a turned card`(): Unit = runBlocking {
        val blank = SourceImage(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888), 1f, 1, 1, "image/jpeg")
        suspend fun merged(d: Int) = lines(d).let { l ->
            val cached = object : TextRecognizer {
                override val id = "cached"
                override suspend fun recognize(image: SourceImage) = l
            }
            RedactionEngine(cached, emptyList(), listOf(RuleClassifier(DefaultRuleSet.rules))).analyze(blank).candidates
                .map { it.kind to compact(textUnder(it.quad, l)) }
        }
        val upright = merged(0)
        assertThat(upright.count { it.first == SensitiveKind.POSTAL_ADDRESS }).isEqualTo(2)
        listOf(90, 180, 270, PHOTO).forEach { d ->
            assertWithMessage(label(d)).that(merged(d)).containsExactlyElementsIn(upright)
        }
    }
}
