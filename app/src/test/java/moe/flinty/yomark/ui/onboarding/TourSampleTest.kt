package moe.flinty.yomark.ui.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.chat.ChatDetector
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.AnalysisResult
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.MaskItem
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
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "zh-rCN")   // 手机号规则按地区认，138 0013 8000 要在中国地区下才算
class TourSampleTest {

    /** 识别一次要一两秒，整个类共用。 */
    companion object {
        private val cached = HashMap<RecognitionConfig, AnalysisResult>()
    }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun analyze(config: RecognitionConfig): AnalysisResult = cached.getOrPut(config) {
        val engine = RedactionEngine(
            recognizer = PaddleTextRecognizer(context),
            regionDetectors = emptyList(),
            classifiers = listOf(RuleClassifier(RuleCatalog.rulesFor(config), maskUnanchored = config.maskUnanchoredNames)),
            merger = CandidateMerger(),
            pageAnalyzers = if (config.chatState == RuleState.OFF) emptyList() else listOf(ChatDetector()),
        )
        runBlocking { engine.analyze(TourSample.load(context.resources)) }
    }

    @Test fun `the bundled sample is the store chat screenshot pixel for pixel`() {
        val bundled = TourSample.load(context.resources).bitmap
        val bytes = javaClass.getResourceAsStream("/play/chat-page.png")!!.readBytes()
        val png = BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, false)
        assertThat(bundled.width).isEqualTo(TourSample.WIDTH)
        assertThat(bundled.height).isEqualTo(TourSample.HEIGHT)
        assertThat(bundled.config).isEqualTo(Bitmap.Config.ARGB_8888)
        assertThat(bundled.sameAs(png)).isTrue()
    }

    @Test fun `factory settings mask exactly the four baked boxes`() {
        assertMatches(masked(RecognitionConfig()), TourSample.AUTO)
    }

    @Test fun `chat detection adds exactly the title and the four avatars`() {
        assertMatches(masked(RecognitionConfig(chatState = RuleState.MASKED)), TourSample.AUTO + TourSample.CHAT)
    }

    /** 写死的是每一段的外接框：有的段是斜的四边形（比如顶栏的「<」），点在哪一段上差不出来。 */
    @Test fun `the tappable pieces are the recognised segments`() {
        val lines = analyze(RecognitionConfig()).lines
        val segments = lines.flatMap { it.segments }.map { Quad.fromRect(it.bounds()) }
        val message = "识别出的段：\n" + lines.joinToString("\n") { line ->
            line.segments.joinToString("\n") { "${Quad.fromRect(it.bounds()).rect()},  // ${line.text}" }
        }
        assertWithMessage(message).that(segments).hasSize(TourSample.LINES.size)
        TourSample.LINES.forEach { baked ->
            assertWithMessage("${baked.rect()}\n$message").that(segments.maxOf { it.iou(baked) }).isAtLeast(MIN_IOU)
        }
    }

    /** 框出来让人点的那一段真是链接：重新量过框、段的先后变了，LINK_INDEX 也要跟着改。 */
    @Test fun `the outlined piece is the link`() {
        val line = analyze(RecognitionConfig()).lines.single { l ->
            l.segments.any { Quad.fromRect(it.bounds()).iou(TourSample.LINK) >= MIN_IOU }
        }
        assertThat(line.text).contains("pan.example.com")
    }

    /** 第二步让人点的链接，出厂设置下不能已经遮上了。 */
    @Test fun `the link the tour asks for is not masked by default`() {
        val link = TourSample.LINK.bounds()
        val hits = masked(RecognitionConfig()).filter { RectF.intersects(it.quad.bounds(), link) }
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

    private val MIN_IOU = 0.9f
}
