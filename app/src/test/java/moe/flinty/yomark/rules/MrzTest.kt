package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 机读码：一行里认出它（MrzLines），拿它去对照页上别处（MrzEcho）。
 *
 * 样例是美国护照样张（Happy Traveler）真实 OCR 的样子，ICAO 9303 的 Utopia 样例，和它们故意弄坏的变体。
 */
class MrzTest {

    private val passport = DefaultRuleSet.rules.first { it.id == "passport" }

    private fun mrz(text: String) = MrzLines(1f).findIn(text).map { text.substring(it.range) }

    /** 美国护照样张的两行，照 PP-OCRv6 small 读出来的原样：第二行窄的「1」两边被补了空格。 */
    private val line1 = "P<USATRAVELER<<HAPPY<<<<<<<<<<<<<<<<<<<<<<<<"
    private val line2 = "E000077303USA6502056F301 01 49500101920<091 824"

    @Test fun `spaces the ocr put into the second line do not matter`() {
        assertThat(mrz(line2)).containsExactly(line2)
    }

    /** 填充符多数一个、少数几个，都还是姓名那一行。 */
    @Test fun `a miscounted filler run is still the name line`() {
        assertThat(mrz("P<USATRAVELER<<HAPPY<<<<<<<<<<<<<<<<<<<<<<")).hasSize(1)
        assertThat(mrz("P<USATRAVELER<<HAPPY<<<<<<<<<<<<<<<<<<<<<<<<<<<")).hasSize(1)
    }

    /** 以前 OCR 在每个字之间都补了空格（「P< U S A T R A V E L E R < <」），去掉空格照样认。 */
    @Test fun `a line spaced out letter by letter`() {
        val spaced = "P< U S A T R A V E L E R < < H A P P Y < < <<< <<< <<< <<< < < < < < < <<< <"
        assertThat(mrz(spaced)).containsExactly(spaced)
    }

    /** 身份证（TD1）三行各 30 个字符。 */
    @Test fun `the three lines of an id card`() {
        listOf("I<UTOD231458907<<<<<<<<<<<<<<<", "7408122F1204159UTO<<<<<<<<<<<6", "ERIKSSON<<ANNA<MARIA<<<<<<<<<<")
            .forEach { assertThat(mrz(it)).containsExactly(it) }
    }

    @Test fun `ordinary text is not a machine readable zone`() {
        assertThat(mrz("UNITED STATES DEPARTMENT OF STATE")).isEmpty()
        assertThat(mrz("<< BACK TO SHOPPING CART AND CONTINUE")).isEmpty()
        assertThat(mrz("ORDER 1234567890123456789012345678 SHIPPED")).isEmpty()
    }

    // ---------- 对照 ----------

    /** 样张上 OCR 读出来的、与机读码有关的几行（坐标不要紧，对照只看文字）。 */
    private val specimen = page(
        Row("Passport No.No. du Passeport/No.de Pasaporte", 40f, 0f),
        Row("E00007730", 40f, 30f),
        Row("TRAVELER", 40f, 60f),
        Row("HAPPY", 40f, 90f),
        Row("UNITED STATES OF AMERICA", 40f, 120f),
        Row("05 FEB 1965", 40f, 150f),
        Row("14 OCT 2030", 40f, 180f),
        Row("05 02 65", 40f, 210f),
        Row("UNITED STATES DEPARTMENT OF STATE", 40f, 240f),
        Row(line1, 40f, 300f),
        Row(line2, 40f, 330f),
    )

