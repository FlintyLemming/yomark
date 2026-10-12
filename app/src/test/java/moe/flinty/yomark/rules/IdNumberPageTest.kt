package moe.flinty.yomark.rules

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.SensitiveKind.ID_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.LICENSE_PLATE
import moe.flinty.yomark.core.model.SensitiveKind.LONG_NUMBER
import moe.flinty.yomark.core.model.SensitiveKind.PAYMENT_CARD
import moe.flinty.yomark.core.model.SensitiveKind.PHONE
import moe.flinty.yomark.core.model.SensitiveKind.VIN
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
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

    /** 每个字一个 element（PP-OCR）：字宽 20px、字高 20px，行距 40px。 */
    private fun page(vararg texts: String): List<TextLine> = texts.mapIndexed { row, text ->
        val top = 100f + row * 40f
        val els = text.indices.filter { text[it] != ' ' }.map { i ->
            TextElement(Quad.fromRect(RectF(40f + i * 20f, top, 60f + i * 20f, top + 20f)), text[i].toString(), i..i)
        }
        TextLine(Quad.fromRect(RectF(40f, top, 40f + text.length * 20f, top + 20f)), text, 0.95f, els)
    }

    private fun Candidate.text(lines: List<TextLine>): String {
        val b = quad.bounds()
        val line = lines.single { it.quad.bounds().top == b.top }
        val first = ((b.left - 40f) / 20f).toInt()
        val last = ((b.right - 40f) / 20f).toInt() - 1
        return line.text.substring(first, last + 1)
    }

    private suspend fun found(lines: List<TextLine>): List<Triple<SensitiveKind, String, Boolean>> =
        RuleClassifier(DefaultRuleSet.rules).classify(lines).map { Triple(it.kind, it.text(lines), it.enabledByDefault) }

    private val numberKinds = setOf(ID_NUMBER, PAYMENT_CARD, LONG_NUMBER, PHONE, LICENSE_PLATE, VIN)

    @Test fun `the synthetic id card front`() = runTest {
        val lines = page(
            "姓 名 林知夏",
            "性 别 女 民 族 漢",
            "出 生 1993 年 7 月 18 日",
            "住 址 浙江省杭州市西湖区文三路",
            "88号6幢2单元501 室",
            "公民身份号码 330106199307180423",
        )
        assertThat(found(lines).filter { it.first in numberKinds })
            .containsExactly(Triple(ID_NUMBER, "330106199307180423", true))
    }

    /** 字段名被拆开、号码第一位粘在「码」上、中间补了空格：框从第一位到最后一位，空格一起框进去。 */
    @Test fun `the rotated photo from the user`() = runTest {
        val lines = page("名蒋馨悦", "性新女民 族汉", "出生201 1 年9 月25日", "公民 身份号 码3 202 01201109254526")
        assertThat(found(lines).filter { it.first in numberKinds })
            .containsExactly(Triple(ID_NUMBER, "3 202 01201109254526", true))
    }

    /** 身份证号十个里有一个碰巧过得了 Luhn：标证件号码，不再另标银行卡号。 */
    @Test fun `an id number that passes luhn is not a card`() = runTest {
        val lines = page("乘车人 110105199003071108")
        assertThat(found(lines).filter { it.first in numberKinds })
            .containsExactly(Triple(ID_NUMBER, "110105199003071108", true))
    }

    /** 原先落在长数字串里只圈不遮；末位是 X 的连长数字串都不是。 */
    @Test fun `long numbers give way to id numbers`() = runTest {
        val lines = page("330106199307180423", "11010519491231002X", "2026090323001114571431156787")
        assertThat(found(lines).filter { it.first in numberKinds }).containsExactly(
            Triple(ID_NUMBER, "330106199307180423", true),
            Triple(ID_NUMBER, "11010519491231002X", true),
            Triple(LONG_NUMBER, "2026090323001114571431156787", false),
        )
    }

    /** 字段名后面 15 位的一代身份证号：快递单号也是 15 位，同一处让给证件号码。 */
    @Test fun `tracking gives way to a labeled old id`() = runTest {
        val lines = page("身份证号 110105900307123")
        assertThat(found(lines).map { it.first to it.second }).containsExactly(ID_NUMBER to "110105900307123")
    }

    /** 名字下面一行是身份证号：证件号还是人名的旁证。 */
    @Test fun `an id number still anchors a guessed name`() = runTest {
        val lines = page("李思雨", "330106199307180423")
        assertThat(found(lines).map { it.first to it.second })
            .containsExactly(SensitiveKind.PERSON_NAME to "李思雨", ID_NUMBER to "330106199307180423")
    }
}
