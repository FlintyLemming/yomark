package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 字段名在上、值在下（StackedField），按出厂规则表跑：人名、地址、护照号、出生日期四类里各有一种这样的认法。
 *
 * 护照那几页的坐标照抄自美国护照样张的真实 OCR（PP-OCRv6 small，900×600 的图）：字段名带着法文西文，
 * 「Given names」被读成了「Given na me s」，一排三个字段名，值的字号比字段名大、框上下咬住一截。
 */
class StackedFieldTest {

    /** 一类在这一页上认出的；要旁证的猜测（从名单猜的人名）由 RuleClassifier 再定，这里不算。 */
    private fun found(id: String, page: Page): List<String> =
        DefaultRuleSet.rules.first { it.id == id }.findOnPage(page).filter { !it.match.needsAnchor }.map { page.text(it) }

    /** 样张上半页：一排三个字段名、各自下面的值，姓、名。 */
    private val passportTop = page(
        Row(
            84f, 22f,
            Segment("Type/Type/Tipo", 275f, 375f),
            Segment("Code/Code/Código", 386f, 508f),
            Segment("Passport No.No. du Passeport/No.de Pasaporte", 529f, 825f),
        ),
        Row(100f, 35f, Segment("E00007730", 591f, 758f)),
        Row(103f, 25f, Segment("USA", 431f, 477f)),
        Row(104f, 22f, Segment("P", 318f, 339f)),
        Row(141f, 20f, Segment("Surname/No m/A pellidos", 253f, 403f)),
        Row(158f, 26f, Segment("TRAVELER", 257f, 380f)),
        Row(184f, 19f, Segment("Given na me s/P rón oms/N om b re s", 251f, 454f)),
        Row(201f, 25f, Segment("HAPPY", 255f, 338f)),
        Row(230f, 17f, Segment("Nationalit y/N ation alit é /Nac iona lidad", 300f, 523f)),
    )

    /** 样张下半页：生日的框被水印撑高了，和字段名重叠 12 像素；右边粘着一个水印残字「E」。 */
    private val passportBottom = page(
        Row(265f, 23f, Segment("Date of birth/D ate de naissande/Fecha de", 299f, 565f)),
        Row(276f, 52f, Segment("05 FEB 1965", 299f, 454f), Segment("E", 451f, 505f)),
        Row(306f, 18f, Segment("Sex/Sexe/Sex", 300f, 382f)),
        Row(322f, 22f, Segment("F", 304f, 323f)),
        Row(342f, 22f, Segment("Place of birth/Liou de naissance/L uga r de nacimie nta", 300f, 630f)),
        Row(357f, 28f, Segment("WASHINGTON.D.C..U.S.A.", 303f, 616f)),
        Row(384f, 17f, Segment("Date of issue/Date de dé liv rance/Fecha de expedició n", 300f, 632f)),
        Row(397f, 26f, Segment("15 OCT 2020", 302f, 449f)),
    )

    @Test fun `the names under surname and given names`() {
        assertThat(found("name", passportTop)).containsExactly("TRAVELER", "HAPPY")
    }

    /** 一排三个字段名，各管各的一栏：「USA」在「Code」下面，不是护照号。 */
    @Test fun `the passport number under its own column`() {
        assertThat(found("passport", passportTop)).containsExactly("E00007730")
    }

    @Test fun `the birth date under date of birth, without the watermark letter`() {
        assertThat(found("birthdate", passportBottom)).containsExactly("05 FEB 1965")
    }

    @Test fun `the place of birth is an address`() {
        assertThat(found("address", passportBottom)).containsExactly("WASHINGTON.D.C..U.S.A.")
    }

    /** 同一排两个字段名：姓那一栏只到「Sex」为止，「M」归它自己那一栏。 */
    @Test fun `a value under the next label on the row belongs to that label`() {
        val p = page(
            Row(0f, 20f, Segment("Surname/Nom (1)", 40f, 240f), Segment("Sex/Sexe (5)", 400f, 520f)),
            Row(24f, 24f, Segment("SMITH", 40f, 130f), Segment("M", 400f, 415f)),
        )
        assertThat(found("name", p)).containsExactly("SMITH")
    }

    /** 这一栏的值 OCR 没读出来，下面紧挨着的是下一个字段名：不能把字段名当成名字。 */
    @Test fun `a label below is not the value`() {
        val p = page(
            Row("Given names/Prénoms", 40f, 0f),
            Row("Nationality/Nationalité", 40f, 26f),
            Row("BRITISH CITIZEN", 40f, 52f),
        )
        assertThat(found("name", p)).isEmpty()
    }

    /** 下面一行从左边一路连过来（一段话）：栏里那一截不是值。 */
    @Test fun `a sentence running into the column is not the value`() {
        val p = page(
            Row("Surname", 300f, 0f),
            Row("Thank You For Choosing Happy Holiday Travel Today", 40f, 24f),
        )
        assertThat(found("name", p)).isEmpty()
    }

