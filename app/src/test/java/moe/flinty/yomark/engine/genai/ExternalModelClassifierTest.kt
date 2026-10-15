package moe.flinty.yomark.engine.genai

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.DetectorSource
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.TraceLog
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.refinersFor
import moe.flinty.yomark.rules.RuleCatalog
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 实验室里的外部模型：发出去的是什么（规则认出的号码换成了占位符），回答怎么落回页上，没答上来时怎么说。
 * 接口本身换成假的；真接口在模拟器上验过。
 */
@RunWith(RobolectricTestRunner::class)
class ExternalModelClassifierTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Quad.fromRect(RectF(l, t, r, b))

    private fun line(text: String, y: Float) = TextLine(
        rect(0f, y, text.length * 10f, y + 10f), text, 0.98f,
        text.indices.map { i -> TextElement(rect(i * 10f, y, i * 10f + 10f, y + 10f), text[i].toString(), i..i) },
    )

    private val page = listOf(
        line("收件人 王小明 13800138000", 0f),
        line("<", 20f),
        line("让张伟明天来拿", 40f),
    )

    private val on = ExternalModelSettings(enabled = true, baseUrl = "https://api.example.com/v1", apiKey = "k", model = "m")

    private fun classifier(
        settings: ExternalModelSettings = on,
        ask: suspend (ExternalModelSettings, String) -> String,
    ) = ExternalModelClassifier(
        kinds = SemanticPrompt.KINDS,
        onRules = RuleCatalog.rulesFor(RecognitionConfig()),
        settings = { settings },
        ask = ask,
    )

    /** 规则认得出的手机号不离开手机，留个占位符当线索；人名照原样发，那正是要模型看的。 */
    @Test fun `numbers the rules find are replaced before sending`() = runTest {
        var sent = ""
        classifier { _, prompt -> sent = prompt; "无" }.classify(page)
        assertThat(sent).contains("〔电话〕")
        assertThat(sent).doesNotContain("13800138000")
        assertThat(sent).contains("1: 收件人 王小明 〔电话〕\n2: 让张伟明天来拿\n")
        assertThat(sent).contains("已经打过码的号码")
    }

    @Test fun `what the model names is masked where it is on the page`() = runTest {
        val found = classifier { _, _ -> "人名|王小明\n人名|张伟" }.classify(page)
        assertThat(found.map { it.kind to it.source }.toSet())
            .containsExactly(SensitiveKind.PERSON_NAME to DetectorSource.LLM)
        assertThat(found).hasSize(2)
    }

    /** 没答上来要抛出去：编辑器才会说「识别失败」，而不是「没有新发现」。 */
    @Test fun `a failed request is an error, not an empty page`() = runTest {
        val boom = classifier { _, _ -> throw ExternalModelException(ExternalModelException.Reason.HTTP, 401, "Invalid API key") }
        val e = runCatching { boom.classify(page) }.exceptionOrNull()
        assertThat(e).isInstanceOf(ExternalModelException::class.java)
        assertThat((e as ExternalModelException).status).isEqualTo(401)
    }

    @Test fun `a request that never answers times out as an error`() = runTest {
        val slow = classifier { _, _ -> delay(10 * 60_000L); "无" }
        val e = runCatching { slow.classify(page) }.exceptionOrNull()
        assertThat((e as ExternalModelException).reason).isEqualTo(ExternalModelException.Reason.TIMEOUT)
    }

    @Test fun `it is available only when the settings are filled in`() = runTest {
        assertThat(classifier { _, _ -> "无" }.isAvailable()).isTrue()
        assertThat(classifier(on.copy(enabled = false)) { _, _ -> "无" }.isAvailable()).isFalse()
        assertThat(classifier(on.copy(model = "")) { _, _ -> "无" }.isAvailable()).isFalse()
    }

    /** 调试信息里记下发了什么、回了什么，但不记 key。 */
    @Test fun `the trace keeps the prompt and the reply but not the key`() = runTest {
        val log = TraceLog()
        val secret = on.copy(apiKey = "sk-very-secret")
        withContext(log.start("AI 增强识别")) { classifier(secret) { _, _ -> "人名|张伟" }.classify(page) }
        val text = log.runs.value.single().sections.joinToString("\n") { s ->
            listOf(s.title, s.summary).plus(s.lines).plus(s.blocks.map { it.text }).joinToString("\n")
        }
        assertThat(text).contains("外部模型 · 第 1/1 段")
        assertThat(text).contains("api.example.com")
        assertThat(text).contains("人名|张伟")
        assertThat(text).doesNotContain("sk-very-secret")
    }

    /** 外部模型开着、配好了：AI 增强识别用它，Nano 让开（不去碰 AICore）；没配好就还是 Nano。 */
    @Test fun `the nano backend steps aside when the external model is active`() = runTest {
        val config = RecognitionConfig()
        val (external, nano) = refinersFor(config) { on }
        assertThat(external.isAvailable()).isTrue()
        assertThat(nano.isAvailable()).isFalse()

        val (externalOff, _) = refinersFor(config) { on.copy(enabled = false) }
        assertThat(externalOff.isAvailable()).isFalse()
    }

    /** 文字各类全关了、聊天记录也没开：没有可找的，就不摆「AI 增强识别」。 */
    @Test fun `nothing to look for means no ai review`() {
        val allOff = RuleCatalog.all.fold(RecognitionConfig()) { c, rule -> c.withRule(rule.id, RuleState.OFF, RuleCatalog.factoryState(rule.id)) }
        assertThat(refinersFor(allOff) { on }).isEmpty()
    }
}
