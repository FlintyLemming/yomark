package moe.flinty.yomark.engine.ppocr

import android.graphics.BitmapFactory
import android.graphics.PointF
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.runBlocking
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.AnalysisResult
import moe.flinty.yomark.debug.textUnder
import moe.flinty.yomark.engine.CandidateMerger
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RedactionEngine
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.rules.RuleClassifier
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

/**
 * 真模型、真图：英文聊天截图上打的码，盖不盖得住下伸的笔画（p g y j q、逗号、括号）。
 * 图是 tools/test-images/descenders-chat.html 渲染的，上半截 Noto Sans、下半截 Roboto，内容全是编的。
 *
 * 首次引导的英文样图上「2847 Maple Ave, Apt 12C」的码（出厂规则、PP-OCR）下沿就落在两个 p 的尖上，模拟器上露出来一截。
 * 这里量每块码碰到的字的墨走到多低：码的下沿得在最低的墨下面留出一点（[CLEARANCE] 个码高），
 * 遮罩的边抗锯齿、预览缩放之后才看不见。
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS")
class DescendersOcrTest {

    /** 识别一次要一两秒，整个类共用一份结果。第一次用到时才识别，理由见 PassportOcrTest。 */
    companion object {
        private var cached: Pair<AnalysisResult, Page>? = null

        /** 码的下沿至少在最低的墨下面这么多个码高。没有下伸笔画的行，检测出的框本来就留着 0.08 个框高以上。 */
        private const val CLEARANCE = 0.04f
    }

    private val analyzed: Pair<AnalysisResult, Page>
        get() = cached ?: runBlocking {
            val bytes = DescendersOcrTest::class.java.getResourceAsStream("/ppocr/descenders-chat.png")!!.readBytes()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val config = RecognitionConfig()
            val result = RedactionEngine(
                recognizer = PaddleTextRecognizer(ApplicationProvider.getApplicationContext()),
                regionDetectors = emptyList(),
                classifiers = listOf(RuleClassifier(RuleCatalog.rulesFor(config), maskUnanchored = config.maskUnanchoredNames)),
                merger = CandidateMerger(),
            ).analyze(SourceImage(bmp, 1f, bmp.width, bmp.height, "image/png"))
            result to Page(pixels, bmp.width, bmp.height)
        }.also { cached = it }

    private val result get() = analyzed.first
    private val page get() = analyzed.second
    private val masks get() = result.candidates.filter { it.enabledByDefault }

    @Test fun `the addresses, phones, emails and the name are masked`() {
        assertThat(masks.map { textUnder(it.quad, result.lines) }).containsAtLeast(
            "2847 Maple Ave, Apt 12C",
            "+1 (202) 555-0147",
            "jordan.ellis@example.com",
            "Ms. Rivera",
            "2847 Oak Ave, Unit 12C",
            "peggy.quigley@example.org",
            "+1 (415) 555-0199",
            "19 Spring Valley Rd, Apt 4B",
        )
    }

    @Test fun `every mask reaches below the descenders of the glyphs it covers`() {
        val clearances = masks.map { m ->
            val bottom = m.quad.bounds().bottom
            val ink = page.lowestInk(m.quad)
            Triple(textUnder(m.quad, result.lines), (bottom - ink) / m.quad.bounds().height(), "码的下沿 $bottom，墨到 $ink")
        }
        val message = clearances.joinToString("\n") { (text, c, detail) -> "$text：${"%.3f".format(c)} 个码高（$detail）" }
        clearances.forEach { (_, c, _) -> assertWithMessage(message).that(c).isAtLeast(CLEARANCE) }
    }

    /** 整张图的像素，按亮度看墨。 */
    private class Page(private val pixels: IntArray, private val width: Int, private val height: Int) {

        private fun luma(x: Int, y: Int): Float {
            val c = pixels[y * width + x]
            return 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        }

        /**
         * 码底下的字的墨（八连通）最低到哪一排的下沿：从码里的墨出发，往外跟着连着的墨走，最远走出一个码高。
         * 纸是码里的亮度中位数，墨是离它远的那一头；和纸差过两者之差 15% 的都算墨，抗锯齿的淡边也算。
         */
        fun lowestInk(quad: Quad): Float {
            val b = quad.bounds()
            val x0 = b.left.toInt().coerceAtLeast(0)
            val x1 = b.right.toInt().coerceAtMost(width - 1)
            val y0 = b.top.toInt().coerceAtLeast(0)
            val y1 = b.bottom.toInt().coerceAtMost(height - 1)
            val values = ArrayList<Float>()
            for (y in y0..y1) for (x in x0..x1) values += luma(x, y)
            values.sort()
            val paper = values[values.size / 2]
            val dark = values[values.size / 50]
            val light = values[values.size - 1 - values.size / 50]
            val ink = if (paper - dark >= light - paper) dark else light
            val threshold = 0.15f * abs(ink - paper)
            fun inked(x: Int, y: Int) = abs(luma(x, y) - paper) >= threshold

            val reach = b.height()
            val wx0 = (b.left - reach).toInt().coerceAtLeast(0)
            val wx1 = (b.right + reach).toInt().coerceAtMost(width - 1)
            val wy0 = (b.top - reach).toInt().coerceAtLeast(0)
            val wy1 = (b.bottom + reach).toInt().coerceAtMost(height - 1)
            val seen = HashSet<Int>()
            val stack = ArrayDeque<Int>()
            for (y in y0..y1) for (x in x0..x1) {
                if (inked(x, y) && quad.contains(PointF(x + 0.5f, y + 0.5f)) && seen.add(y * width + x)) stack += y * width + x
            }
            var lowest = -1
            while (stack.isNotEmpty()) {
                val k = stack.removeLast()
                val x = k % width
                val y = k / width
                if (y > lowest) lowest = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx < wx0 || nx > wx1 || ny < wy0 || ny > wy1) continue
                    val nk = ny * width + nx
                    if (nk !in seen && inked(nx, ny)) { seen += nk; stack += nk }
                }
            }
            return lowest + 1f
        }
    }
}
