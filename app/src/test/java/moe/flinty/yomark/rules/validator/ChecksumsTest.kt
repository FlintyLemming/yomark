package moe.flinty.yomark.rules.validator

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChecksumsTest {

    // ---------- Luhn ----------
    @Test fun `luhn accepts well-known test card numbers`() {
        assertThat(Checksums.luhn("4111111111111111")).isTrue()   // Visa
        assertThat(Checksums.luhn("5500005555555559")).isTrue()   // Mastercard
        assertThat(Checksums.luhn("378282246310005")).isTrue()    // Amex
    }

    @Test fun `luhn rejects a number with one digit changed`() {
        assertThat(Checksums.luhn("4111111111111112")).isFalse()
    }

    @Test fun `luhn rejects non-digits and empty input`() {
        assertThat(Checksums.luhn("")).isFalse()
        assertThat(Checksums.luhn("41111111111111x1")).isFalse()
    }

    @Test fun `luhn rejects an all-zero run that would otherwise pass`() {
        assertThat(Checksums.luhn("0000000000000000")).isFalse()
    }

    // ---------- IBAN ----------
    @Test fun `iban accepts valid numbers with and without spaces`() {
        assertThat(Checksums.ibanValid("DE89370400440532013000")).isTrue()
        assertThat(Checksums.ibanValid("DE89 3704 0044 0532 0130 00")).isTrue()
        assertThat(Checksums.ibanValid("GB82WEST12345698765432")).isTrue()
    }

    @Test fun `iban rejects a wrong check digit`() {
        assertThat(Checksums.ibanValid("DE88370400440532013000")).isFalse()
    }

    @Test fun `iban rejects a wrong length for the country`() {
        assertThat(Checksums.ibanValid("DE8937040044053201300")).isFalse()
    }

    @Test fun `iban rejects an unknown country code`() {
        assertThat(Checksums.ibanValid("ZZ89370400440532013000")).isFalse()
        assertThat(Checksums.ibanLengthForCountry("DE")).isEqualTo(22)
    }

    // ---------- SSN ----------
    @Test fun `ssn accepts a normal number`() {
        assertThat(Checksums.ssnValid("123-45-6789")).isTrue()
    }

    @Test fun `ssn rejects invalid area group and serial segments`() {
        assertThat(Checksums.ssnValid("000-45-6789")).isFalse()
        assertThat(Checksums.ssnValid("666-45-6789")).isFalse()
        assertThat(Checksums.ssnValid("900-45-6789")).isFalse()
        assertThat(Checksums.ssnValid("123-00-6789")).isFalse()
        assertThat(Checksums.ssnValid("123-45-0000")).isFalse()
    }

    // ---------- MRZ ----------
    /** ICAO 9303 的样例：护照号 L898902C3 校验位 6；生日 740812 校验位 2；有效期 120415 校验位 9。 */
    @Test fun `mrz check digits follow the icao example`() {
        assertThat(Checksums.mrzCheck("L898902C3")).isEqualTo('6')
        assertThat(Checksums.mrzCheck("740812")).isEqualTo('2')
        assertThat(Checksums.mrzCheck("120415")).isEqualTo('9')
        // 填充符算 0，别的字符算不出来
        assertThat(Checksums.mrzCheck("D23145890<")).isEqualTo(Checksums.mrzCheck("D23145890"))
        assertThat(Checksums.mrzCheck("74-812")).isEqualTo('?')
    }

    // ---------- 证件号码 ----------
    /** GB 11643 附录的两个样例、2023 版永居证样张、港澳台居民居住证号；改一位就不对。 */
    @Test fun `chinese id check character`() {
        listOf("11010519491231002X", "440524188001010014", "932682198501010017", "810000199408230021", "330106199307180423")
            .forEach { assertThat(Checksums.chineseIdValid(it)).isTrue() }
        assertThat(Checksums.chineseIdValid("11010519491231002x")).isTrue()
        assertThat(Checksums.chineseIdValid("110105194912310021")).isFalse()
        assertThat(Checksums.chineseIdValid("330106199307180433")).isFalse()
    }

    @Test fun `hong kong id mod 11`() {
        assertThat(Checksums.hkidValid("A123456", '3')).isTrue()
        assertThat(Checksums.hkidValid("G123456", 'A')).isTrue()
        assertThat(Checksums.hkidValid("C668668", '9')).isTrue()
        assertThat(Checksums.hkidValid("WX123456", '9')).isTrue()
        assertThat(Checksums.hkidValid("XA123456", '8')).isTrue()
        assertThat(Checksums.hkidValid("A123456", '4')).isFalse()
        assertThat(Checksums.hkidValid("A123457", '3')).isFalse()
    }

    /** B120667503 是台北市政府「检查方法」里的例子。 */
    @Test fun `taiwan id and resident certificate`() {
        listOf("A123456789", "B120667503", "F123456784", "A800000014", "A900000016", "F812345676", "AA12345675", "FC12345674")
            .forEach { assertThat(Checksums.taiwanIdValid(it)).isTrue() }
        assertThat(Checksums.taiwanIdValid("A123456788")).isFalse()
        assertThat(Checksums.taiwanIdValid("B120667513")).isFalse()
        assertThat(Checksums.taiwanIdValid("AE12345675")).isFalse()  // 旧式居留证第二位只有 A–D
    }

    /** ICA 新闻稿里示意的「M1234567B」校验位不对，正确的是 K。 */
    @Test fun `singapore nric and fin`() {
        listOf("S1234567D", "T1234567J", "F1234567N", "G1234567X", "M1234567K").forEach { assertThat(Checksums.nricValid(it)).isTrue() }
        assertThat(Checksums.nricValid("M1234567B")).isFalse()
        assertThat(Checksums.nricValid("S1234568D")).isFalse()
    }

    @Test fun `brazil cpf`() {
        listOf("529.982.247-25", "111.444.777-35", "123.456.789-09", "012.345.678-90").forEach { assertThat(Checksums.cpfValid(it)).isTrue() }
        assertThat(Checksums.cpfValid("529.982.247-26")).isFalse()
        assertThat(Checksums.cpfValid("529.982.257-25")).isFalse()
        assertThat(Checksums.cpfValid("111.111.111-11")).isFalse()   // 全同的号码算术上过得去，但不发
    }

    /** GB 16735 附录 A 的例子（和 275，余 0）、维基百科的例子（余 10 写作 X）、常见的演示号码。 */
    @Test fun `vin check digit`() {
        listOf("LFWADRJF011002346", "1M8GDM9AXKP042788", "1HGCM82633A004352", "11111111111111111")
            .forEach { assertThat(Checksums.vinValid(it)).isTrue() }
        assertThat(Checksums.vinValid("LFWADRJF011002347")).isFalse()
        assertThat(Checksums.vinValid("1M8GDM9A0KP042788")).isFalse()
        assertThat(Checksums.vinValid("LFWADRJFO11002346")).isFalse()  // O 不是 VIN 里的字
        assertThat(Checksums.vinValid("LFWADRJF01100234")).isFalse()
    }

    // ---------- 熵 ----------
    @Test fun `entropy is low for repeated characters and high for random ones`() {
        assertThat(Checksums.shannonEntropy("aaaaaaaaaaaaaaaa")).isLessThan(1.0)
        assertThat(Checksums.shannonEntropy("aB3xQ9zL2mR7tK1v")).isGreaterThan(3.5)
    }

    // ---------- UPS ----------
    @Test fun `ups check digit validates a real-format tracking number`() {
        assertThat(Checksums.upsCheckDigit("1Z9999W99999999999".let { it })).isFalse()  // 随手编的必然不过
        assertThat(Checksums.upsCheckDigit("1Z12345E0205271688")).isTrue()
    }

    // ---------- MAC ----------
    @Test fun `mac separator consistency`() {
        assertThat(Checksums.macSeparatorConsistent("00:1A:2B:3C:4D:5E")).isTrue()
        assertThat(Checksums.macSeparatorConsistent("00-1A-2B-3C-4D-5E")).isTrue()
        assertThat(Checksums.macSeparatorConsistent("00:1A-2B:3C-4D:5E")).isFalse()
    }

    // ---------- TLD ----------
    @Test fun `known tlds cover common gtlds and any two-letter cctld`() {
        assertThat(KnownTlds.isKnown("com")).isTrue()
        assertThat(KnownTlds.isKnown("dev")).isTrue()
        assertThat(KnownTlds.isKnown("de")).isTrue()
        assertThat(KnownTlds.isKnown("zzzz")).isFalse()
    }
}
