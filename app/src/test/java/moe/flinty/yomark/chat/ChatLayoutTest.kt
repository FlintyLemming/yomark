package moe.flinty.yomark.chat

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 聊天截图的版面规则，在合成测试页上跑（[ChatFixtures]）：OCR 行是生成页面时记下的，不走真模型，
 * 量的是版面规则本身。真模型读出来的行在 [ChatDetectorTest] 里跑。
 *
 * 9 种皮肤（微信、QQ、Telegram、WhatsApp、iMessage、LINE、Messenger，各有浅色深色、单聊群聊；Discord 两张；
 * 微信、QQ 的长截图各一张）和 5 张不是聊天的页（通讯录、会话列表、设置、商品列表、文章）。
 */
class ChatLayoutTest {

    /** 一张图分析一次，整个类共用（JUnit 每个测试方法新建一个实例）。 */
    companion object {
        private val results by lazy { ChatFixtures.all.associateWith { ChatLayout.analyze(it.lines, it.pixels) } }
    }

    private val all = ChatFixtures.all

    @Test fun `every page is told apart as chat or not`() {
        results.forEach { (fx, r) ->
            assertWithMessage("${fx.name}: 得分 ${r.score} ${r.reasons}").that(r.isChat).isEqualTo(fx.isChat)
        }
    }

    @Test fun `pages that are not chats get nothing masked`() {
        results.filterKeys { !it.isChat }.forEach { (fx, r) ->
            assertWithMessage(fx.name).that(r.avatars + r.names.map { it.box } + listOfNotNull(r.title?.box)).isEmpty()
        }
    }

    /** 会话列表、通讯录也有一列头像加名字，最容易被当成聊天页。 */
    @Test fun `conversation list and contacts are not chats`() {
        assertThat(ChatLayout.analyze(ChatFixtures.named("neg-001").lines, ChatFixtures.named("neg-001").pixels).isChat).isFalse()
        assertThat(ChatLayout.analyze(ChatFixtures.named("neg-002").lines, ChatFixtures.named("neg-002").pixels).isChat).isFalse()
    }

    @Test fun `every chat title is found and stripped of the member count`() {
        results.filterKeys { it.isChat }.forEach { (fx, r) ->
            val truth = fx.title!!
            assertWithMessage("${fx.name}: ${r.title}").that(r.title).isNotNull()
            assertWithMessage(fx.name).that(ChatFixtures.matches(r.title!!.box, truth.box)).isTrue()
            assertWithMessage(fx.name).that(r.title!!.text).doesNotContainMatch("[(（]\\d+[)）]$")
        }
    }

    /** 头像：认出至少 95%，多出来的不超过 2%。漏掉的是和底色几乎一样的淡头像。 */
    @Test fun `avatars are found across apps`() {
        val t = ChatFixtures.Tally()
        results.filterKeys { it.isChat }.forEach { (fx, r) -> ChatFixtures.tally(r.avatars, fx.avatars, t) }
        assertWithMessage(ChatFixtures.report()).that(t.recall).isAtLeast(0.95f)
        assertWithMessage(ChatFixtures.report()).that(t.extra.toFloat() / t.truth).isAtMost(0.02f)
    }

    /** 昵称：消息上方的、气泡里第一行的、Discord 消息头里的、系统消息里的，一共认出至少 95%。 */
    @Test fun `display names are found across apps`() {
        val t = ChatFixtures.Tally()
        results.filterKeys { it.isChat }.forEach { (fx, r) -> ChatFixtures.tally(r.names.map { it.box }, fx.names.map { it.box }, t) }
        assertWithMessage(ChatFixtures.report()).that(t.recall).isAtLeast(0.95f)
        assertWithMessage(ChatFixtures.report()).that(t.extra.toFloat() / t.truth).isAtMost(0.02f)
    }

