package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 银行卡背面没有字段名的安全码（CardBackCode），和字段名自己成一段的安全码（LoneLabel），按出厂规则表跑银行卡一类。
 * 版面照着合成的卡背面（tools/test-images/CardBackPage.java）上 PP-OCR 读出来的框排；真模型从像素读起的见 CardBackOcrTest。
 */
class CardBackCodeTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "card" }

    private fun found(page: Page): List<String> = rule.findOnPage(page).map { page.text(it) }

    private val strip = Row(191f, 27f, Segment("持卡人签名 AUTHORIZED SIGNATURE", 56f, 385f))

    /** 深色的银联卡背面：签名条上面一行「持卡人签名 AUTHORIZED SIGNATURE」，条的右头是卡号末四位和安全码。 */
    private fun chineseBack(code: Row = Row(258f, 40f, Segment("8810 357", 527f, 688f)), wording: Boolean = true) =
        page(
            *(listOfNotNull(strip.takeIf { wording }, code) + listOf(
                Row(370f, 27f, Segment("本卡仅限持卡人本人使用，请妥善保管，不得转借他人。", 57f, 610f)),
                Row(456f, 31f, Segment("客户服务热线 96000", 57f, 280f), Segment("www.example.com.cn", 330f, 560f)),
                Row(547f, 34f, Segment("示例银行 EXAMPLE BANK", 58f, 413f)),
            )).toTypedArray()
        )

    @Test fun `the code at the end of a chinese signature strip`() {
        assertThat(found(chineseBack())).containsExactly("8810 357")
    }

    /** Visa 卡背面：签名条上下各一行小字，安全码单独印在条右边的白框里。 */
    @Test fun `the code in the white box beside the strip`() {
        val p = page(
            Row(192f, 19f, Segment("AUTHORIZED SIGNATURE", 57f, 274f)),
            Row(248f, 43f, Segment("482", 697f, 773f)),
            Row(325f, 22f, Segment("NOT VALID UNLESS SIGNED", 56f, 273f)),
            Row(382f, 25f, Segment("This card is issued by Example Bank, N.A. pursuant to a license from Visa U.S.A. Inc.", 56f, 739f)),
            Row(460f, 25f, Segment("Customer Service 1-800-555-0199", 57f, 367f)),
        )
        assertThat(found(p)).containsExactly("482")
    }

    /** 只有条下面一行「NOT VALID UNLESS SIGNED」读出来了。 */
    @Test fun `a strip with its wording only below`() {
        val p = page(
            Row(248f, 43f, Segment("482", 697f, 773f)),
            Row(325f, 22f, Segment("NOT VALID UNLESS SIGNED", 56f, 273f)),
            Row(382f, 25f, Segment("Use of this card is subject to the Cardholder Agreement.", 56f, 507f)),
        )
        assertThat(found(p)).containsExactly("482")
    }

    /** 「持卡人签名」印在条里的左头，码在同一排的右头，OCR 分成两行。 */
    @Test fun `the wording inside the strip on the same row`() {
        val p = page(
            Row(262f, 18f, Segment("持卡人签名", 80f, 170f)),
            Row(250f, 38f, Segment("357", 600f, 660f)),
            Row(547f, 34f, Segment("示例银行", 58f, 200f)),
        )
        assertThat(found(p)).containsExactly("357")
    }

    /** 斜体的字边上 OCR 多读出一撇：只遮数字。 */
    @Test fun `a stray stroke beside the italic digits`() {
        assertThat(found(chineseBack(Row(258f, 40f, Segment("/357", 600f, 680f))))).containsExactly("357")
    }

    /** 末四位和安全码隔得开，读成了两段：安全码照遮，末四位不是安全码。 */
    @Test fun `the last four apart from the code`() {
        val code = Row(258f, 40f, Segment("8810", 450f, 530f), Segment("357", 600f, 660f))
        assertThat(found(chineseBack(code))).containsExactly("357")
    }

    /** 签名条上的字没读出来：「末四位 三位」页上有银行就认，单独的三位不认。 */
    @Test fun `the last four and the code without the strip wording`() {
        assertThat(found(chineseBack(wording = false))).containsExactly("8810 357")
        assertThat(found(chineseBack(Row(258f, 40f, Segment("357", 600f, 660f)), wording = false))).isEmpty()
    }

    /** 竖版卡：「CVV2」自己成一段，码在它下面一行；横着排的，码在同一排右边隔得老远。 */
    @Test fun `a code below or beside a label of its own`() {
        assertThat(found(page(Row("CVV2", 300f, 600f), Row("123", 300f, 628f, 24f)))).containsExactly("123")
        assertThat(found(page(Row(0f, 20f, Segment("CVV", 40f, 76f)), Row(0f, 30f, Segment("482", 400f, 454f)))))
            .containsExactly("482")
    }

    // ---------- 不是卡背面的安全码 ----------

    /** 快递单、表格上也印着「签名」，旁边格子里的三位数是件数、金额：页上没有卡。 */
    @Test fun `a signature box on a form with no card`() {
        val p = page(
            Row(100f, 24f, Segment("收件人签名", 40f, 160f)),
            Row(130f, 30f, Segment("120", 400f, 454f)),
            Row(180f, 24f, Segment("寄件人签名", 40f, 160f)),
        )
        assertThat(found(p)).isEmpty()
    }

    /** 离签名条远了：卡下面的三位数不是条上的码。 */
    @Test fun `a number far from the strip`() {
        val far = Row(547f, 34f, Segment("示例银行", 58f, 200f), Segment("357", 600f, 660f))
        assertThat(found(chineseBack(far))).isEmpty()
    }

    /** 同一条水平线上，隔着好几张卡宽。 */
    @Test fun `a number far to the side of the strip`() {
        assertThat(found(chineseBack(Row(258f, 40f, Segment("357", 2400f, 2460f))))).isEmpty()
    }

    /** 银行回单：「柜员号：」和号码之间隔着一格，号码是那个字段的值；不是只有三位数字的也不算。 */
    @Test fun `a value after a colon or with more than the code in it`() {
        val p = page(
            Row(100f, 24f, Segment("示例银行 电子回单", 40f, 250f)),
            Row(150f, 24f, Segment("柜员号：", 40f, 136f), Segment("123", 300f, 345f), Segment("客户签名", 500f, 596f)),
            Row(190f, 24f, Segment("金额", 40f, 88f), Segment("¥123", 300f, 360f), Segment("No.456", 500f, 572f)),
        )
        assertThat(found(p)).isEmpty()
    }

    /** 签购单：「Signature」上面是金额、卡号末四位，金额带着小数。 */
    @Test fun `a receipt with a signature line`() {
        val p = page(
            Row(100f, 24f, Segment("VISA ****1234", 40f, 220f)),
            Row(140f, 24f, Segment("TOTAL", 40f, 110f), Segment("$112.00", 400f, 500f)),
            Row(180f, 24f, Segment("Signature", 40f, 160f)),
        )
        assertThat(found(p)).isEmpty()
    }

    /** 四位数不是安全码的样子；「末四位 三位」页上没有卡也不认。 */
    @Test fun `other shapes`() {
        assertThat(found(chineseBack(Row(258f, 40f, Segment("8810", 600f, 680f))))).isEmpty()
        assertThat(found(page(Row(100f, 24f, Segment("签名", 40f, 88f)), Row(130f, 30f, Segment("8810 357", 300f, 440f))))).isEmpty()
    }
}
