package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 字段名自己成一段、取件码另成一段（LoneLabel），按出厂规则表跑取件码一类：码在字段名下面（左对齐、居中），
 * 或者在同一排右边、OCR 没拼进同一行（隔得远、字号大得多）。测评页上整页的回归见 PickupCodeLoneLabelRegressionTest。
 */
class LoneLabelTest {

    private val rule = DefaultRuleSet.rules.first { it.id == "pickup" }

    private fun found(page: Page): List<String> = rule.findOnPage(page).map { page.text(it) }

    /** 短信卡片（测评页 zsms-pickup-codes）：小字的「取件码」，下面一行大字的码，再下面是「复制取件码」按钮。 */
    @Test fun `the code under a label on its own`() {
        val p = page(
            Row("取件码", 40f, 0f),
            Row(26f, 40f, Segment("8-5-2001", 40f, 232f)),
            Row("复制取件码", 40f, 80f),
        )
        assertThat(found(p)).containsExactly("8-5-2001")
    }

    /** 码居中、字号大，比字段名宽出一截，左沿在字段名左边：整个码都遮上。 */
    @Test fun `a centered code wider than the label`() {
        val p = page(Row("取件码", 500f, 0f), Row(26f, 48f, Segment("6-3-2208", 400f, 636f)))
        assertThat(found(p)).containsExactly("6-3-2208")
    }

    /** 逐字切开的「取 件 码」。 */
    @Test fun `a label split into single characters`() {
        val p = page(Row("取 件 码", 40f, 0f), Row(26f, 40f, Segment("8-5-2001", 40f, 232f)))
        assertThat(found(p)).containsExactly("8-5-2001")
    }

    /** 订单详情：字段名在左，码靠右对齐，中间隔着二十多个字高，OCR 分成了两行。 */
    @Test fun `the code at the far right of the row`() {
        val p = page(Row(0f, 20f, Segment("取件码", 40f, 76f)), Row(0f, 20f, Segment("6-3-2208", 560f, 656f)))
        assertThat(found(p)).containsExactly("6-3-2208")
    }

    /** 「取件码」右边一个大字的码，再右边是「复制」：字高差一倍多，OCR 不拼成一行。 */
    @Test fun `a big code right of a small label`() {
        val p = page(
            Row(10f, 20f, Segment("取件码", 40f, 76f)),
            Row(0f, 44f, Segment("3-2-1234", 100f, 300f)),
            Row(10f, 20f, Segment("复制", 340f, 364f)),
        )
        assertThat(found(p)).containsExactly("3-2-1234")
    }

    /** 字段名那一排右边是「复制」（拼成了一行「取件码 复制」，中间隔得开）：它不是码，码在下面一行。 */
    @Test fun `a copy button beside the label, the code below`() {
        val p = page(
            Row(0f, 20f, Segment("取件码", 40f, 76f), Segment("复制", 600f, 624f)),
            Row(26f, 40f, Segment("8-5-2001", 40f, 232f)),
        )
        assertThat(found(p)).containsExactly("8-5-2001")
    }

    /** 同一排右边已经接上了码，下面一行是别的东西，不再往下找。 */
    @Test fun `a code beside the label stops the search`() {
        val p = page(
            Row(0f, 20f, Segment("取件码", 40f, 76f), Segment("1-58908", 120f, 204f), Segment("复制", 300f, 324f)),
            Row("4-1-2002", 40f, 26f),
        )
        assertThat(found(p)).containsExactly("1-58908")
    }

    /** 按钮、标题里带着「取件码」「取件」（测评页上的「复制取件码」「出示取件码」「3 条待取件」）：下面、右边的码不归它们。 */
    @Test fun `buttons and headings are not labels`() {
        listOf("复制取件码", "出示取件码", "查看取件码", "3 条待取件").forEach { text ->
            assertThat(found(page(Row(text, 40f, 0f), Row("8-5-2001", 40f, 26f)))).isEmpty()
            assertThat(found(page(Row(0f, 20f, Segment(text, 40f, 100f)), Row(0f, 20f, Segment("6-3-2208", 400f, 496f)))))
                .isEmpty()
        }
    }

    /** 下面紧挨着的一行不像码（下一个字段名），不遮，也不越过它往下找：再下面那一行离字段名也够近，照样不要。 */
    @Test fun `a label below is not the code, nothing further down is taken`() {
        val p = page(Row("取件码", 40f, 0f), Row("自提点", 40f, 22f), Row("6-3-2208", 40f, 44f))
        assertThat(found(p)).isEmpty()
    }

    /** 码至少有一位数字：没取到码时的「暂无」、一串字母都不是。 */
    @Test fun `the value needs a digit`() {
        listOf("暂无", "已取件", "ABC-DEF").forEach { text ->
            assertThat(found(page(Row("取件码", 40f, 0f), Row(text, 40f, 26f)))).isEmpty()
        }
    }

    /** 太远的不是：字段名下面隔了好几行高的空白。 */
    @Test fun `a code far below is not taken`() {
        assertThat(found(page(Row("取件码", 40f, 0f), Row("8-5-2001", 40f, 120f)))).isEmpty()
    }

    /** 别的一栏的不是：下面那一段和字段名横向没有交叠。 */
    @Test fun `a code below another column is not taken`() {
        assertThat(found(page(Row("取件码", 40f, 0f), Row("8-5-2001", 300f, 26f)))).isEmpty()
    }
}
