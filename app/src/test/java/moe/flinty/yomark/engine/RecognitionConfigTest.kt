package moe.flinty.yomark.engine

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.rules.RuleCatalog
import org.junit.Test

class RecognitionConfigTest {

    @Test fun `factory default is pp-ocr`() {
        // 真机截图上中文识别明显好于 ML Kit，且能给出字符级的框；ML Kit 三档留作对比
        assertThat(RecognitionConfig().textEngine).isEqualTo(TextEngineOption.PADDLE)
    }

    @Test fun `factory default keeps barcode strict and face fast`() {
        assertThat(RecognitionConfig().barcode).isEqualTo(BarcodeOption.STRICT)
        assertThat(RecognitionConfig().face).isEqualTo(FaceOption.FAST)
    }

    /** 人脸、条码出厂都打码：设置里加了处理方式这一项，出厂行为不能跟着变。 */
    @Test fun `factory default masks faces and barcodes`() {
        assertThat(RecognitionConfig().faceState).isEqualTo(RuleState.MASKED)
        assertThat(RecognitionConfig().barcodeState).isEqualTo(RuleState.MASKED)
    }

    @Test fun `factory default overrides nothing`() {
        assertThat(RecognitionConfig().ruleOverrides).isEmpty()
    }

    // ---------- 规则：打码或关闭 ----------

    /** 出厂打码的那几类进引擎，出厂关着的（误报率高，原先只圈出）不进。 */
    @Test fun `no override runs exactly the rules that mask out of the box`() {
        val rules = RuleCatalog.rulesFor(RecognitionConfig())
        assertThat(rules.map { it.id })
            .containsExactlyElementsIn(RuleCatalog.all.filter { it.enabledByDefault }.map { it.id })
        assertThat(rules.all { it.enabledByDefault }).isTrue()
        assertThat(rules.map { it.id }).containsNoneOf("url", "ip", "longnum", "datetime")
    }

    @Test fun `OFF removes the rule from the engine entirely`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("longnum" to RuleState.OFF))
        assertThat(RuleCatalog.rulesFor(cfg).map { it.id }).doesNotContain("longnum")
    }

    @Test fun `MASKED turns on a rule that is off out of the box, and it masks`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("url" to RuleState.MASKED))
        val url = RuleCatalog.rulesFor(cfg).single { it.id == "url" }
        assertThat(url.enabledByDefault).isTrue()
    }

    /** 覆写只换打不打码，绝不能换掉规则本身的匹配行为。 */
    @Test fun `override preserves matching behaviour`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("url" to RuleState.MASKED))
        val url = RuleCatalog.rulesFor(cfg).single { it.id == "url" }
        val factory = RuleCatalog.all.single { it.id == "url" }
        assertThat(url.findIn("see https://example.com/a/b?c=1")).isEqualTo(factory.findIn("see https://example.com/a/b?c=1"))
        assertThat(url.findIn("see https://example.com/a/b?c=1")).isNotEmpty()
        assertThat(url.kind).isEqualTo(factory.kind)
    }

    @Test fun `unknown rule id in overrides is ignored`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("no-such-rule" to RuleState.MASKED))
        assertThat(RuleCatalog.rulesFor(cfg)).hasSize(RuleCatalog.rulesFor(RecognitionConfig()).size)
    }

    @Test fun `datetime ships in the catalog and starts off`() {
        assertThat(RuleCatalog.all.map { it.id }).contains("datetime")
        assertThat(RuleCatalog.factoryState("datetime")).isEqualTo(RuleState.OFF)
    }

    // ---------- 覆盖表只存差异 ----------

    @Test fun `setting a rule away from its factory state records an override`() {
        val cfg = RecognitionConfig().withRule("url", RuleState.MASKED, RuleCatalog.factoryState("url"))
        assertThat(cfg.ruleOverrides).containsExactly("url", RuleState.MASKED)
    }

    /** 改回出厂值要把这一项**删掉**，不能记一条「等于默认」的覆盖冻住老用户。 */
    @Test fun `setting a rule back to its factory state drops the override`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("url" to RuleState.MASKED))
            .withRule("url", RuleCatalog.factoryState("url"), RuleCatalog.factoryState("url"))
        assertThat(cfg.ruleOverrides).isEmpty()
    }

    @Test fun `changing one rule leaves the others alone`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("ip" to RuleState.OFF))
            .withRule("url", RuleState.MASKED, RuleCatalog.factoryState("url"))
        assertThat(cfg.ruleOverrides).containsExactly("ip", RuleState.OFF, "url", RuleState.MASKED)
    }

    // ---------- 序列化 ----------

    @Test fun `overrides survive a serialization round trip`() {
        val cfg = RecognitionConfig(
            textEngine = TextEngineOption.CHINESE,
            barcode = BarcodeOption.STRICT,
            faceState = RuleState.OFF,
            ruleOverrides = mapOf("url" to RuleState.MASKED, "longnum" to RuleState.OFF),
        )
        assertThat(RecognitionConfig.parseOverrides(cfg.encodeOverrides())).isEqualTo(cfg.ruleOverrides)
    }

    @Test fun `empty overrides encode to empty string`() {
        assertThat(RecognitionConfig().encodeOverrides()).isEmpty()
        assertThat(RecognitionConfig.parseOverrides("")).isEmpty()
    }

    @Test fun `garbage in the persisted overrides is dropped, not thrown`() {
        val parsed = RecognitionConfig.parseOverrides("url:MASKED;garbage;card:NOT_A_STATE;longnum:OFF")
        assertThat(parsed).containsExactly("url", RuleState.MASKED, "longnum", RuleState.OFF)
    }

    /** 快递单号并进了长数字串：打开过快递单号的，读成长数字串打开；长数字串自己改过的照旧。 */
    @Test fun `a tracking override carries over to long numbers`() {
        assertThat(RecognitionConfig.parseOverrides("tracking:MASKED;url:MASKED"))
            .containsExactly("longnum", RuleState.MASKED, "url", RuleState.MASKED)
        assertThat(RecognitionConfig.parseOverrides("tracking:OFF")).isEmpty()
        assertThat(RecognitionConfig.parseOverrides("tracking:MASKED;longnum:OFF")).containsExactly("longnum", RuleState.OFF)
        assertThat(RuleCatalog.stateOf(RecognitionConfig(ruleOverrides = RecognitionConfig.parseOverrides("tracking:MASKED")), "longnum"))
            .isEqualTo(RuleState.MASKED)
    }

    /** 去掉了的「仅圈出」读成关闭：它本来就不打码。 */
    @Test fun `a persisted outline-only override reads as off`() {
        assertThat(RecognitionConfig.parseOverrides("card:OUTLINED")).containsExactly("card", RuleState.OFF)
        assertThat(RuleState.fromName("OUTLINED")).isEqualTo(RuleState.OFF)
        assertThat(RuleState.fromName("MASKED")).isEqualTo(RuleState.MASKED)
        assertThat(RuleState.fromName("NOT_A_STATE")).isNull()
    }
}
