package moe.flinty.yomark.ui.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.R
import moe.flinty.yomark.chat.ChatDetector
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.AnalysisResult
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.ppocr.PaddleTextRecognizer
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 引导里写死的框还对不对：拿真识别（PP-OCR 加出厂规则，同 EngineFactory，只是没有 ML Kit）在打包进去的样图上跑一遍，
 * 一块块对上。规则、识别一改，引导就可能在说假话（图上遮了的编辑器里不遮），这里会先报出来；
 * 报错信息里打着真识别出来的框，照着改 TourSample。
 *
 * 一张样图一个子类，各在自己的语言、地区下跑（手机号规则按地区认）：装进来的图和框都由资源挑。
 */
internal abstract class TourSampleTest(
    private val expected: TourSample,
    /** 这张样图的源 PNG（商店截图用的那张），打包进去的 WebP 要和它一模一样。 */
    private val png: String,
    /** 链接那一段里认得出的字。 */
    private val link: String,
) {

    /** 识别一次要一两秒，同一张图共用。 */
    private companion object {
        val cached = HashMap<Pair<String, RecognitionConfig>, AnalysisResult>()
        const val MIN_IOU = 0.9f
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun analyze(config: RecognitionConfig): AnalysisResult = cached.getOrPut(expected.key to config) {
        val engine = RedactionEngine(
            recognizer = PaddleTextRecognizer(context),
            regionDetectors = emptyList(),
            classifiers = listOf(RuleClassifier(RuleCatalog.rulesFor(config), maskUnanchored = config.maskUnanchoredNames)),
            merger = CandidateMerger(),
            pageAnalyzers = if (config.chatState == RuleState.OFF) emptyList() else listOf(ChatDetector()),
        )
        runBlocking { engine.analyze(TourSample.load(context.resources)) }
    }

    /** 量框的前提：这套资源挑出来的框就是这一份，挑出来的图就是它量的那张。 */
    @Test fun `the resources pick these boxes and the image they were measured on`() {
        assertThat(TourSample.of(context.resources)).isSameInstanceAs(expected)
        assertThat(TourSample.load(context.resources).bitmap.sameAs(decode(png))).isTrue()
    }

    @Test fun `factory settings mask exactly the baked boxes`() {
        assertMatches(masked(RecognitionConfig()), expected.auto)
    }

    @Test fun `chat detection adds exactly the title and the four avatars`() {
        assertMatches(masked(RecognitionConfig(chatState = RuleState.MASKED)), expected.auto + expected.chat)
        assertThat(expected.chat.map { it.kind }).containsExactly(
            SensitiveKind.CHAT_TITLE, SensitiveKind.CHAT_AVATAR, SensitiveKind.CHAT_AVATAR, SensitiveKind.CHAT_AVATAR, SensitiveKind.CHAT_AVATAR,
        ).inOrder()
    }

    /** 写死的是每一段的外接框：有的段是斜的四边形（比如顶栏的「<」），点在哪一段上差不出来。 */
    @Test fun `the tappable pieces are the recognised segments`() {
        val lines = analyze(RecognitionConfig()).lines
        val segments = lines.flatMap { it.segments }.map { Quad.fromRect(it.bounds()) }
        val message = "识别出的段：\n" + lines.joinToString("\n") { line ->
            line.segments.joinToString("\n") { "${Quad.fromRect(it.bounds()).rect()},  // ${line.text}" }
        }
        assertWithMessage(message).that(segments).hasSize(expected.lines.size)
        expected.lines.forEach { baked ->
            assertWithMessage("${baked.rect()}\n$message").that(segments.maxOf { it.iou(baked) }).isAtLeast(MIN_IOU)
        }
    }

    /** 框出来让人点的那一段真是链接：重新量过框、段的先后变了，linkIndex 也要跟着改。 */
    @Test fun `the outlined piece is the link`() {
        val line = analyze(RecognitionConfig()).lines.single { l ->
            l.segments.any { Quad.fromRect(it.bounds()).iou(expected.link) >= MIN_IOU }
        }
        assertThat(line.text).contains(link)
    }

    /** 「点文字打码」让人点的链接，出厂设置下不能已经遮上了。 */
    @Test fun `the link the tour asks for is not masked by default`() {
        val bounds = expected.link.bounds()
        val hits = masked(RecognitionConfig()).filter { RectF.intersects(it.quad.bounds(), bounds) }
        assertThat(hits).isEmpty()
    }

    private fun masked(config: RecognitionConfig) = analyze(config).candidates.filter { it.enabledByDefault }

    private fun assertMatches(found: List<Candidate>, baked: List<MaskItem>) {
        val message = "真识别出来打码的：\n" + found.joinToString("\n") { "${it.kind} ${it.quad.rect()}" }
        assertWithMessage(message).that(found).hasSize(baked.size)
        baked.forEach { item ->
            val best = found.filter { it.kind == item.kind }.maxOfOrNull { it.quad.iou(item.quad) } ?: 0f
            assertWithMessage("${item.candidateId} ${item.quad.rect()}\n$message").that(best).isAtLeast(MIN_IOU)
        }
    }

    private fun Quad.rect() = bounds().let { "RectF(${it.left}f, ${it.top}f, ${it.right}f, ${it.bottom}f)" }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN")
internal class ChineseTourSampleTest : TourSampleTest(TourSample.ZH, "/play/chat-page.png", "pan.example.com")

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS")
internal class EnglishTourSampleTest : TourSampleTest(TourSample.EN, "/play/chat-page-en.png", "photos.example.com")

/**
 * 样图和框跟着语言一起换：图在 drawable-nodpi / drawable-zh-nodpi，挑框的 tour_sample 在 values / values-zh，
 * 哪种语言、哪种屏幕密度下两样都得落在同一张上、和界面文字是同一种语言，图也不按密度缩放。不跑识别。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TourSampleResourcesTest {

    private fun assertPicks(sample: TourSample, png: String, next: String) {
        val res = ApplicationProvider.getApplicationContext<Context>().resources
        assertThat(res.getString(R.string.onboarding_next)).isEqualTo(next)
        assertThat(TourSample.of(res)).isSameInstanceAs(sample)
        val bundled = TourSample.load(res).bitmap
        assertThat(bundled.width).isEqualTo(TourSample.WIDTH)
        assertThat(bundled.height).isEqualTo(TourSample.HEIGHT)
        assertThat(bundled.config).isEqualTo(Bitmap.Config.ARGB_8888)
        assertThat(bundled.sameAs(decode(png))).isTrue()
    }

    @Config(qualifiers = "zh-rCN-xxhdpi")
    @Test fun `simplified chinese gets the chinese sample`() = assertPicks(TourSample.ZH, "/play/chat-page.png", "下一步")

    /** 繁体（zh-Hant）不落到简体的 values-zh 上，界面是英文的，样图也是。 */
    @Config(qualifiers = "zh-rTW-mdpi")
    @Test fun `traditional chinese gets the english sample like the strings`() =
        assertPicks(TourSample.EN, "/play/chat-page-en.png", "Next")

    @Config(qualifiers = "en-rUS-xxxhdpi")
    @Test fun `english gets the english sample`() = assertPicks(TourSample.EN, "/play/chat-page-en.png", "Next")

    @Config(qualifiers = "ja-rJP-hdpi")
    @Test fun `other languages get the english sample like the strings`() =
        assertPicks(TourSample.EN, "/play/chat-page-en.png", "Next")

    @Test fun `every sample has its own key`() {
        assertThat(TourSample.ALL.map { it.key }).containsNoDuplicates()
    }
}

private fun decode(png: String): Bitmap {
    val bytes = TourSampleResourcesTest::class.java.getResourceAsStream(png)!!.readBytes()
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
}
