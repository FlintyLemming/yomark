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
    @Test fun `mrz line one is recognized by its format`() {
        val l1 = "P<USADOE<<JOHN<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
        assertThat(l1.length).isEqualTo(44)
        assertThat(Checksums.mrzTd3Line1(l1)).isTrue()
        assertThat(Checksums.mrzTd3Line1("NOT AN MRZ LINE")).isFalse()
    }

    @Test fun `mrz line two validates its own check digits`() {
        // 护照号 L898902C3 校验位 6；生日 740812 校验位 2；有效期 120415 校验位 9
        val l2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"
        assertThat(l2.length).isEqualTo(44)
        assertThat(Checksums.mrzTd3Line2Valid(l2)).isTrue()
    }

    @Test fun `mrz line two with a broken check digit is rejected`() {
        val broken = "L898902C31UTO7408122F1204159ZE184226B<<<<<10"
        assertThat(Checksums.mrzTd3Line2Valid(broken)).isFalse()
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
