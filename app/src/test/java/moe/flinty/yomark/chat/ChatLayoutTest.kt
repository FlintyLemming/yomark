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

    /**
     * Telegram 群里的昵称和消息一样大，只是彩色的；引用回复里被引用的人名也是。
     * 名字下面先是引用里的彩色人名、再是白字的也认；彩色的网址、置顶消息条不认。
     */
    @Test fun `colored names as large as the message are found`() {
        val px = Pixels(540, 1170, IntArray(540 * 1170) { DARK })
        val lines = listOf(
            ChatLine(Box(70f, 200f, 300f, 220f), "置顶消息") to BLUE,
            ChatLine(Box(70f, 226f, 400f, 246f), "周六上午十点集合") to WHITE,
            ChatLine(Box(70f, 400f, 150f, 420f), "Jerry") to BLUE,
            ChatLine(Box(70f, 426f, 300f, 446f), "你想幹啥") to WHITE,
            ChatLine(Box(70f, 500f, 250f, 520f), "Angelic47") to PINK,
            ChatLine(Box(80f, 528f, 150f, 548f), "Jerry") to BLUE,
            ChatLine(Box(80f, 552f, 200f, 572f), "你想幹啥") to WHITE,
            ChatLine(Box(70f, 650f, 250f, 670f), "pan.example.com") to BLUE,
            ChatLine(Box(70f, 676f, 300f, 696f), "资料都在这里了") to WHITE,
        )
        lines.forEach { (l, c) -> px.strokes(l.box, c) }
        val found = Names.find(zonesOf(lines.map { it.first }), emptyList()) { TextInk.chroma(it, px) }
        assertThat(found.map { it.text }).containsExactly("Jerry", "Angelic47", "Jerry")
    }

    /** Telegram 的渐变壁纸斜着变色：离屏幕边一成屏宽，颜色就和边上差出一截。左右两边的底色也不一样。 */
    @Test fun `avatars on a wallpaper that shades across the screen are found`() {
        val px = canvas(540, 1170) { x, y -> rgb(170 - x / 4, 200 - x / 20 + y / 50, 120 + x / 5) }
        val truth = listOf(300, 420, 540, 700).map { Box(8f, it.toFloat(), 56f, it + 48f) }
        truth.forEachIndexed { i, b ->
            px.fill(Box(70f, b.top - 10f, 380f, b.bottom), 0xFFFFFFFF.toInt())
            px.disc(b, AVATAR_COLORS[i])
        }
        val found = Avatars.find(Grid.of(px), Zones.of(emptyList(), 540f, 1170f))
        assertThat(found.body).hasSize(truth.size)
        truth.forEach { t -> assertWithMessage("$t in ${found.body}").that(found.body.any { ChatFixtures.matches(it, t) }).isTrue() }
    }

    /**
     * 微信深色：头像右缘到屏宽 12.9%，自己的头像在右边；滑动着截的图右边上还有一道滚动条，盖住了右边的底色。
     */
    @Test fun `wide avatars and a scrollbar at the right edge`() {
        val px = canvas(540, 1170) { _, _ -> 0xFF111111.toInt() }
        val left = listOf(200, 400, 600).map { Box(16f, it.toFloat(), 70f, it + 54f) }
        val right = Box(470f, 800f, 524f, 854f)
        left.forEachIndexed { i, b ->
            px.fill(Box(84f, b.top, 300f, b.top + 50f), 0xFF2C2C2C.toInt())
            px.fill(b, AVATAR_COLORS[i])
        }
        px.fill(Box(250f, 805f, 456f, 850f), 0xFF2DC160.toInt())
        px.fill(right, AVATAR_COLORS[3])
        px.fill(Box(534f, 700f, 540f, 1000f), 0xFF686868.toInt())
        val found = Avatars.find(Grid.of(px), Zones.of(emptyList(), 540f, 1170f))
        (left + right).forEach { t -> assertWithMessage("$t in ${found.body}").that(found.body.any { ChatFixtures.matches(it, t) }).isTrue() }
        assertThat(found.mirrored).isTrue()
    }

    /**
     * 滚动条的滑块停在最新一条消息旁边：滑块的下端只盖住那一段的八成多，自己的头像就在这一段里。
     * 图宽 1220（不是 540 的倍数，网格每三个像素取一个）。
     */
    @Test fun `an own avatar beside the end of the scroll thumb is found`() {
        val px = canvas(1220, 2600) { _, _ -> 0xFF111111.toInt() }
        val left = listOf(400, 800, 1200).map { Box(36f, it.toFloat(), 156f, it + 120f) }
        val right = Box(1064f, 2070f, 1184f, 2190f)
        left.forEachIndexed { i, b ->
            px.fill(Box(190f, b.top, 700f, b.top + 110f), 0xFF2C2C2C.toInt())
            px.fill(b, AVATAR_COLORS[i])
        }
        px.fill(Box(700f, 2080f, 1040f, 2180f), 0xFF2DC160.toInt())
        px.fill(right, AVATAR_COLORS[3])
        px.fill(Box(1205f, 1500f, 1220f, 2170f), 0xFF686868.toInt())
        val found = Avatars.find(Grid.of(px), Zones.of(emptyList(), 1220f, 2600f))
        (left + right).forEach { t -> assertWithMessage("$t in ${found.body}").that(found.body.any { ChatFixtures.matches(it, t) }).isTrue() }
    }

    /** 壁纸上一笔粗的涂鸦搭在头像和气泡之间，连成一大块：拆开以后头像还认得出。 */
    @Test fun `an avatar bridged to its bubble by a thick doodle stroke is still found`() {
        val px = canvas(540, 1170) { _, _ -> 0xFFEDEDED.toInt() }
        val truth = listOf(300, 450).map { Box(8f, it.toFloat(), 56f, it + 48f) }
        truth.forEachIndexed { i, b ->
            px.fill(Box(70f, b.top - 10f, 400f, b.bottom), 0xFFFFFFFF.toInt())
            px.disc(b, AVATAR_COLORS[i])
        }
        px.fill(Box(50f, 326f, 72f, 331f), 0xFF8A8A8A.toInt())
        val found = Avatars.find(Grid.of(px), Zones.of(emptyList(), 540f, 1170f))
        truth.forEach { t -> assertWithMessage("$t in ${found.body}").that(found.body.any { ChatFixtures.matches(it, t) }).isTrue() }
    }

    /**
     * Telegram 转发的消息：「转发自」不是名字，它下面那行（或同一行后面）才是，频道名带数字也认。
     * 打字打出来的「转发自……」（不是彩色的）不算。
     */
    @Test fun `a forwarded message names its source, not the word forwarded`() {
        val px = Pixels(540, 1170, IntArray(540 * 1170) { DARK })
        val lines = listOf(
            ChatLine(Box(70f, 300f, 180f, 320f), "Maple Syrup") to PINK,
            ChatLine(Box(70f, 326f, 130f, 346f), "转发自") to BLUE,
            ChatLine(Box(70f, 352f, 330f, 372f), "量产型小熊0815号的频道存档") to BLUE,
            ChatLine(Box(70f, 380f, 300f, 400f), "千万别把门锁上") to WHITE,
            ChatLine(Box(70f, 500f, 330f, 520f), "Forwarded from Tech News 2077") to BLUE,
            ChatLine(Box(70f, 526f, 300f, 546f), "Big update tonight") to WHITE,
            ChatLine(Box(70f, 650f, 330f, 670f), "转发自家里的群 周六大扫除") to WHITE,
            ChatLine(Box(70f, 676f, 130f, 696f), "收到") to WHITE,
        )
        lines.forEach { (l, c) -> px.strokes(l.box, c) }
        val found = Names.find(zonesOf(lines.map { it.first }), emptyList()) { TextInk.chroma(it, px) }
        assertThat(found.map { it.text }).containsExactly("Maple Syrup", "量产型小熊0815号的频道存档", "Tech News 2077")
        assertThat(found.first { it.text == "Tech News 2077" }.box.left).isGreaterThan(200f)
    }

    /**
     * Telegram 的引用回复：发送人名字后面跟着头衔（OCR 读成一长行），引用里被引用的人名下面那句小字是他说的话，不是名字；
     * 带缩略图的引用人名往右挪，正好落在屏幕正中，也认。
     */
    @Test fun `in a Telegram reply the quoted words are not a name`() {
        val px = Pixels(540, 1170, IntArray(540 * 1170) { DARK })
        val lines = listOf(
            ChatLine(Box(70f, 300f, 330f, 320f), "Lost in the long night 夜猫子") to PURPLE,
            ChatLine(Box(80f, 328f, 200f, 346f), "Nova d' Lune") to BLUE,
            ChatLine(Box(80f, 350f, 230f, 364f), "可是它真的能用吗") to WHITE,
            ChatLine(Box(70f, 372f, 160f, 392f), "反正挺快！") to WHITE,
            ChatLine(Box(130f, 500f, 410f, 520f), "七月 (某某小区业主委员会主任)") to PURPLE,
            ChatLine(Box(131f, 526f, 170f, 546f), "图片") to GRAY,
            ChatLine(Box(70f, 556f, 110f, 576f), "还好") to WHITE,
        )
        lines.forEach { (l, c) -> px.strokes(l.box, c) }
        val found = Names.find(zonesOf(lines.map { it.first }), emptyList()) { TextInk.chroma(it, px) }
        assertThat(found.map { it.text }).containsExactly("Lost in the long night 夜猫子", "Nova d' Lune", "七月 (某某小区业主委员会主任)")
    }

    /** 消息中间的彩色链接（不是一段的开头）照常按名字的长度认，长的不算名字。 */
    @Test fun `a colored link inside a message is not a name`() {
        val px = Pixels(540, 1170, IntArray(540 * 1170) { DARK })
        val lines = listOf(
            ChatLine(Box(70f, 300f, 130f, 320f), "Maple") to PINK,
            ChatLine(Box(70f, 326f, 160f, 346f), "看看这个") to WHITE,
            ChatLine(Box(70f, 352f, 330f, 372f), "Download the release notes here") to BLUE,
            ChatLine(Box(70f, 378f, 200f, 398f), "明天之前看完") to WHITE,
        )
        lines.forEach { (l, c) -> px.strokes(l.box, c) }
        val found = Names.find(zonesOf(lines.map { it.first }), emptyList()) { TextInk.chroma(it, px) }
        assertThat(found.map { it.text }).containsExactly("Maple")
    }

    /** 微信消息下面的引用「方小禾：[图片]」只遮冒号前的名字；同样带冒号的消息、标签、时间、网址不算。 */
    @Test fun `the name in a WeChat quote is boxed alone`() {
        val lines = listOf(
            ChatLine(Box(85f, 300f, 240f, 319f), "周末一起去爬山吗"),
            ChatLine(Box(85f, 380f, 300f, 399f), "好久不见，你一点没变"),
            ChatLine(Box(80f, 420f, 140f, 435f), "方小禾："),
            ChatLine(Box(230f, 520f, 365f, 539f), "这张照片你还留着啊"),
            ChatLine(Box(260f, 560f, 380f, 575f), "方小禾：今晚加班吗"),
            ChatLine(Box(85f, 620f, 330f, 639f), "地址：长江路88号阳光新村"),
            ChatLine(Box(85f, 660f, 200f, 675f), "备注：明天带伞"),
            ChatLine(Box(85f, 700f, 150f, 715f), "今天 09:12"),
            ChatLine(Box(85f, 740f, 260f, 755f), "https://example.com/a"),
        )
        val found = Names.find(zonesOf(lines), emptyList())
        assertThat(found.map { it.text }).containsExactly("方小禾", "方小禾")
        found.forEach { n ->
            val line = lines.first { it.box.top == n.box.top }
            assertThat(n.box.width).isLessThan(line.box.width * 0.8f)
        }
    }

    /**
     * 微信群里发图片的人：名字下面是一张截图，OCR 读出截图上一行小字，不是消息。名字顶边和头像顶边对齐，照样认。
     * 名字和气泡左缘对齐、气泡里的字缩进屏宽 3.7% 的也认。
     */
    @Test fun `a WeChat name above a picture or an indented bubble is found`() {
        val typical = listOf(
            ChatLine(Box(85f, 600f, 180f, 619f), "好的没问题"),
            ChatLine(Box(85f, 700f, 140f, 719f), "我也去"),
            ChatLine(Box(85f, 800f, 240f, 819f), "周六来我家吃饭吧"),
        )
        val picture = listOf(ChatLine(Box(78f, 300f, 120f, 313f), "方小禾"), ChatLine(Box(90f, 330f, 160f, 334f), "相册截图(123/456)")) + typical
        assertThat(Names.find(zonesOf(picture), listOf(Box(14f, 300f, 62f, 348f))).map { it.text }).containsExactly("方小禾")
        val indented = listOf(ChatLine(Box(78f, 300f, 120f, 313f), "程一舟"), ChatLine(Box(98f, 330f, 300f, 349f), "好久不见你一点没变")) + typical
        assertThat(Names.find(zonesOf(indented), emptyList()).map { it.text }).containsExactly("程一舟")
    }

    /**
     * Telegram 自己发的消息没有头像，气泡贴到屏宽 97%，时间在气泡右下角：短消息和时间读成一行「嗯 15:56」，
     * 长消息的时间单占一行。贴着右边、又短、带时间，同会话列表每行右上角的时间，可它在气泡里，不算会话列表。
     * 同样的字直接写在页面底色上的照旧扣分。
     */
    @Test fun `times inside outgoing Telegram bubbles are not a chat list's time column`() {
        val chat = telegramGroup(outgoingBubbles = true)
        assertWithMessage("${chat.score} ${chat.reasons}").that(chat.isChat).isTrue()
        assertWithMessage("${chat.reasons}").that(chat.reasons.none { it.startsWith("行尾一列时间") }).isTrue()
        val bare = telegramGroup(outgoingBubbles = false)
        assertWithMessage("${bare.reasons}").that(bare.reasons.any { it.startsWith("行尾一列时间") }).isTrue()
    }

    /**
     * 同一页上：自己气泡里引用的人名是白字，和被引用的话一个颜色，按引用的缩进认；
     * 最后一条消息只露出彩色的名字，消息被输入栏挡住了，名字照样认。被引用的话、回复的话不是名字。
     */
    @Test fun `a quoted name in an own bubble and a name cut off by the input bar are found`() {
        val r = telegramGroup(outgoingBubbles = true)
        assertWithMessage("${r.names}").that(r.names.map { it.text }).containsExactly("Maple", "Akarin", "Maple", "Akarin", "Akarin", "Pudding")
        assertWithMessage("${r.names}").that(r.names.any { it.text == "Akarin" && it.box.left > 200f && it.box.top in 690f..720f }).isTrue()
    }

    /**
     * 深色 Telegram 群聊（照一张真截图的版面缩到 540 宽）：别人的消息五组，头像在每组旁边，最后一组只露出名字；
     * 自己的四条，三条的时间贴着右边，最后一条引用了别人的话。
     */
    private fun telegramGroup(outgoingBubbles: Boolean): ChatFindings {
        val px = canvas(540, 1170) { _, _ -> DARK }
        val lines = ArrayList<ChatLine>()
        fun text(b: Box, s: String, color: Int = WHITE) {
            px.glyphs(b, color)
            lines += ChatLine(b, s)
        }
        text(Box(170f, 90f, 290f, 110f), "周末羽毛球群")
        text(Box(170f, 118f, 290f, 134f), "三三 正在输入…", BLUE)
        fun incoming(top: Float, name: String, message: String, avatar: Int) {
            px.fill(Box(70f, top, 400f, top + 70f), INCOMING)
            px.disc(Box(8f, top + 14f, 64f, top + 70f), avatar)
            text(Box(94f, top + 8f, 94f + 14f * name.length, top + 24f), name, PINK)
            text(Box(94f, top + 32f, 94f + 16f * message.length, top + 52f), message)
        }
        fun outgoing(bubble: Box, vararg texts: Pair<Box, String>) {
            if (outgoingBubbles) px.fill(bubble, OUTGOING)
            texts.forEach { (b, s) -> text(b, s) }
        }
        incoming(250f, "Maple", "这个会让我以为是自带的", AVATAR_COLORS[0])
        outgoing(Box(405f, 340f, 526f, 374f), Box(421f, 348f, 516f, 366f) to "嗯 15:56")
        incoming(400f, "Akarin", "我还是执着于扁平一点", AVATAR_COLORS[1])
        outgoing(Box(390f, 490f, 526f, 524f), Box(400f, 498f, 516f, 516f) to "不带 15:57")
        outgoing(Box(215f, 532f, 526f, 566f), Box(230f, 540f, 516f, 558f) to "框架能实现但不是自带的 15:57")
        incoming(590f, "Maple", "看着挺基础的 15:57", AVATAR_COLORS[0])
        // 引用回复：引用块比气泡浅一点，左边一道白竖条；回复的话比引用里的字靠左
        outgoing(Box(196f, 680f, 526f, 784f))
        if (outgoingBubbles) {
            px.fill(Box(208f, 691f, 511f, 744f), QUOTE)
            px.fill(Box(208f, 691f, 212f, 744f), WHITE)
        }
        text(Box(223f, 696f, 316f, 715f), "Akarin")
        text(Box(224f, 718f, 499f, 740f), "我个人还是执着于扁平一点的操作")
        text(Box(211f, 751f, 375f, 775f), "平板模式是扁平的")
        text(Box(451f, 762f, 516f, 778f), "15:57")
        incoming(800f, "Akarin", "能不展开就不用 15:57", AVATAR_COLORS[1])
        // 最后一条只露出名字，下面的消息被输入栏挡住
        px.fill(Box(70f, 1024f, 400f, 1075f), INCOMING)
        text(Box(94f, 1032f, 192f, 1055f), "Pudding", PINK)
        px.fill(Box(10f, 1075f, 460f, 1150f), INCOMING)
        text(Box(80f, 1115f, 170f, 1135f), "输入消息", GRAY)
        return ChatLayout.analyze(lines, px)
    }

    /**
     * 微信群里只有别人说话的一屏：输入栏空着（微信不写占位字），没有靠右的消息，昵称旁边不带时间。
     * 剩下的是头像，和头像旁边顶对齐的一行小字昵称、下面一行大一号的消息。
     * 同样的版面换成名字大、下面一行小（通讯录的签名、会话列表的预览）的，不算。
     */
    @Test fun `a WeChat group with only other people's messages is a chat`() {
        val chat = wechatGroup(namesSmaller = true)
        assertWithMessage("${chat.score} ${chat.reasons}").that(chat.isChat).isTrue()
        assertThat(chat.names.map { it.text }).containsExactlyElementsIn(WECHAT_SENDERS)
        assertThat(chat.avatars.count { it.centerX < 100f }).isEqualTo(WECHAT_SENDERS.size)
        val list = wechatGroup(namesSmaller = false)
        assertWithMessage("${list.score} ${list.reasons}").that(list.isChat).isFalse()
        assertThat(list.reasons.none { it.startsWith("头像旁的昵称") }).isTrue()
    }

    private val WECHAT_SENDERS = listOf("程一舟", "方小禾", "方小禾", "程一舟", "方小禾", "孟华", "方小禾", "方小禾")

    /** 浅色微信群聊（照一张真截图的版面缩到 540 宽）：八条消息，每条一个头像、一行昵称；中间一个居中的时间。 */
    private fun wechatGroup(namesSmaller: Boolean): ChatFindings {
        val px = canvas(540, 1170) { _, _ -> WECHAT_PAGE }
        val lines = ArrayList<ChatLine>()
        fun text(b: Box, s: String, color: Int) {
            px.glyphs(b, color)
            lines += ChatLine(b, s)
        }
        text(Box(150f, 108f, 390f, 132f), "周末爬山小分队(6)", INK)
        val messages = listOf("我都忘了怎么认识的了", "这种还是算了", "补习班吧应该", "反正那时候觉得她很飒", "真假", "肯定的", "以前她跟个小黑皮一样", "我不知道哪里飒")
        val (nameH, messageH) = if (namesSmaller) 16f to 24f else 24f to 16f
        WECHAT_SENDERS.forEachIndexed { i, sender ->
            val top = 200f + 96f * i + (if (i >= 6) 40f else 0f)
            px.fill(Box(16f, top, 70f, top + 54f), AVATAR_COLORS[i % AVATAR_COLORS.size])
            text(Box(88f, top + 5f, 88f + 14f * sender.length, top + 5f + nameH), sender, GRAY)
            val m = messages[i]
            px.fill(Box(85f, top + 28f, 120f + 20f * m.length, top + 38f + messageH), 0xFFFFFFFF.toInt())
            text(Box(102f, top + 33f, 102f + 20f * m.length, top + 33f + messageH), m, INK)
        }
        text(Box(232f, 776f, 308f, 790f), "周二 20:00", GRAY)
        px.fill(Box(65f, 1110f, 420f, 1150f), 0xFFFFFFFF.toInt())
        return ChatLayout.analyze(lines, px)
    }

    private fun zonesOf(lines: List<ChatLine>): Zones = Zones.of(lines, 540f, 1170f)

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun canvas(w: Int, h: Int, color: (Int, Int) -> Int) = Pixels(w, h, IntArray(w * h) { color(it % w, it / w) })

    /** 一张纯色图，再一块块涂上颜色。 */
    private fun paint(w: Int, h: Int, background: Int, vararg blocks: Pair<Box, Int>): Pixels =
        canvas(w, h) { _, _ -> background }.also { px -> blocks.forEach { (b, color) -> px.fill(b, color) } }

    private fun Pixels.fill(b: Box, color: Int) {
        for (y in b.top.toInt() until b.bottom.toInt()) for (x in b.left.toInt() until b.right.toInt()) argb[y * width + x] = color
    }

    /** [b] 里内切的实心圆。 */
    private fun Pixels.disc(b: Box, color: Int) {
        val r = b.width / 2f
        for (y in b.top.toInt() until b.bottom.toInt()) for (x in b.left.toInt() until b.right.toInt()) {
            val dx = x + 0.5f - b.centerX
            val dy = y + 0.5f - b.centerY
            if (dx * dx + dy * dy <= r * r) argb[y * width + x] = color
        }
    }

    /** 字的笔画：框里隔行的横条，框边上留着底色。 */
    private fun Pixels.strokes(b: Box, color: Int) {
        for (y in b.top.toInt() + 2 until b.bottom.toInt() - 2 step 2) for (x in b.left.toInt() + 2 until b.right.toInt() - 2) argb[y * width + x] = color
    }

    /**
     * 稀疏一点的字：框里隔几个像素一竖，框边上留着底色。[strokes] 的横条把收紧以后的框边占满了，
     * 框边上最常见的颜色成了字的颜色，看不出字底下是什么。
     */
    private fun Pixels.glyphs(b: Box, color: Int) {
        for (y in b.top.toInt() + 2 until b.bottom.toInt() - 2) for (x in b.left.toInt() + 2 until b.right.toInt() - 2) {
            if ((x - b.left.toInt()) % 6 < 2) argb[y * width + x] = color
        }
    }

    private val AVATAR_COLORS = listOf(0xFFE5533D, 0xFF3D7BE5, 0xFF8E44AD, 0xFFF0A030).map { it.toInt() }
    private val DARK = 0xFF1E2A36.toInt()
    private val PINK = 0xFFE87A9A.toInt()
    private val BLUE = 0xFF5FB0F0.toInt()
    private val PURPLE = 0xFFA77BE0.toInt()
    private val WHITE = 0xFFF0F0F0.toInt()
    private val GRAY = 0xFF8E8E93.toInt()
    private val INCOMING = 0xFF2C3E50.toInt()
    private val OUTGOING = 0xFF7B5CD6.toInt()
    private val QUOTE = 0xFF8F72DA.toInt()
    private val WECHAT_PAGE = 0xFFEDEDED.toInt()
    private val INK = 0xFF1A1A1A.toInt()
}
