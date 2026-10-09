package moe.flinty.yomark.rules

import android.graphics.RectF
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextElement
import moe.flinty.yomark.core.model.TextLine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 从字面猜出的人名要有旁证（整页、出厂规则表）。
 *
 * 真机反馈：淘宝商品规格页上药名、规格词被圈满了「人名」。字面上分不开「福来恩」和「沐晨冉」，
 * 页面上分得开：沐晨冉下面一行是打了星的证件号，福来恩周围什么都没有。
 */
@RunWith(RobolectricTestRunner::class)
class NameAnchorPageTest {

    /** 像 PP-OCR 那样每个字一个 element：字宽 20px、字高 20px，行距 40px，从左边 40px 起。 */
    private fun page(vararg texts: String): List<TextLine> = texts.mapIndexed { row, text ->
        val top = 100f + row * 40f
        val els = text.mapIndexed { i, c ->
            TextElement(Quad.fromRect(RectF(40f + i * 20f, top, 60f + i * 20f, top + 20f)), c.toString(), i..i)
        }
        TextLine(Quad.fromRect(RectF(40f, top, 40f + text.length * 20f, top + 20f)), text, 0.95f, els)
    }

    private suspend fun names(lines: List<TextLine>, keepUnanchored: Boolean = false): List<Candidate> =
        RuleClassifier(DefaultRuleSet.rules, keepUnanchored = keepUnanchored).classify(lines)
            .filter { it.kind == SensitiveKind.PERSON_NAME }

    private fun Candidate.text(lines: List<TextLine>): String {
        val b = quad.bounds()
        val line = lines.single { it.quad.bounds().top == b.top }
        val first = ((b.left - 40f) / 20f).toInt()
        val last = ((b.right - 40f) / 20f).toInt() - 1
        return line.text.substring(first, last + 1)
    }

    /** 那张截图：一个人名都不该有。逐行过滤挡掉大半，剩下的「单月」附近没有旁证。 */
    @Test fun `the product spec page from the bug report has no names`() = runTest {
        val lines = page(
            "退货宝·7天价保·假一赔四",
            "平台加补后¥64.3 | 优惠前¥120",
            "立减35元",
            "（限购3件）有货",
            "套餐类型（8）",
            "【8周以上猫通用】福来恩3支",
            "【8周以上猫通用】福来恩1支",
            "【幼猫单月装】福来恩1支+海乐妙1粒",
            "【成猫单月装】福来恩1支+海乐妙1粒",
            "成猫体重2-8kg，单月到半年按补货周期选",
            "【幼猫季度装】福来恩3支+海乐妙3粒",
            "【成猫季度装】福来恩3支+海乐妙3粒",
            "【幼猫半年装】福来恩6支+海乐妙6粒",
            "【成猫半年装】福来恩6支+海乐妙6粒",
            "优选服务",
            "加入购物车",
        )
        assertThat(names(lines)).isEmpty()
    }

    /** 名字在上、打了星的证件号在下：有旁证，照「人名」的出厂设置打码。 */
    @Test fun `a name above a masked id number is masked`() = runTest {
        val lines = page("出票成功", "李思雨", "3201**********1234", "查看乘车码")
        val found = names(lines).single()
        assertThat(found.text(lines)).isEqualTo("李思雨")
        assertThat(found.enabledByDefault).isTrue()
    }

    /** 同一行写着票种，也算旁证。 */
    @Test fun `a passenger line with a ticket type is masked`() = runTest {
        val lines = page("沐晨冉 成人")
        assertThat(names(lines).single().enabledByDefault).isTrue()
    }

    /** 旁证隔得太远（中间隔了好几行）不算。 */
    @Test fun `an id number several rows away does not vouch for a name`() = runTest {
        val lines = page("李思雨", "出票成功", "温馨提示", "查看乘车码", "3201**********1234")
        assertThat(names(lines)).isEmpty()
    }

    /** 聊天里随口提到的人名，附近没有旁证：出厂不圈；设置里打开后只圈不打码。 */
    @Test fun `a name in a chat sentence is dropped unless the setting keeps it`() = runTest {
        val lines = page("对了，转告周振宇周三的会", "好的")
        assertThat(names(lines)).isEmpty()
        val kept = names(lines, keepUnanchored = true).single()
        assertThat(kept.text(lines)).isEqualTo("周振宇")
        assertThat(kept.enabledByDefault).isFalse()
    }

    /** 字段名、电话锚定的名字本来就不要旁证，照常打码。 */
    @Test fun `anchored names are untouched`() = runTest {
        val lines = page("收货人：刘洋", "沐晨冉 86-186****3392 号码保护中")
        assertThat(names(lines).map { it.text(lines) to it.enabledByDefault })
            .containsExactly("刘洋" to true, "沐晨冉" to true)
    }
}
