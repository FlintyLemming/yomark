package moe.flinty.yomark.rules

import moe.flinty.yomark.core.model.SensitiveKind.DATETIME
import moe.flinty.yomark.core.model.SensitiveKind.PICKUP_CODE
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 驿站、快递柜短信里的取件码（测评页 zsms-pickup-codes、zsms-lockscreen-stack 上的短信，文字照抄
 * tools/model-eval/nano-pages-zh.json）。
 *
 * 短信里字段名写在句子中间：「凭取件码36920471取件」的「取件码」前面紧挨着「凭」，原先当它是别的词的一截，不认；
 * 「请凭8-5-2001于21:00前取件」「请凭3-2-1234到…领取」干脆不写字段名。出厂设置下这三个码都露着，
 * 「8-5-2001」只被出厂关着的日期时间当成了日期。
 *
 * 同 LogisticsPageRegressionTest，逐字切分的行要认出一样的码。
 */
@RunWith(RobolectricTestRunner::class)
class PickupCodeSmsRegressionTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "pickup" }

    /** 一条短信和它里面的取件码。 */
    private val texts = listOf(
        "【菜鸟驿站】您的中通包裹已到科技园东门菜鸟驿站，请凭8-5-2001于21:00前取件，询13651873320" to "8-5-2001",
        "【丰巢】您的顺丰快递SF1398025517740已存入幸福里小区7号楼丰巢柜，凭取件码36920471取件，超时将收取保管费。" to "36920471",
        "【兔喜生活】您的韵达快递已到幸福里小区南门兔喜快递超市，货架号B3，取件码B3-1107，营业至22:00。" to "B3-1107",
        "【菜鸟驿站】王丽娟您好，您的京东包裹JDVA28816037452已到站，请凭3-2-1234到科技园东门菜鸟驿站领取。" to "3-2-1234",
        "【丰巢】您的快递已存入丰巢柜，取件码 74183025，请及时领取" to "74183025",
    )

    private fun codes(text: String): List<String> = rule.findIn(text).map { text.substring(it.range) }

    /** 把每个汉字拆成独立 token，数字、拉丁、标点连成一段——中文识别器逐字切分时的样子。 */
    private fun splitEveryCharacter(line: String): String =
        Regex("""[一-鿿]|[^\s一-鿿]+""").findAll(line).joinToString(" ") { it.value }

    @Test fun `every pickup code in the texts is found`() {
        texts.forEach { (text, code) -> assertThat(codes(text)).containsExactly(code) }
    }

    @Test fun `the same texts split into single characters give the same codes`() {
        texts.forEach { (text, code) -> assertThat(codes(splitEveryCharacter(text))).containsExactly(code) }
    }

    /**
     * 出厂设置下整页认一遍：每个码都是取件码、都打码。「8-5-2001」也像日期，同一处日期时间让给取件码，不另圈一个。
     */
    @Test fun `the codes are masked with factory settings`() = runTest {
        val lines = textLines(*texts.map { it.first }.toTypedArray())

        val out = RuleClassifier(DefaultRuleSet.rules).classify(lines)

        val pickup = out.filter { it.kind == PICKUP_CODE }
        assertThat(pickup.map { it.textIn(lines) }).containsExactlyElementsIn(texts.map { it.second })
        assertThat(pickup.all { it.enabledByDefault }).isTrue()
        assertThat(out.filter { it.kind == DATETIME }.map { it.textIn(lines) }).doesNotContain("8-5-2001")
    }
}
