package moe.flinty.yomark.rules

import android.graphics.RectF
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RuleClassifierTest {

    /** 每个词一个 element，等宽 50px，间距 10px，字高 20px。[top] 是这一行的上沿，[left] 是左沿。 */
    private fun line(text: String, top: Float = 0f, left: Float = 0f): TextLine {
        var cursor = 0
        var x = left
        val els = text.split(" ").map { w ->
            val start = cursor
            cursor += w.length + 1
            val e = TextElement(Quad.fromRect(RectF(x, top, x + 50f, top + 20f)), w, start until start + w.length)
            x += 60f
            e
        }
        return TextLine(Quad.fromRect(RectF(left, top, x, top + 20f)), text, 0.95f, els)
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

    // ---------- 要旁证的猜测（NeedsAnchor） ----------

    /** 猜测：三个小写字母。旁证：四位数字。 */
    private val guess = CompositeRule(
        id = "guess", kind = SensitiveKind.PERSON_NAME, enabledByDefault = true,
        NeedsAnchor(Finder { t -> Regex("""[a-z]{3}""").findAll(t).map { RuleMatch(it.range, 0.6f) }.toList() }),
    )
    private val digitAnchors = Finder { t -> Regex("""\d{4}""").findAll(t).map { RuleMatch(it.range, 1f) }.toList() }

    private suspend fun guesses(vararg lines: TextLine, keepUnanchored: Boolean = false) =
        RuleClassifier(listOf(guess), keepUnanchored = keepUnanchored, anchors = digitAnchors).classify(lines.toList())

    /** 同一行有旁证：照规则的设置打码。 */
    @Test
    fun `a guess with an anchor on its line follows the rule and is masked`() = runTest {
        val out = guesses(line("abc 1234"))
        assertThat(out.single().enabledByDefault).isTrue()
    }

    /** 旁证在下面一行：滴滴出票页上名字在上、打了星的证件号在下。 */
    @Test
    fun `a guess with an anchor on the next line is masked`() = runTest {
        val out = guesses(line("abc"), line("1234", top = 30f))
        assertThat(out.single().enabledByDefault).isTrue()
    }

    /** 同一排、OCR 拆成了两行：左边是值，右边是号码。 */
    @Test
    fun `an anchor further along the same row counts`() = runTest {
        val out = guesses(line("abc"), line("1234", top = 2f, left = 600f))
        assertThat(out.single().enabledByDefault).isTrue()
    }

    /** 隔了几行、或者在另一栏，都不算旁证：出厂直接丢掉。 */
    @Test
    fun `a guess far from any anchor is dropped`() = runTest {
        assertThat(guesses(line("abc"), line("1234", top = 200f))).isEmpty()
        assertThat(guesses(line("abc"), line("1234", top = 30f, left = 600f))).isEmpty()
        assertThat(guesses(line("abc xyz"))).isEmpty()
    }

    /** 设置里打开「没有旁证的人名也圈出」：留下来，但只圈不打码。 */
    @Test
    fun `an unanchored guess is outlined when the setting keeps it`() = runTest {
        val out = guesses(line("abc"), keepUnanchored = true)
        assertThat(out.single().enabledByDefault).isFalse()
    }

    /** 猜测落在旁证里面：旁证撑不住它自己里面的那一截。 */
    @Test
    fun `an anchor does not vouch for a guess inside it`() = runTest {
        val overlapping = Finder { t -> listOf(RuleMatch(t.indices, 1f)) }
        val out = RuleClassifier(listOf(guess), anchors = overlapping).classify(listOf(line("abc")))
        assertThat(out).isEmpty()
    }

    /** 规则选的「圈出」照样管有旁证的猜测：旁证只决定留不留，不把圈出升成打码。 */
    @Test
    fun `an anchored guess in an outlined rule stays outlined`() = runTest {
        val outlined = CompositeRule(
            id = "guess", kind = SensitiveKind.PERSON_NAME, enabledByDefault = false,
            NeedsAnchor(Finder { t -> Regex("""[a-z]{3}""").findAll(t).map { RuleMatch(it.range, 0.6f) }.toList() }),
        )
        val out = RuleClassifier(listOf(outlined), anchors = digitAnchors).classify(listOf(line("abc 1234")))
        assertThat(out.single().enabledByDefault).isFalse()
    }

    /** 别的规则不要旁证：附近什么都没有也照出。 */
    @Test
    fun `matches that need no anchor are never dropped`() = runTest {
        val out = RuleClassifier(listOf(digits), anchors = Finder { emptyList() }).classify(listOf(line("code 1234")))
        assertThat(out.single().enabledByDefault).isTrue()
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
