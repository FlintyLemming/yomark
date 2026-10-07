package com.yomark.app.rules

import com.yomark.app.core.model.SensitiveKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 取件码（菜鸟快递详情页实测漏检）。看到它的人就能去驿站把包裹取走。
 */
class PickupCodeRuleTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "pickup" }

    private fun codes(text: String): List<String> = rule.findIn(text).map { text.substring(it.range) }

    @Test fun `the pickup code of a logistics page`() {
        assertThat(codes("取件码 1-58908 复制")).containsExactly("1-58908")
    }

    @Test fun `the same line when the recognizer splits every character`() {
        assertThat(codes("取 件 码 1-58908 复 制")).containsExactly("1-58908")
    }

    @Test fun `other labels and code shapes`() {
        assertThat(codes("取件码：A12-3-4567")).containsExactly("A12-3-4567")
        assertThat(codes("提货码 80503421")).containsExactly("80503421")
    }

    /** 说明文字里的「取货码」后面跟的不是码。 */
    @Test fun `instructions mentioning the code are not codes`() {
        assertThat(codes("站，请凭取货码及时领取。如有疑问请联系...展开")).isEmpty()
        assertThat(codes("取件码 复制")).isEmpty()
    }

    @Test fun `it is masked by default`() {
        assertThat(rule.kind).isEqualTo(SensitiveKind.PICKUP_CODE)
        assertThat(rule.enabledByDefault).isTrue()
    }
}
