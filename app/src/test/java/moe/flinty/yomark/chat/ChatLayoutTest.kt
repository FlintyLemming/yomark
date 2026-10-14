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
        // 原图上和标题读成一段的返回箭头、Discord 的「#」，名字后面读成「·」的「›」
        assertThat(Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "< 闲聊")).text).isEqualTo("闲聊")
        assertThat(Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "# general")).text).isEqualTo("general")
        assertThat(Titles.clean(ChatLine(Box(100f, 60f, 300f, 90f), "Book Club ·")).text).isEqualTo("Book Club")
    }

    /**
     * 原图上返回箭头、头像上的字、标题、状态挨得近，OCR 连成一行：整行带着「在线」，会被当成状态行扔掉。
     * 按逐字框拆开以后，贴边的箭头、印在头像上的字（底下不是顶栏的颜色）都不是标题。
     */
    @Test fun `a header line read as one is split and the title is the piece on the page color`() {
        val page = 0xFFEDEDED.toInt()
        val px = paint(1080, 400, page, Box(100f, 108f, 166f, 174f) to 0xFF5B8DEF.toInt())   // 头像
        fun row(x0: Float, top: Float, bottom: Float, n: Int, w: Float) = (0 until n).map { Box(x0 + it * (w + 2f), top, x0 + it * (w + 2f) + w, bottom) }
        val chars = listOf(Box(24f, 136f, 64f, 144f), null, Box(112f, 118f, 154f, 162f), null) +
            row(190f, 125f, 155f, 4, 30f) + null + row(380f, 131f, 151f, 4, 20f)
        val line = ChatLine(Box(20f, 115f, 478f, 165f), "一 马 不吃香菜 最近在线", chars)
        assertThat(Titles.pick(listOf(line), 1080f)).isNull()

        val pieces = Titles.split(listOf(line), 1080f)
        assertThat(pieces.map { it.text }).containsExactly("一", "马", "不吃香菜", "最近在线").inOrder()
        val title = Titles.pick(pieces, 1080f) { TextInk.onPageColor(it, px) }!!
        assertThat(Titles.clean(title).text).isEqualTo("不吃香菜")
        assertThat(title.box.left).isGreaterThan(180f)
        assertThat(title.box.right).isLessThan(330f)
    }

    /** PP-OCR 的框比字大一圈：收到字的墨迹上，压进框里的气泡（伸出了框）不算。 */
    @Test fun `an OCR box is tightened to its ink and leaves out a bubble poking in`() {
        val ink = 0xFF222222.toInt()
        val px = paint(
            200, 100, 0xFFFFFFFF.toInt(),
            Box(50f, 40f, 54f, 59f) to ink, Box(60f, 40f, 80f, 44f) to ink, Box(60f, 55f, 80f, 59f) to ink,
            Box(90f, 40f, 94f, 59f) to ink, Box(100f, 46f, 121f, 50f) to ink,
            Box(30f, 66f, 170f, 100f) to 0xFFE5E5EA.toInt(),                                      // 下一条消息的气泡
        )
        val line = ChatLine(Box(40f, 30f, 130f, 72f), "王小明")
        assertThat(TextInk.tighten(line, px).box).isEqualTo(Box(50f, 40f, 121f, 59f))
        // 框里没有字的不动
        val empty = ChatLine(Box(140f, 5f, 190f, 30f), "")
        assertThat(TextInk.tighten(empty, px)).isSameInstanceAs(empty)
    }

    /** 右边头像上的字接在自己那条消息后面：「收到了 何」只留「收到了」。 */
    @Test fun `text on a right-hand avatar is cut off the message before it`() {
        val avatar = Box(1000f, 490f, 1060f, 550f)
        val chars = listOf(Box(700f, 500f, 730f, 530f), Box(732f, 500f, 762f, 530f), Box(764f, 500f, 794f, 530f), null, Box(1012f, 495f, 1048f, 535f))
        val cut = Avatars.cutOff(ChatLine(Box(700f, 495f, 1048f, 535f), "收到了 何", chars), listOf(avatar), 1080f)!!
        assertThat(cut.text).isEqualTo("收到了")
        assertThat(cut.box).isEqualTo(Box(700f, 500f, 794f, 530f))
        val plain = Avatars.cutOff(ChatLine(Box(700f, 495f, 1048f, 535f), "收到了何"), listOf(avatar), 1080f)!!
        assertThat(plain.text).isEqualTo("收到了")
        assertThat(plain.box.right).isAtMost(avatar.left)
        assertThat(Avatars.cutOff(ChatLine(Box(1012f, 495f, 1048f, 535f), "何"), listOf(avatar), 1080f)).isNull()
    }

    /** 系统消息里的引号常读走样（「'」），前引号也会整个漏掉。 */
    @Test fun `a name in a system message is found whatever the quotes were read as`() {
        fun names(text: String) = Names.find(zonesOf(listOf(ChatLine(Box(170f, 500f, 370f, 520f), text))), emptyList())
        listOf("'番茄炒蛋' 撤回了一条消息", "‘番茄炒蛋’ 撤回了一条消息", "番茄炒蛋” 撤回了一条消息").forEach { text ->
            val name = names(text).single()
            assertWithMessage(text).that(name.text).isEqualTo("番茄炒蛋")
            assertWithMessage(text).that(name.box.width).isLessThan(120f)
        }
        assertThat(names("你撤回了一条消息")).isEmpty()
    }

    /** OCR 偶尔把一个名字检测成两段：同一行上紧挨着、一样大的那段接上。 */
    @Test fun `a name read in two pieces is joined back`() {
        val lines = listOf(
            ChatLine(Box(60f, 300f, 85f, 312f), "Ana"),
            ChatLine(Box(89f, 300f, 125f, 312f), "Souza"),
            ChatLine(Box(60f, 318f, 300f, 334f), "See you all at the station tonight"),
        )
        assertThat(Names.find(zonesOf(lines), emptyList())).containsExactly(ChatText(Box(60f, 300f, 125f, 312f), "Ana Souza"))
    }

    /** 横着的图（电脑版、平板横屏）不在这一版里认。 */
    @Test fun `landscape images are skipped`() {
        val px = Pixels(200, 100, IntArray(200 * 100))
        assertThat(ChatLayout.analyze(listOf(ChatLine(Box(10f, 10f, 50f, 20f), "发送")), px)).isEqualTo(ChatFindings.NONE)
    }

    private fun zonesOf(lines: List<ChatLine>): Zones = Zones.of(lines, 540f, 1170f)

    /** 一张纯色图，再一块块涂上颜色。 */
    private fun paint(w: Int, h: Int, background: Int, vararg blocks: Pair<Box, Int>): Pixels {
        val argb = IntArray(w * h) { background }
        blocks.forEach { (b, color) ->
            for (y in b.top.toInt() until b.bottom.toInt()) for (x in b.left.toInt() until b.right.toInt()) argb[y * w + x] = color
        }
        return Pixels(w, h, argb)
    }
}
