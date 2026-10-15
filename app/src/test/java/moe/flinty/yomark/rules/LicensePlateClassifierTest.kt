package moe.flinty.yomark.rules

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 车牌出成什么样的候选：上下两行一个框，只圈出的那一档跟着设置走，地址让给车牌。 */
@RunWith(RobolectricTestRunner::class)
class LicensePlateClassifierTest {

    /** 一行字，每个字一个 element（像 PP-OCR），字宽是字高的 0.6 倍。 */
    private fun line(text: String, left: Float, top: Float, h: Float): TextLine {
        val w = h * 0.6f
        val els = text.mapIndexedNotNull { i, c ->
            if (c == ' ') null else TextElement(Quad.fromRect(RectF(left + i * w, top, left + (i + 1) * w, top + h)), c.toString(), i..i)
        }
        return TextLine(Quad.fromRect(RectF(left, top, left + text.length * w, top + h)), text, 0.98f, els)
    }

    private suspend fun plates(lines: List<TextLine>, config: RecognitionConfig = RecognitionConfig()): List<Candidate> =
        RuleClassifier(RuleCatalog.rulesFor(config)).classify(lines)

    @Test fun `a two-row plate is one box over both rows`() = runTest {
        val top = line("苏·E", 252f, 457f, 47f)
        val bottom = line("05863", 204f, 495f, 82f)
        val out = plates(listOf(top, bottom))
        assertThat(out).hasSize(1)
        val c = out.single()
        assertThat(c.kind).isEqualTo(SensitiveKind.LICENSE_PLATE)
        assertThat(c.enabledByDefault).isTrue()
        val b = c.quad.bounds()
        listOf(top, bottom).forEach { l ->
            val lb = l.quad.bounds()
            assertThat(b.contains(lb.left + 1f, lb.top + 1f)).isTrue()
            assertThat(b.contains(lb.right - 1f, lb.bottom - 1f)).isTrue()
        }
    }

    @Test fun `plates of a distinctive format are masked and the rest are not`() = runTest {
        val out = plates(listOf(line("苏B·ILE22", 100f, 100f, 40f), line("B MW 1234", 100f, 300f, 40f)))
        assertThat(out.map { it.enabledByDefault }).containsExactly(true, false).inOrder()
    }

    /** 设成关闭，一个都不出。 */
    @Test fun `the settings row governs every plate`() = runTest {
        val lines = listOf(line("苏B·ILE22", 100f, 100f, 40f), line("B MW 1234", 100f, 300f, 40f))
        assertThat(plates(lines, RecognitionConfig(ruleOverrides = mapOf("plate" to RuleState.OFF)))).isEmpty()
    }

    /**
     * 「苏A12345D」（大型新能源，点读丢了）在桌面 JVM 上长得像爱尔兰邮编：地址让给车牌，一处只有一个框。
     * 「M WA 1234」像澳洲的州加邮编，那边打码；不打码的车牌反过来让给地址，不把打了码的降成不打码。
     */
    @Test fun `addresses yield to masked plates and plates that are not masked yield to addresses`() = runTest {
        val out = plates(listOf(line("苏A12345D", 100f, 100f, 40f), line("M WA 1234", 100f, 300f, 40f)))
        assertThat(out.map { it.kind to it.enabledByDefault })
            .containsExactly(SensitiveKind.LICENSE_PLATE to true, SensitiveKind.POSTAL_ADDRESS to true)
    }
}
