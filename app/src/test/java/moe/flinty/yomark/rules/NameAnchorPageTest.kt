package moe.flinty.yomark.rules

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.test.runTest
import moe.flinty.yomark.core.model.Candidate
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.core.model.TextLine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import moe.flinty.yomark.R
import android.content.Context
import androidx.test.core.app.ApplicationProvider

/**
 * 从字面猜出的人名要有旁证（整页、出厂规则表）。
 *
 * 真机反馈：淘宝商品规格页上药名、规格词被圈满了「人名」。字面上分不开「福来恩」和「沐晨冉」，
 * 页面上分得开：沐晨冉下面一行是打了星的证件号，福来恩周围什么都没有。
 */
@RunWith(RobolectricTestRunner::class)
class NameAnchorPageTest {

    private fun page(vararg texts: String) = textLines(*texts)

    private suspend fun names(
        lines: List<TextLine>,
        maskUnanchored: Boolean = false,
        anchors: List<NameAnchor> = DefaultRuleSet.nameAnchors,
    ): List<Candidate> =
        RuleClassifier(DefaultRuleSet.rules, maskUnanchored = maskUnanchored, anchors = anchors).classify(lines)
            .filter { it.kind == SensitiveKind.PERSON_NAME }

    private fun Candidate.text(lines: List<TextLine>): String = textIn(lines)

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

    /** 聊天里随口提到的人名，附近没有旁证：出厂不打；设置里打开「没有旁证的也打码」后照样打码。 */
    @Test fun `a name in a chat sentence is dropped unless the setting masks it`() = runTest {
        val lines = page("对了，转告周振宇周三的会", "好的")
        assertThat(names(lines)).isEmpty()
        val kept = names(lines, maskUnanchored = true).single()
        assertThat(kept.text(lines)).isEqualTo("周振宇")
        assertThat(kept.enabledByDefault).isTrue()
    }

    /** 字段名、电话锚定的名字本来就不要旁证，照常打码。 */
    @Test fun `anchored names are untouched`() = runTest {
        val lines = page("收货人：刘洋", "沐晨冉 86-186****3392 号码保护中")
        assertThat(names(lines).map { it.text(lines) to it.enabledByDefault })
            .containsExactly("刘洋" to true, "沐晨冉" to true)
    }

    /** 英文页同一个道理：名单人名下面一行是地址，算数；聊天里提到的品牌、地名附近什么都没有，不圈。 */
    @Test fun `an english name above an address is masked and a lone one is not`() = runTest {
        val lines = page("Olivia Bennett", "Flat 4, 27 Kingsley Road", "Manchester M14 6PL")
        assertThat(names(lines).map { it.text(lines) to it.enabledByDefault }).containsExactly("Olivia Bennett" to true)
        assertThat(names(page("Loved the Ralph Lauren cap", "see you at Grace Church"))).isEmpty()
    }

    // ---------- 旁证表：每一种一个例子 ----------

    /**
     * 旁证表（DefaultRuleSet.nameAnchors）里每一种旁证的例子：一页截图，和这页上该被坐实的那个名字。
     * 这个名字单独成页时是猜测、没有旁证；有了例子里的那一处，它就按「人名」的出厂设置打码。
     *
     * 往旁证表里加一行，就在这里加一个例子；漏了，`every anchor has an example` 会提醒。
     */
    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    private val examples = mapOf(
        R.string.anchor_title to Example("王小明", "王小明先生，您的快递到了"),
        R.string.anchor_phone to Example("李思雨", "李思雨", "+86 138 0013 8000"),
        R.string.anchor_email to Example("李思雨", "李思雨", "lisiyu@example.com"),
        R.string.anchor_address to Example("李思雨", "李思雨", "送至 祁门路33号四方新村23幢605室"),
        R.string.anchor_id to Example("李思雨", "李思雨", "11010519491231002X"),
        R.string.anchor_masked_number to Example("李思雨", "李思雨", "3201**********1234"),
        R.string.anchor_field to Example("李思雨", "乘车人", "李思雨"),
        R.string.anchor_ticket to Example("李思雨", "李思雨 成人"),
        R.string.anchor_english_field to Example("Emily Carter", "Passenger", "Emily Carter"),
    )

    private class Example(val name: String, vararg val page: String)

    @Test fun `every anchor has an example`() {
        assertThat(examples.keys).containsExactlyElementsIn(DefaultRuleSet.nameAnchors.map { it.label })
    }

    /** 每一种旁证单独拿出来，都坐得实它例子里的那个名字。 */
    @Test fun `each anchor on its own vouches for the name in its example`() = runTest {
        DefaultRuleSet.nameAnchors.forEach { anchor ->
            val example = examples.getValue(anchor.label)
            val lines = page(*example.page)
            val found = names(lines, anchors = listOf(anchor)).filter { it.text(lines) == example.name }
            assertWithMessage(res.getString(anchor.label)).that(found).hasSize(1)
            assertWithMessage(res.getString(anchor.label)).that(found.single().enabledByDefault).isTrue()
        }
    }

    /** 反过来：名字单独成页时，没有旁证，出厂不圈。例子里的名字是被旁证坐实的，不是本来就锚定的。 */
    @Test fun `without its anchor the name in each example is not reported`() = runTest {
        examples.values.forEach { example ->
            assertWithMessage(example.name).that(names(page(example.name))).isEmpty()
        }
    }

    /** 称呼要紧挨着名字：上一行写着「先生您好」，说明不了下一行的名字是谁。 */
    @Test fun `a title on another line does not vouch for a name`() = runTest {
        assertThat(names(page("先生您好", "李思雨"))).isEmpty()
    }

    /** 称呼在名字里头（HanLP 把「李女士」认成一个词）也算；HanLP 认不出的「张先生」也认。 */
    @Test fun `a name that carries its own title is masked`() = runTest {
        listOf("【合肥市】快件已被李女士签收" to "李女士", "张先生您好" to "张先生").forEach { (text, name) ->
            val lines = page(text)
            val found = names(lines).single()
            assertThat(found.text(lines)).isEqualTo(name)
            assertThat(found.enabledByDefault).isTrue()
        }
    }

    /** 人名页上「旁证是……」那句话照着旁证表拼，表里每一种都写进去。 */
    @Test fun `the settings note lists every anchor`() {
        val note = RuleCatalog.nameAnchorNote(res)
        DefaultRuleSet.nameAnchors.forEach { assertThat(note).contains(res.getString(it.label)) }
    }
}
