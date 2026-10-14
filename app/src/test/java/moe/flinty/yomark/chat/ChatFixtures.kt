package moe.flinty.yomark.chat

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.InflaterInputStream

/**
 * 合成的聊天截图（tools/model-eval/make_chat_samples.py --fixtures 生成，全是编的）：
 * 每张图一个同名 .tsv，记着图上写过的每一行字（当作 OCR 的结果）和该打码的头像、昵称、标题。
 *
 * 图自己解（[Png]），不用 Bitmap 也不用 javax.imageio：单测的类路径是 android.jar，没有 imageio；
 * 版面分析本身不碰安卓，这样不起 Robolectric 也能跑。
 */
data class ChatFixture(
    val name: String,
    val isChat: Boolean,
    val lines: List<ChatLine>,
    val avatars: List<Box>,
    val names: List<ChatText>,
    val title: ChatText?,
    val image: File,
) {
    val app: String get() = name.split('-')[2]

    /** 每次现解，不留在内存里：三十几张图常驻要上百 MB，和同一个 JVM 里跑的其他单测抢堆。 */
    val pixels: Pixels get() = Png.read(image)
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

    /** 原尺寸的测试图（chat-native/，生成脚本 --fixtures 另存的）：给真 OCR 按原图读。 */
    fun native(name: String): ChatFixture {
        val url = ChatFixtures::class.java.classLoader!!.getResource("chat-native/$name.tsv")
            ?: error("缺少测试图 chat-native/$name：先跑 tools/model-eval/make_chat_samples.py --fixtures app/src/test/resources/chat")
        return load(File(url.toURI()))
    }

    private fun load(tsv: File): ChatFixture {
        val stem = tsv.nameWithoutExtension
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
        return ChatFixture(stem, chat, lines, avatars, names, title, File(tsv.parentFile, "$stem.png"))
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

/** 只认生成脚本写出的那一种 PNG：8 位、不隔行，RGB 或 RGBA。 */
internal object Png {
    fun read(file: File): Pixels {
        val input = DataInputStream(file.inputStream().buffered())
        input.skipBytes(8)
        var width = 0
        var height = 0
        var channels = 0
        val idat = ByteArrayOutputStream()
        input.use {
            while (true) {
                val length = it.readInt()
                val type = String(ByteArray(4).also { t -> it.readFully(t) }, Charsets.US_ASCII)
                val data = ByteArray(length).also { d -> it.readFully(d) }
                it.skipBytes(4)
                when (type) {
                    "IHDR" -> {
                        width = int(data, 0)
                        height = int(data, 4)
                        val depth = data[8].toInt()
                        channels = when (data[9].toInt()) { 2 -> 3; 6 -> 4; else -> 0 }
                        require(depth == 8 && channels != 0 && data[12].toInt() == 0) { "${file.name}: 只认 8 位不隔行的 RGB/RGBA" }
                    }
                    "IDAT" -> idat.write(data)
                    "IEND" -> break
                }
            }
        }
        val raw = InflaterInputStream(idat.toByteArray().inputStream()).readBytes()
        val stride = width * channels
        val argb = IntArray(width * height)
        var prev = ByteArray(stride)
        for (y in 0 until height) {
            val filter = raw[y * (stride + 1)].toInt()
            val row = raw.copyOfRange(y * (stride + 1) + 1, (y + 1) * (stride + 1))
            for (i in 0 until stride) {
                val a = if (i >= channels) row[i - channels].u() else 0
                val b = prev[i].u()
                val c = if (i >= channels) prev[i - channels].u() else 0
                val add = when (filter) {
                    0 -> 0
                    1 -> a
                    2 -> b
                    3 -> (a + b) / 2
                    4 -> paeth(a, b, c)
                    else -> error("${file.name}: 不认识的行过滤 $filter")
                }
                row[i] = (row[i].u() + add).toByte()
            }
            for (x in 0 until width) {
                val o = x * channels
                val alpha = if (channels == 4) row[o + 3].u() else 255
                argb[y * width + x] = (alpha shl 24) or (row[o].u() shl 16) or (row[o + 1].u() shl 8) or row[o + 2].u()
            }
            prev = row
        }
        return Pixels(width, height, argb)
    }

    private fun Byte.u() = toInt() and 0xff

    private fun int(b: ByteArray, at: Int) = (b[at].u() shl 24) or (b[at + 1].u() shl 16) or (b[at + 2].u() shl 8) or b[at + 3].u()

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