    /** 太远的不是：字段名下面隔了好几行高的空白。 */
    @Test fun `a value far below is not taken`() {
        val p = page(Row("Surname", 40f, 0f), Row("SMITH", 40f, 120f))
        assertThat(found("name", p)).isEmpty()
    }

    /** 斜压在版面上的水印框比字段名高好几倍，跳过它，取下面真的值。 */
    @Test fun `a watermark between the label and the value is skipped`() {
        val p = page(
            Row("Surname/Nom", 40f, 0f),
            Row(10f, 130f, Segment("SPECIMEN", 20f, 600f)),
            Row("SMITH", 40f, 24f),
        )
        assertThat(found("name", p)).containsExactly("SMITH")
    }

    /** 驾照：「DOB 01/07/1973」同一行已经有值，下面一行是有效期，不是生日。 */
    @Test fun `a label with its value on the same line does not look below`() {
        val p = page(Row("DOB 01/07/1973", 40f, 0f), Row("4b 01/07/2030", 40f, 24f))
        assertThat(found("birthdate", p)).containsExactly("01/07/1973")
    }

    /** 一句话里提到字段名，下面一行不像日期：什么都不认。 */
    @Test fun `a label in a sentence with no date below`() {
        val p = page(Row("Please confirm your date of birth", 40f, 0f), Row("Thanks, John", 40f, 24f))
        assertThat(found("birthdate", p)).isEmpty()
    }

    // ---------- 表单：单独一行的字段名 ----------

    /** 订单页的收货地址块：字段名单独一行，下面行距均匀的几行都是地址，到行距变大为止。 */
    @Test fun `the lines under a shipping address header`() {
        val p = page(
            Row("Shipping Address", 40f, 0f),
            Row("4417 Brookhaven Dr Apt 12B", 40f, 26f),
            Row("Tucker, GA 30084-2213", 40f, 52f),
            Row("United States", 40f, 78f),
            Row("Payment Method", 40f, 140f),
            Row("Visa ending in 4471", 40f, 166f),
        )
        assertThat(found("address", p))
            .containsExactly("4417 Brookhaven Dr Apt 12B", "Tucker, GA 30084-2213", "United States")
    }

    /** 地址块下面一行是电话：停在电话前面，电话归电话规则。 */
    @Test fun `an address block stops at the phone line`() {
        val p = page(
            Row("Delivery address", 40f, 0f),
            Row("88 Queen St", 40f, 26f),
            Row("(555) 123-4567", 40f, 52f),
        )
        assertThat(found("address", p)).containsExactly("88 Queen St")
    }

    /**
     * 地址块下面跟着的界面上的字（英文名址基准上量出来的误遮）：链接、送餐备注、地址的类型、勾选项、
     * 表单上下一个必填字段的字段名。
     */
    @Test fun `an address block stops at the ui text under it`() {
        val airbnb = page(
            Row("Address", 40f, 0f),
            Row("4/19 Kendall Street", 40f, 26f),
            Row("Byron Bay NSW 2481", 40f, 52f),
            Row("Get directions", 40f, 78f),
        )
        assertThat(found("address", airbnb)).containsExactly("4/19 Kendall Street", "Byron Bay NSW 2481")
        val contact = page(Row("Address", 40f, 0f), Row("Gandhi Nagar, Adyar", 40f, 26f), Row("Home", 40f, 52f))
        assertThat(found("address", contact)).containsExactly("Gandhi Nagar, Adyar")
        val sameAs = page(Row("Mailing address", 40f, 0f), Row("Same as legal address", 40f, 26f))
        assertThat(found("address", sameAs)).isEmpty()
        val form = page(
            Row("Street address *", 40f, 0f),
            Row("8812 Bramblewood Ct Apt 304", 40f, 26f),
            Row("City, State, ZIP *", 40f, 52f),
            Row("Plano, TX 75024", 40f, 78f),
        )
        // 第二行之后是表单的下一个字段；「Plano, TX 75024」由城市州邮编的形状认出来
        assertThat(found("address", form)).containsExactly("8812 Bramblewood Ct Apt 304", "Plano, TX 75024")
    }

    /** 联系人卡片：「Birthday」下面是生日。 */
    @Test fun `the date under birthday`() {
        val p = page(Row("Birthday", 40f, 0f), Row("12 March 1979", 40f, 26f), Row("Notes", 40f, 60f))
        assertThat(found("birthdate", p)).containsExactly("12 March 1979")
    }

    @Test fun `the name under a full name header`() {
        val p = page(Row("Full name", 40f, 0f), Row("Emily Carter", 40f, 26f))
        assertThat(found("name", p)).containsExactly("Emily Carter")
    }

    /** 「Name」这种字段名下面不一定是人：「Order Details」、品牌名不算。 */
    @Test fun `ui words under a name header are not names`() {
        val p = page(Row("Name", 40f, 0f), Row("Order Details", 40f, 26f))
        assertThat(found("name", p)).isEmpty()
    }
}