    @Test fun `the document number is found where it is printed again`() {
        assertThat(MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, 1f).texts(specimen)).containsExactly("E00007730")
    }

    /** 生日：资料页上的「05 FEB 1965」，幽灵像旁边的「05 02 65」。有效期不是。 */
    @Test fun `the birth date is found in any format`() {
        assertThat(MrzEcho(MrzEcho.What.BIRTH_DATE, 1f).texts(specimen)).containsExactly("05 FEB 1965", "05 02 65")
    }

    /** 姓名：整行只有机读码里的名字才算，「UNITED STATES」里没有人名。 */
    @Test fun `the names are found on lines that hold only them`() {
        assertThat(MrzEcho(MrzEcho.What.NAME, 1f).texts(specimen)).containsExactly("TRAVELER", "HAPPY")
    }

    /** 美国人写日期是月在前：「02/05/1965」也是 1965 年 2 月 5 日。 */
    @Test fun `a month first date matches too`() {
        val p = page(Row("02/05/1965", 40f, 0f), Row(line1, 40f, 40f), Row(line2, 40f, 70f))
        assertThat(MrzEcho(MrzEcho.What.BIRTH_DATE, 1f).texts(p)).containsExactly("02/05/1965")
    }

    /** 证件号的校验位对不上，就不拿它去对照：读错的号码对照出来的东西不可信。 */
    @Test fun `a document number with a broken check digit is not echoed`() {
        val broken = "E000077301USA6502056F3010149500101920<091824"
        val p = page(Row("E00007730", 40f, 0f), Row(line1, 40f, 40f), Row(broken, 40f, 70f))
        assertThat(MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, 1f).texts(p)).isEmpty()
    }

    @Test fun `no machine readable zone, nothing to echo`() {
        val p = page(Row("E00007730", 40f, 0f), Row("TRAVELER", 40f, 30f))
        MrzEcho.What.entries.forEach { assertThat(MrzEcho(it, 1f).texts(p)).isEmpty() }
    }

    // ---------- 大陆的通行证 ----------

    /** 往来港澳通行证卡正面底下的一行：百度 OCR 样张的「C00000000」，四位校验位都对得上。往来台湾通行证是 CD。 */
    private val exitPermit = "CSC000000004<2404200<8108038<6"

    @Test fun `the one line of an exit-entry permit card`() {
        listOf(exitPermit, "CSCA12345672<3306150<9503124<6", "CDL123456781<3001019<9001011<8")
            .forEach { assertThat(mrz(it)).containsExactly(it) }
        // OCR 把「<」读成 K、在中间补空格，照样认
        assertThat(mrz("CSC000000004K2404200K8108038K6")).hasSize(1)
        assertThat(mrz("CSC00000000 4<2404200<810 8038<6")).hasSize(1)
        // 校验位只剩一位对得上的不认
        assertThat(mrz("CSC000000004<2404201<8108039<6")).isEmpty()
    }

    /**
     * 卡右上角没有字段名的号码、别处印着的生日，拿那一行对出来。
     * 那一行有效期在前、生日在后：当护照第二行读会把有效期「240420」当成生日，「2024.04.20」不该被认成生日。
     */
    @Test fun `the permit number and birth date printed elsewhere on the card`() {
        val p = page(
            Row("往来港澳通行证", 40f, 0f),
            Row("C00000000", 300f, 0f),
            Row("姓名 证件样本", 40f, 30f),
            Row("出生日期 1981.08.03", 40f, 60f),
            Row("有效期限 2014.04.21-2024.04.20", 40f, 90f),
            Row(exitPermit, 40f, 150f),
        )
        assertThat(MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, 1f).texts(p)).containsExactly("C00000000")
        assertThat(MrzEcho(MrzEcho.What.BIRTH_DATE, 1f).texts(p)).containsExactly("1981.08.03")
        assertThat(passport.findOnPage(p).map { p.text(it) }).containsExactly("C00000000", exitPermit)
    }

    /** 回乡证（CR）、台胞证（CT）背面三行的第一行，整行没有成串的填充符。号码旁边粘着换证次数也算。 */
    @Test fun `the first line of a mainland travel permit`() {
        val hk = "CRH1234567830003001019M9001011"
        val tw = "CT12345678<80133001019F9001011"
        listOf(hk, tw).forEach { assertThat(mrz(it)).containsExactly(it) }
        val p = page(Row("证件号码 H1234567800", 40f, 0f), Row("出生日期 1990.01.01", 40f, 30f), Row(hk, 40f, 90f))
        assertThat(MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, 1f).texts(p)).containsExactly("H1234567800")
        assertThat(MrzEcho(MrzEcho.What.BIRTH_DATE, 1f).texts(p)).containsExactly("1990.01.01")
        val t = page(Row("12345678", 40f, 0f), Row(tw, 40f, 90f))
        assertThat(MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, 1f).texts(t)).containsExactly("12345678")
    }

    /** 中国护照第二行可选数据区是 GBK 编码的姓名（张三 = NFMFMIPN），照旧按护照认，号码照旧对得出来。 */
    @Test fun `a chinese passport still reads`() {
        val l1 = "POCHNZHANG<<SAN<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
        val l2 = "E123456782CHN9001011M3001019NFMFMIPN<<<<A988"
        listOf(l1, l2).forEach { assertThat(mrz(it)).containsExactly(it) }
        val p = page(Row("E12345678", 40f, 0f), Row("ZHANG, SAN", 40f, 30f), Row("01 JAN 1990", 40f, 60f), Row(l1, 40f, 120f), Row(l2, 40f, 150f))
        assertThat(MrzEcho(MrzEcho.What.DOCUMENT_NUMBER, 1f).texts(p)).containsExactly("E12345678")
        assertThat(MrzEcho(MrzEcho.What.BIRTH_DATE, 1f).texts(p)).containsExactly("01 JAN 1990")
        assertThat(MrzEcho(MrzEcho.What.NAME, 1f).texts(p)).containsExactly("ZHANG, SAN")
    }

    /** 按出厂规则表：护照一类认出两行机读码和资料页上的号码。 */
    @Test fun `the passport rule finds both lines and the number`() {
        assertThat(passport.findOnPage(specimen).map { specimen.text(it) }).containsExactly("E00007730", line1, line2)
    }
}
