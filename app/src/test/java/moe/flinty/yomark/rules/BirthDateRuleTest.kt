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

    /**
     * 证件上的写法：身份证上 OCR 在年份里补的空格，出生医学证明带时分，户口簿连写的八位，
     * 台湾的民国纪年、日本年号、中文数字。
     */
    @Test fun `document date forms after a label`() {
        assertThat(matched("出生201 2 年4 月17日")).containsExactly("201 2 年4 月17日")
        assertThat(matched("出生 2019 年 2 月 6 日")).containsExactly("2019 年 2 月 6 日")
        assertThat(matched("出生时间 Date of Birth 2020年06月19日17时00分")).containsExactly("2020年06月19日17时00分")
        assertThat(matched("出生日期 19900101")).containsExactly("19900101")
        assertThat(matched("出生年月日 民國79年1月1日")).containsExactly("民國79年1月1日")
        assertThat(matched("生年月日 平成2年 1月 1日")).containsExactly("平成2年 1月 1日")
        assertThat(matched("出生日期 一九九八年三月五日")).containsExactly("一九九八年三月五日")
    }

    /** 没有字段名、后面跟着「生」的：毕业证、日本证件。「生效」「生产」不算。 */
    @Test fun `a date followed by sheng`() {
        assertThat(matched("张三，男，一九九八年三月五日生，于")).containsExactly("一九九八年三月五日")
        assertThat(matched("平成2年 1月 1日生")).containsExactly("平成2年 1月 1日")
        assertThat(matched("1998年3月5日出生于南京")).containsExactly("1998年3月5日")
        assertThat(matched("本协议自2026年9月3日生效")).isEmpty()
        assertThat(matched("2026年9月3日生病了")).isEmpty()
    }

    /** 读出来的年月日：数字里的空格去掉，纪年换成公历。 */
    @Test fun `readings of document dates`() {
        assertThat(Dates.readings("201 2 年4 月17日")).containsExactly(Triple(2012, 4, 17))
        assertThat(Dates.readings("2020年06月19日17时00分")).containsExactly(Triple(2020, 6, 19))
        assertThat(Dates.readings("19900101")).containsExactly(Triple(1990, 1, 1))
        assertThat(Dates.readings("民國79年1月1日")).containsExactly(Triple(1990, 1, 1))
        assertThat(Dates.readings("平成2年 1月 1日")).containsExactly(Triple(1990, 1, 1))
        assertThat(Dates.readings("令和元年5月1日")).containsExactly(Triple(2019, 5, 1))
        assertThat(Dates.readings("一九九八年三月五日")).containsExactly(Triple(1998, 3, 5))
        assertThat(Dates.readings("二O一九年十二月三十一日")).containsExactly(Triple(2019, 12, 31))
        assertThat(Dates.readings("二〇〇〇年二月二十九日")).containsExactly(Triple(2000, 2, 29))
    }

    /** 学生证、工作证、简历上只写到月的。月份不对、后面还跟着日的不算这一种。 */
    @Test fun `a year and month after the label`() {
        assertThat(matched("出生年月 1990年01月")).containsExactly("1990年01月")
        assertThat(matched("出生年月：2002.03")).containsExactly("2002.03")
        assertThat(matched("出生日期 1993-07")).containsExactly("1993-07")
        assertThat(matched("出生年月 2002.03.15")).containsExactly("2002.03.15")
        assertThat(matched("出生年月 2002.13")).isEmpty()
        assertThat(matched("出生年月写到月就行")).isEmpty()
    }

    /** 后面紧跟着数字的是一串编号；连写的八位要是日历上有的一天。 */
    @Test fun `a compact date must stand alone and be real`() {
        assertThat(matched("出生 202001011234")).isEmpty()
        assertThat(matched("生日 20240230")).isEmpty()
        assertThat(matched("生日 20240229")).containsExactly("20240229")
    }

    /** ML Kit 逐字切开的「生 效」拼回来才看得出是「生效」。 */
    @Test fun `a split sheng xiao is not a birth`() {
        assertThat(matched("自 2026 年 9 月 3 日 生 效")).isEmpty()
        assertThat(matched("1998 年 3 月 5 日 生")).hasSize(1)
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
