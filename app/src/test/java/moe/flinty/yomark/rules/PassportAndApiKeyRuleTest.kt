package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PassportAndApiKeyRuleTest {

    private fun rule(id: String) = DefaultRuleSet.rules.first { it.id == id }
    private fun matched(id: String, text: String) =
        rule(id).findIn(text).map { text.substring(it.range.first, it.range.last + 1) }

    private val mrzL1 = "P<USADOE<<JOHN<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<"
    private val mrzL2 = "L898902C36UTO7408122F1204159ZE184226B<<<<<10"

    @Test fun `passport matches mrz line one by format`() {
        assertThat(matched("passport", mrzL1)).containsExactly(mrzL1)
    }

    @Test fun `passport matches mrz line two by its check digits`() {
        assertThat(matched("passport", mrzL2)).containsExactly(mrzL2)
    }

    /** 有成串的填充符就是机读码，OCR 读错一位校验位不要紧。 */
    @Test fun `a line two with fillers is kept when one check digit is misread`() {
        val misread = "L898902C31UTO7408122F1204159ZE184226B<<<<<10"
        assertThat(matched("passport", misread)).containsExactly(misread)
    }

    /** 没有填充符的第二行（美国护照只有一个「<」）全靠校验位：三个错两个就不算。 */
    @Test fun `a line two without fillers needs its check digits`() {
        assertThat(matched("passport", "E000077303USA6502056F3010149500101920<091824")).hasSize(1)
        assertThat(matched("passport", "E000077301USA6502051F3010149500101920<091824")).isEmpty()
    }

    @Test fun `passport ignores ordinary text of the same length`() {
        val filler = "THIS IS JUST A NORMAL SENTENCE OF LETTERS OK"
        assertThat(filler.length).isEqualTo(44)
        assertThat(matched("passport", filler)).isEmpty()
    }

    @Test fun `api key matches known prefixes`() {
        assertThat(matched("apikey", "key sk-aB3xQ9zL2mR7tK1vP0wY8n end")).hasSize(1)
        assertThat(matched("apikey", "ghp_aB3xQ9zL2mR7tK1vP0wY8nJ4hG2fD6sA1")).hasSize(1)
        assertThat(matched("apikey", "AKIAIOSFODNN7EXAMPLE")).hasSize(1)
    }

    @Test fun `api key matches a jwt`() {
        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r"
        assertThat(matched("apikey", jwt)).hasSize(1)
    }

    @Test fun `api key rejects a low-entropy string with the right prefix`() {
        assertThat(matched("apikey", "sk-aaaaaaaaaaaaaaaaaaaaaa")).isEmpty()
    }

    @Test fun `both rules are masked by default`() {
        assertThat(rule("passport").enabledByDefault).isTrue()
        assertThat(rule("apikey").enabledByDefault).isTrue()
    }
}
