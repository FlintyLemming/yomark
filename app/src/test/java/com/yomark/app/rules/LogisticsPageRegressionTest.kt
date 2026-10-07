package com.yomark.app.rules

import android.graphics.RectF
import com.yomark.app.core.geometry.Quad
import com.yomark.app.core.model.SensitiveKind
import com.yomark.app.core.model.SensitiveKind.DATETIME
import com.yomark.app.core.model.SensitiveKind.PERSON_NAME
import com.yomark.app.core.model.SensitiveKind.PHONE
import com.yomark.app.core.model.SensitiveKind.PICKUP_CODE
import com.yomark.app.core.model.SensitiveKind.POSTAL_ADDRESS
import com.yomark.app.core.model.SensitiveKind.TRACKING_NO
import com.yomark.app.core.model.TextElement
import com.yomark.app.core.model.TextLine
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 真机漏检的回归样本：菜鸟 app 的快递详情页（用户反馈截图，文字照抄）。
 *
 * 0.2.1 在这一页上只认出了两个时间和快递单号，收件人姓名、打了星的电话、
 * 收件地址、驿站地址、取件码全部漏掉。
 *
 * 中文识别器的 element 到底切到词还是切到字，真机上还没量过，
 * 所以同一页按两种切法各跑一遍，结果必须一样。
 */
@RunWith(RobolectricTestRunner::class)
class LogisticsPageRegressionTest {

    private val page = listOf(
        "16:08",
        "已放入菜鸟驿站",
        "距收货地193米",
        "客服 包裹",
        "收 合肥市",
        "申通快递 773443899958908 复制 | 打电话",
        "待取件 09-29 12:30",
        "您的快件已暂存至合肥四方新村10栋101室店菜鸟驿",
        "站，请凭取货码及时领取。如有疑问请联系...展开",
        "合肥四方新村10栋101室店",
        "四方新村10栋101室",
        "取件码 1-58908 复制",
        "找人帮取 扫码取件",
        "查看更多物流明细",
        "送至 祁门路33号四方新村23幢605室",
        "沐晨冉 86-186****3392 号码保护中 取件出示虚拟号>",
        "天猫 乐事旗舰店 >",
        "直播间秒杀中 >",
        "【乐事X蔚蓝档案】薯片多口味... ¥99.9",
        "退货宝 破损包退 极速退款 x1",
        "查看全部订单信息",
    )

    private val expected = listOf(
        DATETIME to "16:08",
        TRACKING_NO to "773443899958908",
        DATETIME to "09-29 12:30",
        POSTAL_ADDRESS to "合肥四方新村10栋101室",       // 通知里的驿站地址
        POSTAL_ADDRESS to "合肥四方新村10栋101室",       // 驿站名
        POSTAL_ADDRESS to "四方新村10栋101室",           // 驿站地址
        PICKUP_CODE to "1-58908",
        POSTAL_ADDRESS to "祁门路33号四方新村23幢605室", // 收件地址
        PERSON_NAME to "沐晨冉",
        PHONE to "86-186****3392",
    )

    /** 把每个汉字拆成独立 token，数字、拉丁、标点连成一段——中文识别器逐字切分时的样子。 */
    private fun splitEveryCharacter(line: String): String =
        Regex("""[\u4e00-\u9fff]|[^\s\u4e00-\u9fff]+""").findAll(line).joinToString(" ") { it.value }

    /**
     * 不去重：一种类型只有一条规则，几种认法在规则里面已经排好先后，同一处只出一个区间。
     * 同一行里出现两个一样的结果，就是有两种认法在抢同一处。
     */
    private fun findings(lines: List<String>): List<Pair<SensitiveKind, String>> =
        lines.flatMap { line ->
            DefaultRuleSet.rules.flatMap { rule ->
                rule.findIn(line).map { rule.kind to line.substring(it.range) }
            }
        }

    @Test fun `every sensitive item on the page is found and nothing else`() {
        assertThat(findings(page)).containsExactlyElementsIn(expected)
    }

    /** 逐字切分的行，区间映射回去自然带着拼接用的空格；比较时去掉，框的边界另有测试。 */
    @Test fun `the same page split into single characters gives the same result`() {
        val found = findings(page.map(::splitEveryCharacter))
        found.forEach { (_, text) -> assertThat(text).isEqualTo(text.trim()) }
        assertThat(found.map { (kind, text) -> kind to text.replace(" ", "") })
            .containsExactlyElementsIn(expected.map { (kind, text) -> kind to text.replace(" ", "") })
    }

    /** 名字、电话、地址、取件码都默认打码——漏检的这几样恰恰是最不该露出去的。 */
    @Test fun `what was missed is masked by default`() {
        val masked = DefaultRuleSet.rules.filter { it.enabledByDefault }.map { it.kind }
        assertThat(masked).containsAtLeast(PERSON_NAME, PHONE, POSTAL_ADDRESS, PICKUP_CODE)
    }

    /**
     * 逐字切分时，映射回原串的区间要落在正确的 element 上：
     * 名字的框盖住「沐」「晨」「冉」三个字，不多不少；电话的框就是号码那一个 element。
     */
    @Test fun `boxes land on the right elements of a split line`() = runTest {
        val tokens = listOf("沐", "晨", "冉", "86-186****3392", "号", "码")
        var cursor = 0
        var x = 0f
        val elements = tokens.map { tok ->
            val width = 30f * tok.length
            val e = TextElement(Quad.fromRect(RectF(x, 0f, x + width, 30f)), tok, cursor until cursor + tok.length)
            cursor += tok.length + 1
            x += width + 6f
            e
        }
        val line = TextLine(Quad.fromRect(RectF(0f, 0f, x, 30f)), tokens.joinToString(" "), 0.9f, elements)

        val out = RuleClassifier(DefaultRuleSet.rules).classify(listOf(line))

        // 电话锚定与字面识别都认出了名字，人名规则只出一个（锚定的那个，打码），框正好盖住三个字
        val name = out.single { it.kind == PERSON_NAME }
        assertThat(name.enabledByDefault).isTrue()
        assertThat(name.quad.bounds().left).isEqualTo(elements[0].quad.bounds().left)
        assertThat(name.quad.bounds().right).isEqualTo(elements[2].quad.bounds().right)
        assertThat(out.single { it.kind == PHONE }.quad.bounds()).isEqualTo(elements[3].quad.bounds())
    }
}
