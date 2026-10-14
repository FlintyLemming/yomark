package moe.flinty.yomark.chat

import java.io.File
import javax.imageio.ImageIO

/**
 * 合成的聊天截图（tools/model-eval/make_chat_samples.py --fixtures 生成，全是编的）：
 * 每张图一个同名 .tsv，记着图上写过的每一行字（当作 OCR 的结果）和该打码的头像、昵称、标题。
 *
 * 用 javax.imageio 读图、不用 Bitmap：版面分析本身不碰安卓，这样在桌面 JVM 上也能跑。
 */
data class ChatFixture(
    val name: String,
    val isChat: Boolean,
    val lines: List<ChatLine>,
    val avatars: List<Box>,
    val names: List<ChatText>,
    val title: ChatText?,
    val pixels: Pixels,
) {
    val app: String get() = name.split('-')[2]
}

object ChatFixtures {

    private val dir: File by lazy {
        val url = ChatFixtures::class.java.classLoader!!.getResource("chat/neg-001-contacts.tsv")
            ?: error("缺少测试图 chat/：先跑 tools/model-eval/make_chat_samples.py --fixtures app/src/test/resources/chat")
        File(url.toURI()).parentFile
    }

    val all: List<ChatFixture> by lazy {
        dir.listFiles { f -> f.name.endsWith(".tsv") }!!.sortedBy { it.name }.map { load(it) }
    }

    fun named(prefix: String): ChatFixture = all.first { it.name.startsWith(prefix) }

    private fun load(tsv: File): ChatFixture {
        val stem = tsv.nameWithoutExtension
        val image = ImageIO.read(File(tsv.parentFile, "$stem.png").takeIf { it.exists() } ?: File(tsv.parentFile, "$stem.jpg"))
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        var chat = false
        val lines = ArrayList<ChatLine>()
        val avatars = ArrayList<Box>()
        val names = ArrayList<ChatText>()
        var title: ChatText? = null
        tsv.readLines().filter { it.isNotBlank() }.forEach { row ->
            val f = row.split('\t')
            if (f[0] == "chat") {
                chat = f[1] == "1"
                return@forEach
            }
            val box = Box(f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat())
            val text = f.getOrElse(5) { "" }
            when (f[0]) {
                "line" -> lines += ChatLine(box, text)
                "avatar" -> avatars += box
                "name" -> names += ChatText(box, text)
                "title" -> title = ChatText(box, text)
            }
        }
        return ChatFixture(stem, chat, lines, avatars, names, title, Pixels(image.width, image.height, argb))
    }

    fun iou(a: Box, b: Box): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val i = w * h
        return i / (a.width * a.height + b.width * b.height - i)
    }

    /** 一个找到的框算对上真值：IoU 过 [minIou]，或者真值的大半落在它里面（名字框按字数比例切，难免宽一点窄一点）。 */
    fun matches(found: Box, truth: Box, minIou: Float = 0.5f): Boolean {
        if (iou(found, truth) >= minIou) return true
        val w = minOf(found.right, truth.right) - maxOf(found.left, truth.left)
        val h = minOf(found.bottom, truth.bottom) - maxOf(found.top, truth.top)
        return w > 0f && h > 0f && w * h >= 0.7f * truth.width * truth.height && found.width * found.height <= 2f * truth.width * truth.height
    }

    data class Tally(var truth: Int = 0, var hit: Int = 0, var extra: Int = 0) {
        val recall get() = if (truth == 0) 1f else hit.toFloat() / truth
    }

    /** 一批图上找到的框和真值逐个配对，数出认出、漏掉和多出来的。 */
    fun tally(found: List<Box>, truth: List<Box>, into: Tally) {
        val used = BooleanArray(truth.size)
        var hit = 0
        found.forEach { f ->
            val i = truth.indices.firstOrNull { !used[it] && matches(f, truth[it]) }
            if (i != null) { used[i] = true; hit++ }
        }
        into.truth += truth.size
        into.hit += hit
        into.extra += found.size - hit
    }

    /** 打一张表：每种皮肤一行，头像、昵称、标题各自认出多少、多出多少。本地调规则时看。 */
    fun report(): String {
        val sb = StringBuilder()
        val rows = LinkedHashMap<String, Triple<Tally, Tally, Tally>>()
        var wrongVerdict = 0
        all.forEach { fx ->
            val r = ChatLayout.analyze(fx.lines, fx.pixels)
            if (r.isChat != fx.isChat) {
                wrongVerdict++
                sb.append("判错 ${fx.name}: isChat=${r.isChat} score=${r.score} ${r.reasons}\n")
            }
            val key = if (fx.isChat) fx.app else "非聊天页"
            val (a, n, t) = rows.getOrPut(key) { Triple(Tally(), Tally(), Tally()) }
            tally(r.avatars, fx.avatars, a)
            tally(r.names.map { it.box }, fx.names.map { it.box }, n)
            tally(listOfNotNull(r.title?.box), listOfNotNull(fx.title?.box), t)
        }
        sb.append(String.format("%-10s %14s %14s %14s\n", "", "头像 认出/共/多", "昵称 认出/共/多", "标题 认出/共/多"))
        val total = Triple(Tally(), Tally(), Tally())
        rows.forEach { (k, v) ->
            fun f(t: Tally) = "${t.hit}/${t.truth}/${t.extra}"
            sb.append(String.format("%-10s %14s %14s %14s\n", k, f(v.first), f(v.second), f(v.third)))
            listOf(v.first to total.first, v.second to total.second, v.third to total.third).forEach { (s, d) ->
                d.truth += s.truth; d.hit += s.hit; d.extra += s.extra
            }
        }
        fun f(t: Tally) = "${t.hit}/${t.truth}/${t.extra}"
        sb.append(String.format("%-10s %14s %14s %14s\n", "合计", f(total.first), f(total.second), f(total.third)))
        sb.append("聊天页判错 $wrongVerdict 张\n")
        return sb.toString()
    }
}
