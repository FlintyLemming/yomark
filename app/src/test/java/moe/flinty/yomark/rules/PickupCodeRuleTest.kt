package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind
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
        assertThat(codes("请凭取件码取件")).isEmpty()
        assertThat(codes("取件码是多少")).isEmpty()
    }

    /**
     * 短信、聊天里字段名写在句子中间：前面紧挨着汉字，和码之间隔着「为」「是」。
     * 测评页上的原文见 PickupCodeSmsRegressionTest。
     */
    @Test fun `a label in the middle of a sentence`() {
        assertThat(codes("凭取件码36920471取件")).containsExactly("36920471")
        assertThat(codes("您的取件码为 6-2-3001，请尽快领取")).containsExactly("6-2-3001")
        assertThat(codes("取件码是A12-3-4567")).containsExactly("A12-3-4567")
        assertThat(codes("放好了，柜子取餐码 5-0318")).containsExactly("5-0318")
    }

    /** 不写字段名：码紧跟在「凭」后面，接着说怎么取。 */
    @Test fun `a code the text says to collect with needs no label`() {
        assertThat(codes("请凭8-5-2001于21:00前取件")).containsExactly("8-5-2001")
        assertThat(codes("请凭1-2-3456及时取件")).containsExactly("1-2-3456")
        assertThat(codes("凭12345678开柜取件")).containsExactly("12345678")
    }

    /** 「凭」后面的数字不都是码：年份、积分、优惠码，后面接的不是怎么取，或者这一句没说取件。 */
    @Test fun `years, points and coupons are not pickup codes`() {
        listOf("凭2024年发票领取", "凭1000积分领取", "凭8888到店享八折", "凭8888到店，否则取消", "凭1张券领取", "凭身份证领取")
            .forEach { assertThat(codes(it)).isEmpty() }
    }

    @Test fun `it is masked by default`() {
        assertThat(rule.kind).isEqualTo(SensitiveKind.PICKUP_CODE)
        assertThat(rule.enabledByDefault).isTrue()
    }
}
