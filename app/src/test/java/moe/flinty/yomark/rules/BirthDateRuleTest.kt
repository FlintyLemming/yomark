package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.core.model.SensitiveKind
import org.junit.Test

/**
 * 出生日期：字段名指明了的那个日期，默认打码。这里是同一行的写法；字段名在上、日期在下的见 StackedFieldTest，
 * 机读码对照见 MrzTest。
 */
class BirthDateRuleTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "birthdate" }

    private fun matched(text: String) = rule.findIn(text).map { text.substring(it.range) }

    @Test fun `chinese forms and id cards`() {
        assertThat(matched("出生日期：1990-01-01")).containsExactly("1990-01-01")
        assertThat(matched("出生 1990年1月1日")).containsExactly("1990年1月1日")
        // ML Kit 中文识别器逐字切开的身份证一行
        assertThat(matched("出 生 1990 年 1 月 1 日")).hasSize(1)
    }

    @Test fun `english labels in any case`() {
        assertThat(matched("DOB 01/07/1973")).containsExactly("01/07/1973")
        assertThat(matched("Date of Birth: 05 FEB 1965")).containsExactly("05 FEB 1965")
        assertThat(matched("DATE OF BIRTH 05.02.65")).containsExactly("05.02.65")
        assertThat(matched("Born: Feb 5, 1965")).containsExactly("Feb 5, 1965")
    }

    /** 字段名后面不是日期：出生地、只有年份。 */
    @Test fun `a label without a date after it`() {
        assertThat(matched("出生地 江苏")).isEmpty()
        assertThat(matched("Born in 1990")).isEmpty()
        assertThat(matched("Birthday party at 7")).isEmpty()
    }

    /** 没有字段名的日期还是日期时间，只圈出。 */
    @Test fun `a date without a label is not a birth date`() {
        assertThat(matched("Order placed September 28, 2026")).isEmpty()
    }

    @Test fun `masked by default as its own kind`() {
        assertThat(rule.enabledByDefault).isTrue()
        assertThat(rule.kind).isEqualTo(SensitiveKind.BIRTH_DATE)
    }
}
