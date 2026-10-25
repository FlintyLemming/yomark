package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.ID_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.LONG_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.PAYMENT_CARD
import moe.flinty.yomark.core.model.SensitiveKind.PHONE
import moe.flinty.yomark.core.model.SensitiveKind.VIN
import moe.flinty.yomark.core.model.TextLine
import moe.flinty.yomark.debug.textUnder
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 证件号码在整页上（出厂规则表、RuleClassifier）：同一处只出一个框，类型是证件号码。
 *
 * 身份证正面的几行照抄 PP-OCR 在合成身份证（tools/test-images/IdCardPage.java）上读出来的；
 * 用户照片那几行照抄真机反馈（转正之后），号码换成了编的。
 * 0.15.0 上合成身份证的号码只圈成「长数字串」，照片上的号码什么都没认出来。
 */
@RunWith(RobolectricTestRunner::class)
class IdNumberPageTest {

    private fun page(vararg texts: String) = textLines(*texts)

    private suspend fun found(lines: List<TextLine>): List<Triple<SensitiveKind, String, Boolean>> =
        RuleClassifier(DefaultRuleSet.rules).classify(lines).map { Triple(it.kind, it.textIn(lines), it.enabledByDefault) }

    private val numberKinds = setOf(ID_NUMBER, PAYMENT_CARD, LONG_NUMBER, PHONE, VIN)

    @Test fun `the synthetic id card front`() = runTest {
        val lines = IdDocumentRows.idCard.textLines()
        assertThat(
            RuleClassifier(DefaultRuleSet.rules).classify(lines).filter { it.kind in numberKinds }
                .map { Triple(it.kind, textUnder(it.quad, lines), it.enabledByDefault) }
        ).containsExactly(Triple(ID_NUMBER, "330106199307180423", true))
    }

    /** 台湾证件上的全角数字：整页看时换成半角再认，框照旧在原来那几个字上。 */
    @Test fun `full width digits`() = runTest {
        val lines = page("出生 民國７９年１月１日")
        assertThat(found(lines)).contains(Triple(SensitiveKind.BIRTH_DATE, "民國７９年１月１日", true))
    }

    /** 字段名被拆开、号码第一位粘在「码」上、中间补了空格：框从第一位到最后一位，空格一起框进去。 */
    @Test fun `the rotated photo from the user`() = runTest {
        val lines = page("名蒋馨悦", "性新女民 族汉", "出生201 1 年9 月25日", "公民 身份号 码3 202 01201109254526")
        assertThat(found(lines).filter { it.first in numberKinds })
            .containsExactly(Triple(ID_NUMBER, "3 202 01201109254526", true))
    }

    /** 同一张照片，号码认错一位：校验码对不上，靠被拆开的字段名认，照样只出一个证件号码的框。 */
    @Test fun `the photo with a misread digit`() = runTest {
        val lines = page("名蒋馨悦", "公民 身份号 码3 202 01201109254520")
        assertThat(found(lines).filter { it.first in numberKinds })
            .containsExactly(Triple(ID_NUMBER, "3 202 01201109254520", true))
    }

    /** 字段名后面末位是全角Ｘ的号码整个框；后面跟着的手机号另算电话，不并进号码里。 */
    @Test fun `a labeled number keeps its x and leaves the phone after it alone`() = runTest {
        val lines = page("身份证号 11010519491231002Ｘ", "身份证号 330106199307180423 +86 13800138000")
        assertThat(found(lines).filter { it.first in numberKinds }).containsExactly(
            Triple(ID_NUMBER, "11010519491231002Ｘ", true),
            Triple(ID_NUMBER, "330106199307180423", true),
            Triple(PHONE, "+86 13800138000", true),
        )
    }

    /** 身份证号十个里有一个碰巧过得了 Luhn：标证件号码，不再另标银行卡号。 */
    @Test fun `an id number that passes luhn is not a card`() = runTest {
        val lines = page("乘车人 110105199003071108")
        assertThat(found(lines).filter { it.first in numberKinds })
            .containsExactly(Triple(ID_NUMBER, "110105199003071108", true))
    }

    /** 原先落在长数字串里只圈不遮。长数字串也认带大写字母的，末位是 X 的一样让给证件号码。 */
    @Test fun `long numbers give way to id numbers`() = runTest {
        val lines = page("330106199307180423", "11010519491231002X", "2026090323001114571431156787")
        assertThat(found(lines).filter { it.first in numberKinds }).containsExactly(
            Triple(ID_NUMBER, "330106199307180423", true),
            Triple(ID_NUMBER, "11010519491231002X", true),
            Triple(LONG_NUMBER, "2026090323001114571431156787", false),
        )
    }

    /** 字段名后面 15 位的一代身份证号：也是一串长数字，同一处让给证件号码。 */
    @Test fun `a long number gives way to a labeled old id`() = runTest {
        val lines = page("身份证号 110105900307123")
        assertThat(found(lines).map { it.first to it.second }).containsExactly(ID_NUMBER to "110105900307123")
    }

    // ---------- 字段名和号码各成一段（同姓名、住址一样认，LabeledField） ----------

    private fun ids(p: Page) = DefaultRuleSet.rules.first { it.id == "idnumber" }.findOnPage(p).map { p.text(it) }

    /** 驾驶证的「证号」没有译名，号码在右边一栏，认错了一位（校验码对不上）：靠字段名接过去。 */
    @Test fun `a number in the column beside a bare label`() {
        val p = page(
            Row(144f, 43f, Segment("证号", 32f, 99f)),
            Row(150f, 30f, Segment("320105198602143728", 379f, 749f)),
        )
        assertThat(ids(p)).containsExactly("320105198602143728")
    }

    /** 永居证的「证件号码/Card No.」「证件号码/ID No.」、翻译件上的「证号 No.」：英文跳过去，同一行的、右边一栏的都认。 */
    @Test fun `bilingual id labels`() {
        val p = page(
            Row("证件号码/Card No. KAZ110090123105", 40f, 100f, 24f),
            Row(160f, 24f, Segment("证件号码/ID No.", 40f, 220f)),
            Row(160f, 24f, Segment("932682199001011230", 400f, 700f)),
            Row("证号 No. 320105198602143728", 40f, 220f, 24f),
        )
        assertThat(ids(p)).containsExactly("KAZ110090123105", "932682199001011230", "320105198602143728")
    }

    /** 单单一个字段名，右边是表头的下一格、没填的提示、打了星的号码：都不接。 */
    @Test fun `a bare label does not take just anything beside it`() {
        val p = page(
            Row(100f, 30f, Segment("证件号码", 60f, 180f)),
            Row(100f, 30f, Segment("姓名", 400f, 460f)),
            Row(160f, 30f, Segment("证件号码", 60f, 180f)),
            Row(160f, 30f, Segment("未填写 >", 400f, 520f)),
            Row(220f, 30f, Segment("身份证号", 60f, 180f)),
            Row(220f, 30f, Segment("3301**********0423", 400f, 700f)),
        )
        assertThat(ids(p)).isEmpty()
    }

    /** 名字下面一行是身份证号：证件号还是人名的旁证。 */
    @Test fun `an id number still anchors a guessed name`() = runTest {
        val lines = page("李思雨", "330106199307180423")
        assertThat(found(lines).map { it.first to it.second })
            .containsExactly(SensitiveKind.PERSON_NAME to "李思雨", ID_NUMBER to "330106199307180423")
    }
}
