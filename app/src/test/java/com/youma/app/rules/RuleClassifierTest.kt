package com.youma.app.rules

import android.graphics.RectF
import com.youma.app.core.geometry.Quad
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKind
import com.youma.app.core.model.TextElement
import com.youma.app.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleClassifierTest {

    /** 每个词一个 element，等宽 50px，间距 10px。 */
    private fun line(text: String): TextLine {
        var cursor = 0
        var x = 0f
        val els = text.split(" ").map { w ->
            val start = cursor
            cursor += w.length + 1
            val e = TextElement(Quad.fromRect(RectF(x, 0f, x + 50f, 20f)), w, start until start + w.length)
            x += 60f
            e
        }
        return TextLine(Quad.fromRect(RectF(0f, 0f, x, 20f)), text, 0.95f, els)
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
    fun `a matching rule produces a candidate`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("code 1234 end")))
        assertThat(out).hasSize(1)
        assertThat(out.single().kind).isEqualTo(SensitiveKind.PAYMENT_CARD)
        assertThat(out.single().source).isEqualTo(DetectorSource.RULE)
    }

    @Test
    fun `the candidate quad lands on the matched word`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("code 1234 end")))
        // "1234" 是第二个词：x 从 60 到 110
        assertThat(out.single().quad.bounds()).isEqualTo(RectF(60f, 0f, 110f, 20f))
    }

    @Test
    fun `a failing validator suppresses the candidate`() = runTest {
        val never = RegexRule("no", SensitiveKind.PAYMENT_CARD, true, Regex("""\d{4}"""), 0.9f, validate = { _, _ -> false })
        assertThat(RuleClassifier(listOf(never)).classify(listOf(line("code 1234 end")))).isEmpty()
    }

    @Test
    fun `enabledByDefault flows from the rule to the candidate`() = runTest {
        val outlined = RegexRule("o", SensitiveKind.URL, enabledByDefault = false, Regex("""\d{4}"""), 0.7f, { _, _ -> true })
        assertThat(RuleClassifier(listOf(outlined)).classify(listOf(line("x 1234"))).single().enabledByDefault)
            .isFalse()
    }

    @Test
    fun `select narrows the masked range without changing the match`() = runTest {
        // 匹配 "ab1234"，但只遮后四位
        val narrow = RegexRule(
            id = "narrow", kind = SensitiveKind.URL, enabledByDefault = true,
            pattern = Regex("""ab\d{4}"""), confidence = 0.8f, validate = { _, _ -> true },
            select = { m -> (m.range.first + 2)..m.range.last },
        )
        val out = RuleClassifier(listOf(narrow)).classify(listOf(line("ab1234 tail")))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `multiple matches in one line all become candidates`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("1111 2222 3333")))
        assertThat(out).hasSize(3)
    }

    @Test
    fun `candidate ids are unique across lines and rules`() = runTest {
        val out = RuleClassifier(listOf(digits)).classify(listOf(line("1111 2222"), line("3333 4444")))
        assertThat(out.map { it.id }.toSet()).hasSize(4)
    }

    @Test
    fun `an exploding rule does not take down the classifier`() = runTest {
        val boom = object : Rule {
            override val id = "boom"
            override val kind = SensitiveKind.EMAIL
            override val enabledByDefault = true
            override fun findIn(text: String): List<RuleMatch> = error("rule exploded")
        }
        val out = RuleClassifier(listOf(boom, digits)).classify(listOf(line("code 1234")))
        assertThat(out).hasSize(1)
    }

    @Test
    fun `classifier is always available`() = runTest {
        assertThat(RuleClassifier(emptyList()).isAvailable()).isTrue()
    }

    @Test
    fun `empty lines produce no candidates`() = runTest {
        assertThat(RuleClassifier(listOf(digits)).classify(emptyList())).isEmpty()
    }
}
