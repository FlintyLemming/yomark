package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test
import java.time.LocalDate

/**
 * 证件号码（逐行）：字段名后面的号码，和没有字段名也认的几种。
 *
 * 号码都是编的或公开样例：GB 11643 附录的「11010519491231002X」、移民局的永居证样张「932682198501010017」，
 * 合成身份证上的「330106199307180423」，用户照片里那一行（号码改成了编的「320201201109254526」）。
 */
class IdNumberRuleTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "idnumber" }

    private fun matched(text: String) = rule.findIn(text).map { text.substring(it.range) }

    // ---------- 大陆身份证 ----------

    @Test fun `a resident id number with its label and on its own`() {
        assertThat(matched("公民身份号码 330106199307180423")).containsExactly("330106199307180423")
        assertThat(matched("330106199307180423")).containsExactly("330106199307180423")
        assertThat(matched("乘车人证件 11010519491231002X 成人票")).containsExactly("11010519491231002X")
    }

    /** 用户照片：字段名被拆开，号码第一位粘在「码」上，中间补了空格。框从第一位数字到最后一位。 */
    @Test fun `the ocr spaced number from the user photo`() {
        assertThat(matched("公民 身份号 码3 202 01201109254526")).containsExactly("3 202 01201109254526")
        assertThat(matched("公民身份号码 110105 19900307 1239")).containsExactly("110105 19900307 1239")
    }

    /** 末位 X 的各种读法：小写、乘号、全角，和单独隔开的一个 X。 */
    @Test fun `the check character x in any of its lookalikes`() {
        listOf("11010519491231002X", "11010519491231002x", "11010519491231002×", "11010519491231002Ｘ")
            .forEach { assertThat(matched("号码 $it")).containsExactly(it) }
        assertThat(ResidentIdNumber.findAll("11010519491231002 X").single().number).isEqualTo("11010519491231002X")
    }

    @Test fun `residence permits and the 2023 foreigner card use the same numbers`() {
        assertThat(matched("810000199408230021")).hasSize(1)
        assertThat(matched("82000019880102001X")).hasSize(1)
        assertThat(matched("证件号码/ID No. 932682198501010017")).containsExactly("932682198501010017")
    }

    /** 三道关：校验码、生日、省份，各弄坏一样都不认。 */
    @Test fun `a broken check digit date or region is not an id on its own`() {
        assertThat(matched("330106199307180424")).isEmpty()          // 校验码
        assertThat(matched("330106199313180427")).isEmpty()          // 13 月
        assertThat(matched("330106203007180423")).isEmpty()          // 还没到的生日
        assertThat(matched("990106199307180423")).isEmpty()          // 没有 99 这个省
        assertThat(ResidentIdNumber.isValid("330106199307180423", today = LocalDate.of(1993, 7, 17))).isFalse()
    }

    /** 只在段与段之间切：20 位的时间戳、更长的数字串里不切出号码；前后紧挨字母的不是独立的号码。 */
    @Test fun `no id is cut out of a longer run`() {
        assertThat(matched("20330106199307180423")).isEmpty()
        assertThat(matched("3301061993071804231")).isEmpty()
        assertThat(matched("ID330106199307180423")).isEmpty()
        assertThat(matched("330106199307180423A")).isEmpty()
        // 前后各有一段别的数字，号码自己是完整的一段
        assertThat(matched("1 330106199307180423 2026")).containsExactly("330106199307180423")
    }

    @Test fun `the birth date inside the number`() {
        assertThat(ResidentIdNumber.birthDate("330106199307180423")).isEqualTo(LocalDate.of(1993, 7, 18))
        assertThat(ResidentIdNumber.birthDate("330106199302300423")).isNull()
    }

    // ---------- 字段名后面的号码 ----------

    /** 字段名后面的不验校验位：OCR 认错一位照样遮。一代身份证、档案编号、通行证号只在这里认。 */
    @Test fun `numbers after a label need no check digit`() {
        assertThat(matched("身份证号：330106199307180424")).containsExactly("330106199307180424")
        assertThat(matched("身份证号 110105900307123")).containsExactly("110105900307123")
        assertThat(matched("证号 11010519491231002X")).containsExactly("11010519491231002X")
        assertThat(matched("档案编号 320100000123")).containsExactly("320100000123")
        assertThat(matched("通行证号码 H12345678")).containsExactly("H12345678")
        assertThat(matched("社会保障卡号 A01234567")).containsExactly("A01234567")
        assertThat(matched("结婚证字号 J320102-2015-001234")).containsExactly("J320102-2015-001234")
        assertThat(matched("学号 2020123456")).containsExactly("2020123456")
        assertThat(matched("户号 1234567890")).containsExactly("1234567890")
        assertThat(matched("毕业证书编号 103351202605001234")).containsExactly("103351202605001234")
    }

    @Test fun `traditional japanese and english labels`() {
        assertThat(matched("統一編號 A123456788")).containsExactly("A123456788")
        assertThat(matched("個人番号 1234 5678 9018")).containsExactly("1234 5678 9018")
        assertThat(matched("マイナンバー 123456789018")).containsExactly("123456789018")
        assertThat(matched("ID No.: S1234567A")).containsExactly("S1234567A")
        assertThat(matched("National Insurance number AB 12 34 56 C")).containsExactly("AB 12 34 56 C")
        assertThat(matched("NI number: QQ123456C")).containsExactly("QQ123456C")
        assertThat(matched("Social Insurance Number 130 456 783")).containsExactly("130 456 783")
        assertThat(matched("Your Aadhaar No. 2345 6789 0124")).containsExactly("2345 6789 0124")
        assertThat(matched("DNI 12345678Z")).containsExactly("12345678Z")
        assertThat(matched("Identity Card No. A123456(4)")).containsExactly("A123456(4)")
    }

    /** 值在下一个不像号码的词前停下，在汉字前停下；字段名前面紧挨着别的字的不是字段名。 */
    @Test fun `where a labeled value stops and what is not a label`() {
        assertThat(matched("ID No. 123456 VALID")).containsExactly("123456")
        assertThat(matched("证件号码330106199307180424有效期")).containsExactly("330106199307180424")
        assertThat(matched("保证号 12345678")).isEmpty()
        assertThat(matched("凭证号 12345678")).isEmpty()
        assertThat(matched("客户号 12345678")).isEmpty()
        assertThat(matched("学号 1234")).isEmpty()                    // 不到五位数字
        assertThat(matched("FINLAND 12345")).isEmpty()
        assertThat(matched("证件号码：护照")).isEmpty()
    }

    /** 打了大半星的号码是截图的 app 已经遮过的，不另框；只打了几位的照样遮。 */
    @Test fun `starred numbers`() {
        assertThat(matched("身份证号 3201**********1234")).isEmpty()
        assertThat(matched("证件号码 1***************9")).isEmpty()
        assertThat(matched("身份证号 33010619930718****")).containsExactly("33010619930718****")
    }

    // ---------- 港台、外国的号码 ----------

    @Test fun `hong kong ids need the brackets and the check digit`() {
        assertThat(matched("A123456(3)")).containsExactly("A123456(3)")
        assertThat(matched("WX123456（9）")).containsExactly("WX123456（9）")
        assertThat(matched("G123456 (A)")).containsExactly("G123456 (A)")
        assertThat(matched("A123456(4)")).isEmpty()
        assertThat(matched("A1234563")).isEmpty()
    }

    @Test fun `taiwan ids and resident certificates`() {
        listOf("A123456789", "B120667503", "A800000014", "F812345676", "AA12345675", "FC12345674")
            .forEach { assertThat(matched("號碼 $it")).containsExactly(it) }
        assertThat(matched("A123456788")).isEmpty()
        assertThat(matched("A323456789")).isEmpty()                 // 第二位不是 1、2、8、9、A–D
    }

    /** 12306 的订单号也是一个字母加九位数字，「E100000005」碰巧过得了台湾身份证的校验；跟在「订单号」后面的不算。 */
    @Test fun `letter led numbers after an order hint`() {
        assertThat(matched("E100000005")).containsExactly("E100000005")
        assertThat(matched("订单号：E100000005")).isEmpty()
        assertThat(matched("Order S1234567D")).isEmpty()
        assertThat(matched("型号 AB12345678CD")).isEmpty()
    }

    @Test fun `singapore nric and fin`() {
        listOf("S1234567D", "T1234567J", "F1234567N", "G1234567X", "M1234567K").forEach { assertThat(matched(it)).containsExactly(it) }
        assertThat(matched("M1234567B")).isEmpty()                  // ICA 新闻稿里的示意号码，校验位不对
    }

    @Test fun `korean resident registration numbers`() {
        assertThat(matched("800101-1234560")).containsExactly("800101-1234560")
        assertThat(matched("주민등록번호 900101 - 2345670")).containsExactly("900101 - 2345670")
        assertThat(matched("801301-1234560")).isEmpty()             // 13 月
        assertThat(matched("8001011234560")).isEmpty()              // 没有连字符
        assertThat(matched("800101-9234560")).isEmpty()             // 第七位只有 1–8
    }

    @Test fun `malaysian mykad numbers`() {
        assertThat(matched("900101-14-5677")).containsExactly("900101-14-5677")
        assertThat(matched("900101-00-5677")).isEmpty()             // 00 不是出生地代码
        assertThat(matched("900141-14-5677")).isEmpty()
    }

    @Test fun `japanese residence cards and brazilian cpf`() {
        assertThat(matched("番号 AB12345678CD")).containsExactly("AB12345678CD")
        assertThat(matched("CPF: 529.982.247-25")).containsExactly("529.982.247-25")
        assertThat(matched("529.982.247-25")).containsExactly("529.982.247-25")
        assertThat(matched("529.982.247-26")).isEmpty()
        assertThat(matched("111.111.111-11")).isEmpty()
    }

    @Test fun `masked by default as its own kind`() {
        assertThat(rule.enabledByDefault).isTrue()
        assertThat(rule.kind).isEqualTo(SensitiveKind.ID_NUMBER)
        assertThat(rule.yieldsTo).containsExactly(SensitiveKind.PASSPORT)
    }

    // ---------- 银行卡的新认法：安全码、有效期 ----------

    private val card = DefaultRuleSet.rules.first { it.id == "card" }

    private fun cardMatched(text: String) = card.findIn(text).map { text.substring(it.range) }

    @Test fun `card security codes and expiry dates after their labels`() {
        assertThat(cardMatched("CVV2: 123")).containsExactly("123")
        assertThat(cardMatched("安全码 4321")).containsExactly("4321")
        assertThat(cardMatched("VALID THRU 12/28")).containsExactly("12/28")
        assertThat(cardMatched("Expiry Date: 09/2031")).containsExactly("09/2031")
        assertThat(cardMatched("有效期至 06/29")).containsExactly("06/29")
    }

    /** 身份证背面的「有效期限」、驾照护照上的完整日期都不是卡的有效期。 */
    @Test fun `validity periods of ids are not card expiry dates`() {
        assertThat(cardMatched("有效期限 2015.08.20-2035.08.20")).isEmpty()
        assertThat(cardMatched("有效期至 2029.05.31")).isEmpty()
        assertThat(cardMatched("有效期 2026/12/31")).isEmpty()
        assertThat(cardMatched("Expiry 10/14/2030")).isEmpty()
        assertThat(cardMatched("CVV 12")).isEmpty()
        assertThat(cardMatched("Experience 10/20")).isEmpty()
    }
}