    /** 每种皮肤都认得出，不是靠一两个应用把总数撑起来。 */
    @Test fun `every app gets most of its avatars and names`() {
        results.filterKeys { it.isChat }.entries.groupBy { it.key.app }.forEach { (app, pages) ->
            val a = ChatFixtures.Tally()
            val n = ChatFixtures.Tally()
            pages.forEach { (fx, r) ->
                ChatFixtures.tally(r.avatars, fx.avatars, a)
                ChatFixtures.tally(r.names.map { it.box }, fx.names.map { it.box }, n)
            }
            assertWithMessage("$app 头像").that(a.recall).isAtLeast(0.85f)
            assertWithMessage("$app 昵称").that(n.recall).isAtLeast(0.85f)
        }
    }

    /** 系统消息「"张三" 撤回了一条消息」只遮名字，不遮整行。 */
    @Test fun `a name in a system message is boxed alone`() {
        val fx = all.first { f -> f.isChat && f.lines.any { it.text.contains("撤回了一条消息") } }
        val line = fx.lines.first { it.text.contains("撤回了一条消息") }
        val r = results.getValue(fx)
        val name = r.names.first { it.box.top >= line.box.top - 1 && it.box.bottom <= line.box.bottom + 1 }
        assertThat(line.text).contains(name.text)
        assertThat(name.box.width).isLessThan(line.box.width * 0.7f)
    }

    /** Discord、Slack 的「名字 + 时间」被 OCR 读成一行时，只遮名字那一截。 */
    @Test fun `a name read together with its timestamp is cut out of the line`() {
        val line = ChatLine(Box(70f, 400f, 400f, 420f), "Rob Fletcher Today at 10:24 AM")
        val body = ChatLine(Box(70f, 428f, 300f, 448f), "Meeting moved to 3pm")
        val z = zonesOf(listOf(line, body))
        val names = Names.headerRows(z)
        assertThat(names.map { it.text }).containsExactly("Rob Fletcher")
        assertThat(names.single().box.right).isLessThan(line.box.right * 0.6f)
    }

    /** 默认头像上的姓和旁边的昵称被 OCR 读成一行：切掉压在头像上的字，框收到剩下的字上。 */
    @Test fun `text printed on an avatar is cut off the name beside it`() {
        val avatar = Box(12f, 135f, 56f, 179f)
        val chars = listOf(Box(20f, 135f, 50f, 170f), null, Box(64f, 139f, 78f, 156f), null, Box(82f, 139f, 96f, 156f))
        val cut = Avatars.cutOff(ChatLine(Box(20f, 135f, 96f, 170f), "卢 曹 雨", chars), listOf(avatar), 540f)!!
        assertThat(cut.text).isEqualTo("曹 雨")
        assertThat(cut.box).isEqualTo(Box(64f, 139f, 96f, 156f))
        // 没有逐字框时按字数比例切
        assertThat(Avatars.cutOff(ChatLine(Box(20f, 135f, 96f, 170f), "卢曹雨"), listOf(avatar), 540f)!!.text).isEqualTo("曹雨")
        // 整行都在头像上的不要；不挨着头像的原样留着
        assertThat(Avatars.cutOff(ChatLine(Box(20f, 140f, 50f, 170f), "卢"), listOf(avatar), 540f)).isNull()
        val apart = ChatLine(Box(70f, 300f, 200f, 320f), "收到")
        assertThat(Avatars.cutOff(apart, listOf(avatar), 540f)).isSameInstanceAs(apart)
    }

    @Test fun `titles lose the member count and the chevron`() {
        assertThat(Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "周末羽毛球群(23)")).text).isEqualTo("周末羽毛球群")
        assertThat(Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "小区业主群（3期）（188）")).text).isEqualTo("小区业主群（3期）")
        assertThat(Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "Emily Carter ›")).text).isEqualTo("Emily Carter")
        val cut = Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "周末羽毛球群(23)"))
        assertThat(cut.box.right).isLessThan(300f)
    }

    /** 横着的图（电脑版、平板横屏）不在这一版里认。 */
    @Test fun `landscape images are skipped`() {
        val px = Pixels(200, 100, IntArray(200 * 100))
        assertThat(ChatLayout.analyze(listOf(ChatLine(Box(10f, 10f, 50f, 20f), "发送")), px)).isEqualTo(ChatFindings.NONE)
    }

    private fun zonesOf(lines: List<ChatLine>): Zones = Zones.of(lines, 540f, 1170f)
}
