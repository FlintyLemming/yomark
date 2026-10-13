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

    /**
     * 末位 X 的各种读法：小写、乘号、全角，希腊、西里尔字母，和单独隔开的一个 X。
     * 跟在字段名后面的也整个认：字段名那一路先认，原先停在第 17 位，第 18 位露在外面。
     */
    @Test fun `the check character x in any of its lookalikes`() {
        val lookalikes = listOf("X", "x", "×", "Ｘ", "Χ", "Х").map { "11010519491231002$it" }
        lookalikes.forEach { assertThat(matched("号码 $it")).containsExactly(it) }
        lookalikes.forEach { assertThat(matched("身份证号 $it")).containsExactly(it) }
        assertThat(ResidentIdNumber.findAll("11010519491231002 X").single().number).isEqualTo("11010519491231002X")
    }

    /** 照片上 OCR 把字段名拆成几段、冒号前补了空格：号码认错一位也照样遮，靠的就是字段名。 */
    @Test fun `a label split by ocr or spaced before its colon`() {
        assertThat(matched("公民 身份号 码3 202 01201109254520")).containsExactly("3 202 01201109254520")
        assertThat(matched("公民 身份号 码3 200 01201109254526")).containsExactly("3 200 01201109254526")
        assertThat(matched("身份 号码 330106199307180424")).containsExactly("330106199307180424")
        assertThat(matched("身份证号码 :330106199307180424")).containsExactly("330106199307180424")
        assertThat(matched("证件号码 ： H12345678")).containsExactly("H12345678")
        // 字段名前面紧挨着汉字的照旧不认；四个字以上的照认：「公」读丢了的「民 身 份 号 码」
        assertThat(matched("凭证 号 12345678")).isEmpty()
        assertThat(matched("民 身 份 号 码 33010 07180423")).containsExactly("33010 07180423")
    }

    /** 字段名后面的值不吞下一段：空格后面是日期、手机号的那段不是号码的一部分。 */
    @Test fun `a labeled value stops before a date or a phone number`() {
        assertThat(matched("证件号码 H12345678 2026.10.12")).containsExactly("H12345678")
        assertThat(matched("证件号码 H12345678 2026-10-12")).containsExactly("H12345678")
        assertThat(matched("证件号码 H12345678 2026年10月12日")).containsExactly("H12345678")
        assertThat(matched("身份证号 330106199307180423 13812345678")).containsExactly("330106199307180423")
        assertThat(matched("公民身份号码 110105 19900307 1239 13812345678")).containsExactly("110105 19900307 1239")
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
        assertThat(matched("户别 家庭户 户号 1234567890")).containsExactly("1234567890")
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

    /** 在别处也常见的字段名，后面的值要再像一点。 */
    @Test fun `labels that also mean something else need a value of the right shape`() {
        // 缩写只认大写；FIN、NRIC 要新加坡的写法，SIN 九位，VID 十六位
        assertThat(matched("https://a.com/watch?vid=1234567")).isEmpty()
        assertThat(matched("sin 123456")).isEmpty()
        assertThat(matched("SIN 123456")).isEmpty()
        assertThat(matched("SIN 130 456 783")).containsExactly("130 456 783")
        assertThat(matched("FIN 202612345")).isEmpty()
        assertThat(matched("FIN: G1234567X")).containsExactly("G1234567X")
        assertThat(matched("VID 9123 4567 8901 2345")).containsExactly("9123 4567 8901 2345")
        // 前面紧挨着别的英文词的「ID No.」是别的号码
        assertThat(matched("Tracking ID Number 1Z999AA10123456784")).isEmpty()
        assertThat(matched("Product ID No. 12345678")).isEmpty()
        assertThat(matched("National ID No. 12345678")).containsExactly("12345678")
        // 台湾公司的统一编号八位数字，个人的是一个字母打头
        assertThat(matched("統一編號：12345675")).isEmpty()
        // 产品认证证书的编号；个人证书上还写着姓名
        assertThat(matched("证书编号 CQC2021010101234567")).isEmpty()
        assertThat(matched("姓名 张三 证书编号 1234567890123")).containsExactly("1234567890123")
        assertThat(matched("证书编号 1234567890123")).isEmpty()
        assertThat(matched("产品名称 电水壶 姓名 张三 证书编号 2020010703372854")).isEmpty()
        // 电费的户号
        assertThat(matched("户号 3301234567 你自己在网上交也行")).isEmpty()
    }

    /** 澳门身份证的字段名只是「編號」：后面要是七位加括号里一位的样子。 */
    @Test fun `macau ids after their label`() {
        assertThat(matched("編號 1234567(8)")).containsExactly("1234567(8)")
        assertThat(matched("編號/Número 5123456（7）")).containsExactly("5123456（7）")
        assertThat(matched("Numero 7123456 (1)")).containsExactly("7123456 (1)")
        assertThat(matched("編號 1234567")).isEmpty()
        assertThat(matched("1234567(8)")).isEmpty()
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
            .forEach { assertThat(matched("身分證 號碼 $it")).containsExactly(it) }
        // 民國紀年、台胞证背面的机读码也说明是台湾的证件
        assertThat(matched("民國79年1月1日 A123456789")).containsExactly("A123456789")
        assertThat(matched("A123456789 CT12345678<80133001019F9001011")).contains("A123456789")
        assertThat(matched("身分證 A123456788")).isEmpty()
        assertThat(matched("身分證 A323456789")).isEmpty()          // 第二位不是 1、2、8、9、A–D
    }

    /**
     * 12306 的订单号也是一个字母加九位数字，「E100000005」碰巧过得了台湾身份证的校验：页上要写着是台湾的证件，
     * 跟在「订单号」后面的也不算。新加坡、日本的同样。
     */
    @Test fun `letter led numbers need a cue and no order hint`() {
        assertThat(matched("E100000005")).isEmpty()
        assertThat(matched("台湾居民 E100000005")).containsExactly("E100000005")
        assertThat(matched("台湾 订单号：E100000005")).isEmpty()
        assertThat(matched("台湾 取票号 E212345673")).isEmpty()
        assertThat(matched("S1234567D")).isEmpty()
        assertThat(matched("Singapore Order S1234567D")).isEmpty()
        assertThat(matched("新加坡 货号 S1234567D")).isEmpty()
        assertThat(matched("AB12345678CD")).isEmpty()
        assertThat(matched("在留 型号 AB12345678CD")).isEmpty()
    }

    /**
     * 地区码加日期再加四位的办件号、受理号只剩校验码一道关，十一个里有一个过得去；日期打头的订单号像韩国、马来西亚的号码。
     * 跟在提示词后面的不算，提示词后面又说到身份证的照认。
     */
    @Test fun `numbers built from dates after an order hint`() {
        listOf("330106202610110019", "110105202609300125").forEach { assertThat(ResidentIdNumber.isValid(it)).isTrue() }
        assertThat(matched("办件编号 330106202610110019")).isEmpty()
        assertThat(matched("受理号：110105202609300125")).isEmpty()
        assertThat(matched("订单联系人身份证 330106199307180423")).containsExactly("330106199307180423")
        assertThat(matched("证件编号 330106199307180423")).containsExactly("330106199307180423")
        assertThat(matched("261012-1234567")).isEmpty()
        assertThat(matched("주민등록번호 261012-1234567")).hasSize(1)
        assertThat(matched("订单号 261012-1234567")).isEmpty()
        assertThat(matched("单号：250101-3123456")).isEmpty()
        assertThat(matched("流水号 241231-1000001")).isEmpty()
        assertThat(matched("订单号 900101-14-5677")).isEmpty()
    }

    @Test fun `singapore nric and fin`() {
        listOf("S1234567D", "T1234567J", "F1234567N", "G1234567X", "M1234567K")
            .forEach { assertThat(matched("SINGAPORE $it")).containsExactly(it) }
        assertThat(matched("SINGAPORE M1234567B")).isEmpty()        // ICA 新闻稿里的示意号码，校验位不对
    }

    @Test fun `korean resident registration numbers`() {
        assertThat(matched("KOREA 800101-1234560")).containsExactly("800101-1234560")
        assertThat(matched("주민등록번호 900101 - 2345670")).containsExactly("900101 - 2345670")
        assertThat(matched("주민등록번호 850315一1123457")).containsExactly("850315一1123457")  // 连字符读成了「一」
        // 校验位对得上的不要提示词（韩文 OCR 读不出来）；2020 年以后发的没有校验位，要页上写着是韩国的
        assertThat(matched("800101-1234560")).containsExactly("800101-1234560")
        assertThat(matched("800101-1234567")).isEmpty()
        assertThat(matched("KOREA 800101-1234567")).containsExactly("800101-1234567")
        assertThat(matched("KOREA 801301-1234560")).isEmpty()       // 13 月
        assertThat(matched("KOREA 8001011234560")).isEmpty()        // 没有连字符
        assertThat(matched("KOREA 800101-9234560")).isEmpty()       // 第七位只有 1–8
    }

    @Test fun `malaysian mykad numbers`() {
        assertThat(matched("MyKad 900101-14-5677")).containsExactly("900101-14-5677")
        assertThat(matched("MyKad 900101一14一5677")).containsExactly("900101一14一5677")
        assertThat(matched("900101-14-5677")).isEmpty()
        assertThat(matched("MyKad 900101-00-5677")).isEmpty()       // 00 不是出生地代码
        assertThat(matched("MyKad 900141-14-5677")).isEmpty()
    }

    @Test fun `japanese residence cards and brazilian cpf`() {
        assertThat(matched("番号 AB12345678CD")).containsExactly("AB12345678CD")
        assertThat(matched("AB12345678CD")).isEmpty()
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

    /** 卡面上的一行：有效期要页上有卡，这一行带上卡组织的名字。 */
    private fun onCard(text: String) = cardMatched("UnionPay $text")

    /** 卡面上的年份写成两位：今年往后两年，测试不随年份过期。 */
    private val yy = "%02d".format((LocalDate.now().year + 2) % 100)

    @Test fun `card security codes and expiry dates after their labels`() {
        assertThat(cardMatched("CVV2: 123")).containsExactly("123")
        assertThat(cardMatched("安全码 4321")).containsExactly("4321")
        assertThat(onCard("VALID THRU 12/$yy")).containsExactly("12/$yy")
        assertThat(onCard("GOOD THRU 09/20$yy")).containsExactly("09/20$yy")
        assertThat(onCard("EXPIRES END 03 / $yy")).containsExactly("03 / $yy")
        assertThat(onCard("EXP DATE 03/$yy")).containsExactly("03/$yy")
        assertThat(onCard("VALID FROM/THRU 01/20 - 01/$yy")).containsExactly("01/20 - 01/$yy")
    }

    /**
     * 身份证背面的「有效期限」、驾照护照上的完整日期都不是卡的有效期；小写的英文、中文的「有效期」不认，
     * 早就过期的、几十年后才到期的年份不是卡面上的。
     */
    @Test fun `validity periods of ids and coupons are not card expiry dates`() {
        assertThat(cardMatched("有效期限 2015.08.20-2035.08.20")).isEmpty()
        assertThat(cardMatched("有效期至 2029.05.31")).isEmpty()
        assertThat(cardMatched("有效期 2026/12/31")).isEmpty()
        assertThat(cardMatched("有效期至 06/$yy")).isEmpty()
        assertThat(cardMatched("Expiry Date: 09/20$yy")).isEmpty()
        assertThat(onCard("Offer valid thru 12/$yy.")).isEmpty()
        assertThat(onCard("VALID THRU 08/20")).isEmpty()
        assertThat(onCard("VALID THRU 12/99")).isEmpty()
        assertThat(onCard("VALID THRU 10/12-10/$yy")).isEmpty()
        assertThat(cardMatched("Expiry 10/14/2030")).isEmpty()
        assertThat(cardMatched("CVV 12")).isEmpty()
        assertThat(cardMatched("cvv 123")).isEmpty()
        assertThat(cardMatched("CID 1234")).isEmpty()
        assertThat(cardMatched("Experience 10/20")).isEmpty()
        // 优惠券上的「VALID THRU」：页上没有卡
        assertThat(cardMatched("VALID THRU 12/$yy")).isEmpty()
        assertThat(cardMatched("GOOD THRU 11/$yy")).isEmpty()
    }
}
