package moe.flinty.yomark.rules

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 置信度下限（spec §15 第 5 条的实测结论）。
 *
 * bundled 的拉丁识别器碰上中文会吐出置信度 0.25–0.4 的乱码串，
 * 那些串照样会喂进规则层，是真实截图上误报的一个来源。
 * 低于下限的行直接不参与规则判定。
 */
@RunWith(RobolectricTestRunner::class)
class RuleClassifierConfidenceFloorTest {

    private fun line(text: String, confidence: Float): TextLine {
        val el = TextElement(Quad.fromRect(RectF(0f, 0f, 100f, 20f)), text, text.indices)
        return TextLine(Quad.fromRect(RectF(0f, 0f, 100f, 20f)), text, confidence, listOf(el))
    }

    private val digits = RegexRule(
        id = "test-digits",
        kind = SensitiveKind.PAYMENT_CARD,
        enabledByDefault = true,
        pattern = Regex("""\d{4}"""),
        confidence = 0.9f,
        validate = { _, _ -> true },
    )

    @Test
    fun `a confident line still produces candidates`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("1234", 0.9f)))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `a garbled low-confidence line is skipped`() = runTest {
        // §15.5 实测：中文标签被识别成 conf 0.25–0.4 的乱码
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("1234", 0.3f)))
        assertThat(out).isEmpty()
    }

    @Test
    fun `the floor is inclusive so a line exactly at it survives`() = runTest {
        val out = RuleClassifier(listOf(digits))
            .classify(listOf(line("1234", RuleClassifier.MIN_LINE_CONFIDENCE)))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `the floor is configurable so callers can opt out`() = runTest {
        val out = RuleClassifier(listOf(digits), minLineConfidence = 0f)
            .classify(listOf(line("1234", 0.1f)))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `a low-confidence line does not suppress its confident neighbours`() = runTest {
        val out = RuleClassifier(listOf(digits))
            .classify(listOf(line("1111", 0.2f), line("2222", 0.95f)))
        assertThat(out).hasSize(1)
        assertThat(out.single().id).contains("-1-")      // 第二行（lineIndex = 1）
    }
}
