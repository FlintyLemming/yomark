package moe.flinty.yomark.engine.ppocr

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.geometry.ReadingFrame
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.PAYMENT_CARD
import moe.flinty.yomark.core.model.SensitiveKind.PHONE
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型、真图、出厂规则表，从像素到打码：合成的银行卡背面（tools/test-images/CardBackPage.java），内容全是编的。
 * 签名条上没有字段名的安全码打码，正着、转了直角、放在桌上拍歪了都是；别的只有客服电话。
 *
 * 0.20.0 上安全码一处都没遮，「持卡人签名」里的「签名」倒遮成了人名。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardBackOcrTest {

    /** 识别一次要一两秒，整个类共用。不用 @BeforeClass，理由见 PassportOcrTest。 */
    companion object {
        private val cached = HashMap<String, List<TextLine>>()
    }

    /** [res] 顺时针转 [degrees] 度读出来的行。 */
    private fun lines(res: String, degrees: Int): List<TextLine> = cached.getOrPut("$res@$degrees") {
        val src = TestPages.res("/ppocr/$res")
        val bmp = if (degrees == 0) src
        else Bitmap.createBitmap(src, 0, 0, src.width, src.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
        runBlocking {
            PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
        }
    }

    /** 候选框在它自己那一行上盖住的字，去掉空格。哪一行看 id：「rule-规则-行号-起点-序号」。 */
    private fun Candidate.text(lines: List<TextLine>): String =
        lines[id.split('-')[2].toInt()].elements
            .filter { e -> e.quad.bounds().let { quad.contains(PointF(it.centerX(), it.centerY())) } }
            .joinToString("") { it.text }

    /** 打码的：（类型, 字），一个框一项，同 app 上看到的一样先合并。 */
    private fun masked(res: String, degrees: Int): List<Pair<SensitiveKind, String>> {
        val lines = lines(res, degrees)
        return runBlocking {
            CandidateMerger().merge(RuleClassifier(DefaultRuleSet.rules).classify(lines), ReadingFrame.of(lines.map { it.quad }))
        }.filter { it.enabledByDefault }.map { it.kind to it.text(lines) }
    }

    /** 银联卡：签名条右头的「8810 357」，上面一行是「持卡人签名 AUTHORIZED SIGNATURE」。 */
    @Test fun `the code on a chinese signature strip whichever way it faces`() {
        listOf(0, 90, 180, 270).forEach { d ->
            assertWithMessage("转 $d°").that(masked("card-back-cn.jpg", d)).containsExactly(PAYMENT_CARD to "8810357")
        }
    }

    /** Visa 卡：签名条右边白框里的「482」，客服电话照旧按电话遮。 */
    @Test fun `the code in the white box of a visa card whichever way it faces`() {
        listOf(0, 90, 180, 270).forEach { d ->
            assertWithMessage("转 $d°").that(masked("card-back-en.jpg", d))
                .containsExactly(PAYMENT_CARD to "482", PHONE to "1-800-555-0199")
        }
    }

    /** 银联卡放在木纹桌上拍，歪了 7°：「持卡人签名」读成了「持卡 人签名」。 */
    @Test fun `the card back in a tilted photo`() {
        listOf(0, 90, 180, 270).forEach { d ->
            assertWithMessage("转 $d°").that(masked("card-back-photo.jpg", d)).containsExactly(PAYMENT_CARD to "8810357")
        }
    }
}
