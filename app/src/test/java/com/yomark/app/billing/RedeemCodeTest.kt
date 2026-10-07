package com.yomark.app.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RedeemCodeTest {

    @Test fun `the hardcoded code is accepted`() {
        assertThat(RedeemCode.matches("qwer12345678")).isTrue()
    }

    @Test fun `surrounding whitespace and case are ignored`() {
        assertThat(RedeemCode.matches("  QWER12345678 \n")).isTrue()
    }

    @Test fun `anything else is rejected`() {
        assertThat(RedeemCode.matches("")).isFalse()
        assertThat(RedeemCode.matches("qwer1234567")).isFalse()
        assertThat(RedeemCode.matches("qwer123456789")).isFalse()
    }
}
