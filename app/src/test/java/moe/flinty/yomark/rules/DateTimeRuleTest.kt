package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DateTimeRuleTest {

    private val rule = DateTimeRule()

    private fun matches(text: String): List<String> =
        rule.findIn(text).map { text.substring(it.range) }

    @Test fun `iso date with time`() {
        assertThat(matches("创建时间 2026-09-03 12:08:06"))
            .containsExactly("2026-09-03 12:08:06")
    }

    @Test fun `slash date without padding`() {
        assertThat(matches("下单 2026/9/3 到货")).containsExactly("2026/9/3")
    }

    @Test fun `chinese date`() {
        assertThat(matches("2026年9月3日 发货")).containsExactly("2026年9月3日")
    }

    @Test fun `bare clock time`() {
        assertThat(matches("状态栏 16:59 电量")).containsExactly("16:59")
    }

    @Test fun `time with seconds`() {
        assertThat(matches("耗时 12:08:07 结束")).containsExactly("12:08:07")
    }

    /** 物流轨迹里不带年份的写法；PP-OCR 可能把中间的空格读丢。 */
    @Test fun `month and day followed by a time`() {
        assertThat(matches("待取件 09-29 12:30")).containsExactly("09-29 12:30")
        assertThat(matches("待取件09-2912:30")).containsExactly("09-2912:30")
    }

    /** 月日后面不跟时间就不认：「3-2」这类串太容易是编号、比分。 */
    @Test fun `month and day alone is not a date`() {
        assertThat(matches("取件码 3-2-1234")).isEmpty()
        assertThat(matches("比分 3-1")).isEmpty()
    }

    @Test fun `an impossible month before a time is rejected`() {
        assertThat(matches("编号 13-45 12:30")).isEmpty()
    }

    /** 一个匹配吃掉日期和时间，不能拆成两条——拆开会在同一处叠出两个候选。 */
    @Test fun `date and time are one match`() {
        assertThat(rule.findIn("付款时间 2026-09-03 12:08:07")).hasSize(1)
    }

    // ---------- 英文 ----------

    /** 护照上的写法：日、大写的月份缩写、四位年。样张上一个都没认出来。 */
    @Test fun `day month year as printed on a passport`() {
        assertThat(matches("15 OCT 2020")).containsExactly("15 OCT 2020")
        assertThat(matches("born 5 February 1965 in")).containsExactly("5 February 1965")
        assertThat(matches("05-Feb-1965")).containsExactly("05-Feb-1965")
    }

    @Test fun `month first as written in us english`() {
        assertThat(matches("Order placed September 28, 2026")).containsExactly("September 28, 2026")
        assertThat(matches("Sep 28, 2026, 10:30 AM")).containsExactly("Sep 28, 2026, 10:30 AM")
        assertThat(matches("Tue, Oct 28 at 9:41 PM")).containsExactly("Oct 28 at 9:41 PM")
    }

    /** 四位年的数字日期，日月还是月日看不出来，有一种读得通就算。 */
    @Test fun `numeric dates with a four digit year`() {
        assertThat(matches("Delivered 10/11/2026")).containsExactly("10/11/2026")
        assertThat(matches("Datum 31.12.2025")).containsExactly("31.12.2025")
        assertThat(matches("due 13/25/2026")).isEmpty()
    }

    @Test fun `twelve hour clock`() {
        assertThat(matches("Pickup 3:45 pm today")).containsExactly("3:45 pm")
    }

    /** 不是月份的词：「Mark」「Decade」里有「Mar」「Dec」。 */
    @Test fun `words that start like a month are not months`() {
        assertThat(matches("Mark 12, 2026")).isEmpty()
        assertThat(matches("Decade 5 2026")).isEmpty()
    }

    // ---------- 反例 ----------

    @Test fun `price is not a date`() {
        assertThat(matches("实付款 ¥140 共1件")).isEmpty()
    }

    @Test fun `version number is not a date`() {
        assertThat(matches("客户端 v1.2.3 已更新")).isEmpty()
    }

    @Test fun `long digit run is not a date`() {
        assertThat(matches("2026090323001114571431156787")).isEmpty()
    }

    @Test fun `hour out of range is rejected`() {
        assertThat(matches("编号 88:12 结束")).isEmpty()
    }

    @Test fun `month out of range is rejected`() {
        assertThat(matches("编号 2026-19-03 结束")).isEmpty()
    }

    @Test fun `default state is outlined not masked`() {
        assertThat(rule.enabledByDefault).isFalse()
    }
}
