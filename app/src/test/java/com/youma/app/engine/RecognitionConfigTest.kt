package com.youma.app.engine

import com.google.common.truth.Truth.assertThat
import com.youma.app.rules.RuleCatalog
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

    // ---------- 规则三态 ----------

    @Test fun `no override yields the catalog factory states`() {
        val rules = RuleCatalog.rulesFor(RecognitionConfig())
        assertThat(rules.map { it.id }).containsExactlyElementsIn(RuleCatalog.all.map { it.id })
        rules.forEach { r ->
            assertThat(r.enabledByDefault).isEqualTo(RuleCatalog.factoryState(r.id) == RuleState.MASKED)
        }
    }

    @Test fun `OFF removes the rule from the engine entirely`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("longnum" to RuleState.OFF))
        assertThat(RuleCatalog.rulesFor(cfg).map { it.id }).doesNotContain("longnum")
    }

    @Test fun `MASKED promotes an outlined-by-default rule`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("url" to RuleState.MASKED))
        val url = RuleCatalog.rulesFor(cfg).single { it.id == "url" }
        assertThat(url.enabledByDefault).isTrue()
    }

    @Test fun `OUTLINED demotes a masked-by-default rule`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("card" to RuleState.OUTLINED))
        val card = RuleCatalog.rulesFor(cfg).single { it.id == "card" }
        assertThat(card.enabledByDefault).isFalse()
    }

    /** 覆写只换状态，绝不能换掉规则本身的匹配行为。 */
    @Test fun `override preserves matching behaviour`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("card" to RuleState.OUTLINED))
        val card = RuleCatalog.rulesFor(cfg).single { it.id == "card" }
        assertThat(card.findIn("4111 1111 1111 1111")).hasSize(1)
        assertThat(card.kind).isEqualTo(RuleCatalog.all.single { it.id == "card" }.kind)
    }

    @Test fun `unknown rule id in overrides is ignored`() {
        val cfg = RecognitionConfig(ruleOverrides = mapOf("no-such-rule" to RuleState.MASKED))
        assertThat(RuleCatalog.rulesFor(cfg)).hasSize(RuleCatalog.all.size)
    }

    @Test fun `datetime ships in the catalog and starts outlined`() {
        assertThat(RuleCatalog.all.map { it.id }).contains("datetime")
        assertThat(RuleCatalog.factoryState("datetime")).isEqualTo(RuleState.OUTLINED)
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
}
