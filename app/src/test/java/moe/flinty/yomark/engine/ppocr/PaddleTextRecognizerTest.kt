package moe.flinty.yomark.engine.ppocr

import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.rules.DefaultRuleSet
import moe.flinty.yomark.rules.RuleClassifier
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * 真模型、真图：随包的 PP-OCRv5 mobile 跑在桌面版 ONNX Runtime 上，读一张合成的快递详情页。
 *
 * 图是照着真机漏检的菜鸟快递详情页排的版，内容全是编的——用户的截图含个人信息，不进仓库。
 * 字体是文泉驿正黑，与手机上的系统字体不同，所以只断言结果，不断言坐标。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaddleTextRecognizerTest {

    companion object {
        private lateinit var lines: List<TextLine>

        /** 识别一次要一两秒，整个类共用一份结果。 */
        @BeforeClass @JvmStatic fun recognizeOnce() = runBlocking {
            val bytes = PaddleTextRecognizerTest::class.java.getResourceAsStream("/ppocr/logistics-page.png")!!.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            lines = PaddleTextRecognizer(ApplicationProvider.getApplicationContext())
                .recognize(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
        }
    }

    private val texts get() = lines.map { it.text }

    @Test fun `chinese lines are read and the gaps come back as spaces`() {
        assertThat(texts).containsAtLeast(
            "取件码 3-2-1234 复制",
            "送至 长江路88号阳光新村5幢302室",
            "王小明 86-139****5678 号码保护中",
            "待取件 09-29 12:30",
        )
    }

    /** 每个字一个 element，range 指回 text 里它自己。 */
    @Test fun `elements are single characters that index the line text`() {
        lines.forEach { line ->
            line.elements.forEach { e ->
                assertThat(e.range.first).isEqualTo(e.range.last)
                assertThat(line.text.substring(e.range)).isEqualTo(e.text)
            }
        }
    }

    @Test fun `the rules find everything on the page`(): Unit = runBlocking {
        val kinds = RuleClassifier(DefaultRuleSet.rules).classify(lines).map { it.kind }
        assertThat(kinds).containsAtLeast(
            SensitiveKind.PERSON_NAME,
            SensitiveKind.PHONE,
            SensitiveKind.POSTAL_ADDRESS,
            SensitiveKind.PICKUP_CODE,
            SensitiveKind.DATETIME,
            SensitiveKind.TRACKING_NO,
        )
    }

    /** 字符级的框：名字与紧挨着它的号码各遮各的，框互不重叠。 */
    @Test fun `the name box stops where the number starts`(): Unit = runBlocking {
        val out = RuleClassifier(DefaultRuleSet.rules).classify(lines.filter { it.text.startsWith("王小明") })
        // 名字的框得在号码前面停下
        val names = out.filter { it.kind == SensitiveKind.PERSON_NAME }.map { it.quad.bounds() }
        val phone = out.single { it.kind == SensitiveKind.PHONE }.quad.bounds()
        assertThat(names).isNotEmpty()
        names.forEach { name ->
            assertThat(name.right).isAtMost(phone.left + 1f)
            assertThat(phone.width()).isGreaterThan(name.width())
        }
    }
}
