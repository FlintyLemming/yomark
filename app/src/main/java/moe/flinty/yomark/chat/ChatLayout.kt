package moe.flinty.yomark.chat

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/*
 * 聊天截图的版面分析：这一页是不是聊天记录；是的话，发言者的头像、昵称和顶栏的聊天标题各在哪。
 *
 * 不认应用，认版面。各家聊天软件只有两种版式（调研见 docs/chat-layout-research.md）：
 * - 左右气泡式（微信、QQ、钉钉、飞书、LINE、Kakao、WhatsApp、Telegram、iMessage、Messenger……）：
 *   别人的消息靠左、自己的靠右，头像贴着屏幕边排成一列；
 * - 单列列表式（Discord、Slack）：消息都靠左，每组第一条左边一个头像，右边一行「名字 + 时间」。
 * 两种都有顶栏标题和底部输入栏。
 *
 * 这个文件只用 Kotlin 标准库：坐标是浮点框，像素是 ARGB 数组。安卓那一层（位图、Quad、Candidate）在 ChatDetector 里，
 * 这样版面规则能在桌面 JVM 上直接用合成测试页跑。
 *
 * 所有长度都按图宽 W 量（手机竖屏截图的宽就是一屏宽），不按高：长截图的高可以是宽的五六倍。
 */

/** 图上的一个轴对齐框，像素坐标。 */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
}

/**
 * OCR 读出的一行：外接框和原文。
 * [chars] 有的话与 text 逐字对齐（空格这种没有框的位置是 null），切掉压在头像上的字时用；没有就按字数比例切。
 */
data class ChatLine(val box: Box, val text: String, val chars: List<Box?>? = null)

/** 一张图的像素：[argb] 按行存，长度 width * height。 */
class Pixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(argb.size == width * height) { "argb.size ${argb.size} != $width x $height" }
    }

    fun at(x: Int, y: Int): Int = argb[y * width + x]
}

/** 认出的一个昵称或标题：框和原文（框只框住名字那几个字，系统消息里的「"张三" 撤回了一条消息」只框张三）。 */
data class ChatText(val box: Box, val text: String)

data class ChatFindings(
    val isChat: Boolean,
    /** 判断是不是聊天页的得分，和得分的来由。调试记录用。 */
    val score: Int,
    val reasons: List<String>,
    val avatars: List<Box>,
    val names: List<ChatText>,
    val title: ChatText?,
) {
    companion object {
        val NONE = ChatFindings(false, 0, emptyList(), emptyList(), emptyList(), null)
    }
}

object ChatLayout {

    /** 得分到这个数才算聊天页。各项得分见 [score]。 */
    const val CHAT_THRESHOLD = 5

    fun analyze(lines: List<ChatLine>, pixels: Pixels): ChatFindings {
        val w = pixels.width.toFloat()
        val h = pixels.height.toFloat()
        if (w <= 0f || h < w) return ChatFindings.NONE   // 横着的图不是手机聊天截图（电脑版、平板横屏不在这一版里）

        val split = Titles.split(lines, w)
        val tight = split.map { TextInk.tighten(it, pixels) }
        val onPage = { b: Box -> TextInk.onPageColor(b, pixels) }
        val zones = Zones.of(tight, w, h, onPage)
        val grid = Grid.of(pixels)
        val avatars = Avatars.find(grid, zones)
        val (score, reasons) = score(zones, avatars)
        if (score < CHAT_THRESHOLD) return ChatFindings(false, score, reasons, emptyList(), emptyList(), null)

        val header = Avatars.header(grid, zones)
        // 头像上的字按 OCR 原来的框切（收紧以后的框已经不压着头像了），切剩的再收
        val cut = split.indices.mapNotNull { i ->
            Avatars.cutOff(split[i], avatars.body, w)?.let { if (it === split[i]) tight[i] else TextInk.tighten(it, pixels) }
        }
        val names = Names.find(Zones.of(cut, w, h, onPage), avatars.body) { TextInk.chroma(it, pixels) }
        return ChatFindings(
            isChat = true,
            score = score,
            reasons = reasons,
            avatars = avatars.body + listOfNotNull(header),
            names = names,
            title = zones.title?.let { Titles.clean(it) },
        )
    }

    /**
     * 是不是聊天页：几样版面特征各记几分。只占一两样的（通讯录、会话列表、设置页也有一列头像）不算。
     */
    internal fun score(z: Zones, avatars: Avatars.Found): Pair<Int, List<String>> {
        var s = 0
        val why = ArrayList<String>()
        fun add(points: Int, reason: String) {
            s += points
            why += "$reason ${if (points >= 0) "+" else ""}$points"
        }
        if (z.bottom.any { INPUT_HINT.containsMatchIn(it.text) }) add(3, "输入栏")
        if (TabBar.matches(z.bottom, z.w)) add(-4, "底部导航栏")
        val stamps = z.body.count { z.isCentered(it) && TIME.containsMatchIn(it.text) }
        if (stamps > 0) add(min(stamps, 2), "居中的时间")
        if (z.body.any { z.isCentered(it) && SYSTEM.containsMatchIn(it.text) }) add(1, "系统消息")
        val left = z.leftColumn()
        val right = z.rightColumn()
        if (left >= 1 && right >= 1 && left + right >= 3) add(2, "左右两列消息")
        if (z.body.count { z.isRightEdgeTime(it) } >= 3) add(-3, "行尾一列时间（会话列表）")
        if (Names.headerRows(z).size >= 2) add(2, "名字加时间的消息头")
        when {
            avatars.mirrored -> add(3, "左右两列头像")
            avatars.body.size >= 2 -> add(2, "一列头像")
        }
        return s to why
    }

    /** 输入栏的占位字和按钮。「Aa」是 Messenger、LINE 的。 */
    private val INPUT_HINT = Regex(
        "发送|发消息|按住\\s*说话|说点什么|输入消息|写消息|^Aa$|^Message|iMessage|Type a message|Write a message|Text message|^Send$|^Chat$",
        RegexOption.IGNORE_CASE,
    )

    /** 聊天里的时间：「昨天 21:46」「10月12日 晚上8:30」「Yesterday 9:46 PM」「Sat 2:02 PM」「18:47」。 */
    internal val TIME = Regex("\\d{1,2}[:：]\\d{2}|昨天|前天|星期[一二三四五六日天]|周[一二三四五六日]|Yesterday|Today", RegexOption.IGNORE_CASE)

    /** 群里的系统消息。 */
    internal val SYSTEM = Regex("撤回了一条消息|加入了群聊|拍了拍|邀请.+加入|移出了群聊|退出了群聊|\\bjoined\\b|\\bleft\\b|recalled|unsent a message", RegexOption.IGNORE_CASE)
}

/**
 * 一页的几个区：状态栏、顶栏、正文、底栏。按 OCR 行的位置分；像素只经 onPage 问一句字底下是不是页面的底色。
 */
internal class Zones private constructor(
    val w: Float,
    val h: Float,
    val title: ChatLine?,
    /** 顶栏下沿：正文从这里开始。 */
    val headerBottom: Float,
    /** 底栏上沿：输入栏或导航栏从这里开始。 */
    val bottomTop: Float,
    val header: List<ChatLine>,
    val body: List<ChatLine>,
    val bottom: List<ChatLine>,
    private val onPage: (Box) -> Boolean,
) {
    fun isCentered(l: ChatLine) = abs(l.box.centerX - w / 2f) < 0.06f * w && l.box.width < 0.7f * w

    /** 靠左一列：左缘对齐（误差 3% 屏宽）的行数。只数不居中、右缘没顶到右边的行。 */
    fun leftColumn(): Int {
        val cand = body.filter { !isCentered(it) && it.box.left < 0.35f * w && it.box.right < 0.9f * w }
        return largestCluster(cand.map { it.box.left }, 0.03f * w)
    }

    /**
     * 靠右一列：右缘对齐的、左缘在屏宽四分之一以右的行。自己的消息靠右排，右缘是同一条竖线，左缘随消息长短参差。
     * 左缘也对齐的是右半边的一栏（商品列表的第二列、表单的值），不算。
     */
    fun rightColumn(): Int {
        val cand = body.filter { !isCentered(it) && it.box.left > 0.25f * w && it.box.right > 0.6f * w }
        val tol = 0.015f * w
        val best = cand.maxByOrNull { c -> cand.count { abs(it.box.right - c.box.right) <= tol } } ?: return 0
        val col = cand.filter { abs(it.box.right - best.box.right) <= tol }
        if (col.size >= 2 && col.maxOf { it.box.left } - col.minOf { it.box.left } < 0.05f * w) return 0
        return col.size
    }

    /**
     * 贴着右边的一个时间：会话列表每一行右上角的「10:24」「昨天」，直接写在页面底色上。
     * 聊天页上贴着右边的时间在气泡里：Telegram、WhatsApp 自己发的消息，气泡右下角的「15:56 ✓✓」，短消息和时间读成一行「嗯 15:56」。
     */
    fun isRightEdgeTime(l: ChatLine) =
        l.box.right > 0.9f * w && l.box.width < 0.25f * w && ChatLayout.TIME.containsMatchIn(l.text) && l.text.length <= 12 && onPage(l.box)

    companion object {
        /** 状态栏下沿：手机状态栏大约是屏宽的 8%–11%。 */
        const val STATUS = 0.085f
        /** 顶栏最深到这里（iMessage 的顶栏有头像加名字两层，大约屏宽的 28%）。 */
        const val HEADER_MAX = 0.30f
        /** 底栏：输入栏、导航栏大约屏宽的 15%。 */
        const val BOTTOM = 0.17f

        /**
         * @param onPage 这个框是不是直接写在页面的底色上：不是印在头像上的字（见 [Titles.pick]），
         * 不是气泡里的字（见 [isRightEdgeTime]）。
         */
        fun of(lines: List<ChatLine>, w: Float, h: Float, onPage: (Box) -> Boolean = { true }): Zones {
            val status = STATUS * w
            val visible = lines.filter { it.text.isNotBlank() && it.box.centerY > status }
            val title = Titles.pick(visible, w, onPage)
            val headerBottom = Titles.headerBottom(title, visible, w)
            val bottomTop = h - BOTTOM * w
            return Zones(
                w = w,
                h = h,
                title = title,
                headerBottom = headerBottom,
                bottomTop = bottomTop,
                header = visible.filter { it.box.centerY <= headerBottom },
                body = visible.filter { it.box.centerY > headerBottom && it.box.centerY < bottomTop },
                bottom = visible.filter { it.box.centerY >= bottomTop },
                onPage = onPage,
            )
        }

        /** 一组数里，彼此相差不超过 [tol] 的最大一群有几个。 */
        fun largestCluster(values: List<Float>, tol: Float): Int {
            if (values.isEmpty()) return 0
            val sorted = values.sorted()
            var best = 1
            var i = 0
            for (j in sorted.indices) {
                while (sorted[j] - sorted[i] > tol) i++
                best = max(best, j - i + 1)
            }
            return best
        }
    }
}

/** 顶栏标题。 */
internal object Titles {

    /** 标题下面那行状态，不是标题：「最近在线」「23 位成员」「last seen recently」「对方正在输入…」。 */
    private val STATUS_LINE = Regex(
        "在线|位成员|成员|正在输入|last seen|online|members|typing|Active now|Active \\d|tap here|点击",
        RegexOption.IGNORE_CASE,
    )

    /** 群聊人数「(23)」「（23）」，iMessage 名字后面的「›」（原图上常读成「·」「>」）。 */
    private val SUFFIX = Regex("\\s*([(（]\\d+[)）]|[›>〉》·•]+)\\s*$")

    /** 和标题读成一段的返回箭头「<」、Discord 频道名前的「#」。 */
    private val PREFIX = Regex("^[<‹«〈《←#＃·•|]+\\s*")

    /** 贴着屏幕边这么近（屏宽的比例）的不是标题：返回箭头（原图上常被读成「<」「一」）、未读数、菜单键。 */
    private const val EDGE = 0.12f

    /**
     * 顶栏标题：状态栏以下第一行字（屏宽 [Zones.HEADER_MAX] 以上，去掉状态行）。按字号挑不可靠：
     * 有的应用标题和消息一样大。iMessage 标题上面是对方头像，第一行仍是标题。
     * 太长的不是标题（标题一般一行放得下，宽不过屏宽七成）。
     * 也不是标题的：贴着屏幕边的、只有符号的、印在头像上的字（[onHeader] 说它底下不是顶栏的底色）：
     * iMessage 的默认头像是一个圆里一个姓，在标题上面，比标题还靠上。
     */
    fun pick(lines: List<ChatLine>, w: Float, onHeader: (Box) -> Boolean = { true }): ChatLine? = lines
        .filter { it.box.centerY < Zones.HEADER_MAX * w && it.box.width < 0.7f * w && !STATUS_LINE.containsMatchIn(it.text) }
        .filter { it.text.trim().length in 1..40 && !ChatLayout.TIME.matches(it.text.trim()) }
        .filter { it.text.any { c -> c.isLetterOrDigit() } && it.box.right > EDGE * w && it.box.left < (1 - EDGE) * w }
        .sortedBy { it.box.top }
        .firstOrNull { onHeader(it.box) }

    /**
     * 顶栏一带（屏宽 [Zones.HEADER_MAX] 以上）的行按逐字框拆开：返回箭头、头像上的字、标题、标题下面那行状态挨得近，
     * 原图上会被 OCR 连成一行（「一 马 不吃香菜 最近在线」「B Marcus Lee」「梁 Family 182 位成员」），
     * 整行当标题不对，整行因为带着「在线」「位成员」被当成状态行扔掉也不对。
     * 先按空格切成词，同一高度上挨着的词再连回一段。没有逐字框的行原样留着。
     */
    fun split(lines: List<ChatLine>, w: Float): List<ChatLine> =
        lines.flatMap { if (it.box.centerY < Zones.HEADER_MAX * w) pieces(it) else listOf(it) }

    private fun pieces(line: ChatLine): List<ChatLine> {
        val chars = line.chars?.takeIf { it.size == line.text.length } ?: return listOf(line)
        val words = ArrayList<IntRange>()
        var start = -1
        for (i in 0..chars.size) {
            val solid = i < chars.size && chars[i] != null && !line.text[i].isWhitespace()
            if (solid && start < 0) start = i
            if (!solid && start >= 0) {
                words += start until i
                start = -1
            }
        }
        fun boxOf(r: IntRange): Box {
            val bs = r.mapNotNull { chars[it] }
            return Box(bs.minOf { it.left }, bs.minOf { it.top }, bs.maxOf { it.right }, bs.maxOf { it.bottom })
        }
        val groups = ArrayList<MutableList<IntRange>>()
        words.forEach { r ->
            val last = groups.lastOrNull()
            if (last != null && sameRow(boxOf(last.last()), boxOf(r))) last += r else groups += mutableListOf(r)
        }
        if (groups.size < 2) return listOf(line)
        return groups.map { g ->
            val range = g.first().first..g.last().last
            // 逐字框是按识别的时间步切的，字可能压出一点：左右各放宽一点（段和段之间至少隔着半个字高）
            val b = boxOf(range)
            val box = Box(b.left - 0.15f * b.height, b.top, b.right + 0.15f * b.height, b.bottom)
            ChatLine(box, line.text.substring(range.first, range.last + 1), chars.subList(range.first, range.last + 1))
        }
    }

    /** 一行里接着写的两个词：中线差不到矮的那个字高的四分之一，中间空当不到半个字高。 */
    private fun sameRow(a: Box, b: Box): Boolean {
        val h = min(a.height, b.height)
        return abs(a.centerY - b.centerY) < 0.25f * h && b.left - a.right < 0.5f * h
    }

    /**
     * 正文从哪儿开始：标题下面紧跟的状态行也算顶栏。没认出标题的，按常见的顶栏高度（屏宽 22%）。
     */
    fun headerBottom(title: ChatLine?, lines: List<ChatLine>, w: Float): Float {
        if (title == null) return 0.22f * w
        var bottom = title.box.bottom
        lines.filter { it !== title && it.box.top >= title.box.top && it.box.top < bottom + title.box.height && it.box.centerY < Zones.HEADER_MAX * w }
            .filter { STATUS_LINE.containsMatchIn(it.text) || it.box.height < 0.8f * title.box.height }
            .forEach { bottom = max(bottom, it.box.bottom) }
        return bottom + 0.01f * w
    }

    /** 标题去掉前面的箭头、「#」和后面的人数、箭头，框缩到名字那一截。 */
    fun clean(line: ChatLine): ChatText {
        val text = line.text
        var start = text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        var end = text.indexOfLast { !it.isWhitespace() } + 1
        PREFIX.find(text.substring(start, end))?.let { start += it.value.length }
        SUFFIX.find(text.substring(start, end))?.let { end = start + it.range.first }
        if (end <= start) return ChatText(line.box, text.trim())
        return ChatText(TextSpan.boxOf(line, start, end - start), text.substring(start, end))
    }
}

/**
 * 一行里一段字的框。有逐字框的按逐字框取，左右不出整行的框，上下就是整行的上下沿；
 * 没有的按字宽比例（汉字算两份，其余算一份）切出来。[text] 是 line.text 本身或其中一段（比如 trim 过的）。
 */
internal object TextSpan {
    private fun units(s: String) = s.sumOf { if (it.code >= 0x2E80) 2 else 1 }.toFloat()

    fun boxOf(line: ChatLine, start: Int, length: Int, text: String = line.text): Box {
        val total = units(text)
        if (total <= 0f || length >= text.length) return line.box
        val chars = line.chars
        val offset = line.text.indexOf(text)
        if (chars != null && chars.size == line.text.length && offset >= 0) {
            val from = (offset + start).coerceIn(0, chars.size)
            val picked = chars.subList(from, (from + length).coerceIn(from, chars.size)).filterNotNull()
            val b = line.box
            val left = max(b.left, picked.minOfOrNull { it.left } ?: b.right)
            val right = min(b.right, picked.maxOfOrNull { it.right } ?: b.left)
            if (right > left) return Box(left, b.top, right, b.bottom)
        }
        val before = units(text.substring(0, start.coerceIn(0, text.length)))
        val span = units(text.substring(start.coerceIn(0, text.length), (start + length).coerceIn(0, text.length)))
        val b = line.box
        val left = b.left + b.width * before / total
        return Box(left, b.top, min(b.right, left + b.width * span / total), b.bottom)
    }
}

/**
 * OCR 的框收到字上，和框底下的颜色。
 *
 * PP-OCR 的检测框比字大一圈，而且按 unclip 往外扩的多少随行长变：短行扩得多（两个字的昵称上下各多出小半个字高），
 * 长消息只多一两个像素。昵称比消息小一号要靠字高比出来，拿这种框比，字高比 0.7 的成了 0.9，和上下两行消息分不开。
 * 所以先把框收到字的墨迹上：框边上最常见的颜色是底色，和它差得多的像素是字。
 * 本来就贴着字的框（ML Kit 的、测试页上记的）收了也基本不变。
 */
internal object TextInk {
    /** 和底色差这么多（三个通道差的和）才算字：浅灰的时间戳和底色也差一百五以上，JPEG 的噪点二三十。 */
    private const val INK = 60

    /** 底色算一样：三个通道差的和不过这些（量化到 8 级一档，相邻两档也算）。 */
    private const val SAME = 60

    /** 往框外多看这么多像素：连到这一圈上的墨迹伸出了框，不是这一行的字。 */
    private const val MARGIN = 3

    fun tighten(line: ChatLine, p: Pixels): ChatLine {
        val b = tight(line.box, p) ?: return line
        return if (b == line.box) line else line.copy(box = b)
    }

    /**
     * 框里字的外接框。伸出框外的墨迹不算：框下沿压到的气泡、框边上的头像、挤进框里的上下两行，都和框外连着；
     * 这一行的字整个在框里。收不出来的（见 [SHRINK]）返回 null。
     */
    fun tight(box: Box, p: Pixels): Box? {
        val r = Region.of(box, p) ?: return null
        val bg = r.border(p)
        val x0 = max(0, r.x0 - MARGIN)
        val y0 = max(0, r.y0 - MARGIN)
        val x1 = min(p.width, r.x1 + MARGIN)
        val y1 = min(p.height, r.y1 + MARGIN)
        val w = x1 - x0
        val h = y1 - y0
        val ink = BooleanArray(w * h) { i -> diff(p.at(x0 + i % w, y0 + i / w), bg) > INK }
        // 从外圈灌进来：和外圈连着的墨迹（八邻接）都不是这一行的字
        val outside = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        fun seed(i: Int) {
            if (ink[i] && !outside[i]) { outside[i] = true; stack[sp++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val cx = i % w
            val cy = i / w
            for (dy in -1..1) for (dx in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until w && ny in 0 until h) seed(ny * w + nx)
            }
        }
        // 字边上 JPEG 的振铃也和底色差过 INK（深底浅字的截图上一圈两三个像素）：外接框只认够深的像素，按这行字本身的反差定
        var strongest = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            val i = (y - y0) * w + (x - x0)
            if (ink[i] && !outside[i]) strongest = max(strongest, diff(p.at(x, y), bg))
        }
        val deep = max(INK, (DEEP * strongest).toInt())
        var top = -1
        var bottom = -1
        var left = Int.MAX_VALUE
        var right = -1
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            val i = (y - y0) * w + (x - x0)
            if (!ink[i] || outside[i] || diff(p.at(x, y), bg) <= deep) continue
            if (top < 0) top = y
            bottom = y
            if (x < left) left = x
            if (x > right) right = x
        }
        if (top < 0 || bottom + 1 - top < SHRINK * (r.y1 - r.y0) || right + 1 - left < SHRINK * (r.x1 - r.x0)) return null
        return Box(max(box.left, left.toFloat()), max(box.top, top.toFloat()), min(box.right, right + 1f), min(box.bottom, bottom + 1f))
    }

    /**
     * 框里字的颜色有多「彩」：和底色差到最大反差一半以上的像素（字的笔画心，避开抗锯齿那一圈），RGB 最大减最小的平均。
     * 白字、黑字、灰字接近 0，Telegram、WhatsApp 群里的彩色昵称一百上下。
     */
    fun chroma(box: Box, p: Pixels): Int {
        val r = Region.of(box, p) ?: return 0
        val bg = r.border(p)
        var strongest = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) strongest = max(strongest, diff(p.at(x, y), bg))
        if (strongest <= INK) return 0
        var sum = 0L
        var n = 0
        for (y in r.y0 until r.y1) for (x in r.x0 until r.x1) {
            val c = p.at(x, y)
            if (diff(c, bg) * 2 < strongest) continue
            val red = c shr 16 and 0xFF
            val green = c shr 8 and 0xFF
            val blue = c and 0xFF
            sum += max(red, max(green, blue)) - min(red, min(green, blue))
            n++
        }
        return if (n == 0) 0 else (sum / n).toInt()
    }

    /** 外接框只算和底色的差到这行字最大反差这么多成的像素（振铃一般不到两成，字的笔画都在五成以上）。 */
    private const val DEEP = 0.35f

    /**
     * 收出来不到原框这么多成高、宽的不收：一横、一点这种字，或者框里找不到字。
     * 不定得太高：和头像上的字连成一个框的昵称，切掉那个字以后框还是头像那么高，字只占两成多。
     */
    private const val SHRINK = 0.15f

    /**
     * 框底下的颜色和同一高度上屏幕两边的颜色一样：字直接写在页面（顶栏）上，不是印在头像、按钮这种色块上。
     * 屏幕最左、最右各 1.5% 屏宽的两条上是页面本身的颜色（头像、箭头离边总留着一道缝）。
     */
    fun onPageColor(box: Box, p: Pixels): Boolean {
        val r = Region.of(box, p) ?: return true
        val edge = max(2, (0.015f * p.width).toInt())
        val counts = HashMap<Int, Int>()
        for (y in r.y0 until r.y1) for (x in (0 until edge) + (p.width - edge until p.width)) {
            val key = quantize(p.at(x, y))
            counts[key] = (counts[key] ?: 0) + 1
        }
        val page = counts.maxByOrNull { it.value }?.key ?: return true
        return diff(r.border(p), color(page)) <= SAME
    }

    /** 框落在图里的整数像素范围 [x0, x1) × [y0, y1)。太小的（不到 4 像素）不算。 */
    private class Region(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        /** 框边上最常见的颜色。 */
        fun border(p: Pixels): Int {
            val counts = HashMap<Int, Int>()
            fun add(x: Int, y: Int) {
                val key = quantize(p.at(x, y))
                counts[key] = (counts[key] ?: 0) + 1
            }
            for (x in x0 until x1) { add(x, y0); add(x, y1 - 1) }
            for (y in y0 + 1 until y1 - 1) { add(x0, y); add(x1 - 1, y) }
            return color(counts.maxByOrNull { it.value }!!.key)
        }

        companion object {
            fun of(box: Box, p: Pixels): Region? {
                val x0 = floor(box.left).toInt().coerceIn(0, p.width - 1)
                val x1 = ceil(box.right).toInt().coerceIn(x0 + 1, p.width)
                val y0 = floor(box.top).toInt().coerceIn(0, p.height - 1)
                val y1 = ceil(box.bottom).toInt().coerceIn(y0 + 1, p.height)
                return if (x1 - x0 < 4 || y1 - y0 < 4) null else Region(x0, y0, x1, y1)
            }
        }
    }

}

/** 每通道量化到 8 级一档，拼成一个 15 位的键。 */
private fun quantize(c: Int) = (c shr 19 and 31 shl 10) or (c shr 11 and 31 shl 5) or (c shr 3 and 31)

/** 量化的键 → 那一档中间的颜色。 */
private fun color(k: Int) = ((k shr 10 and 31) * 8 + 4 shl 16) or ((k shr 5 and 31) * 8 + 4 shl 8) or ((k and 31) * 8 + 4)

/** 两个颜色三个通道差的和。 */
private fun diff(a: Int, b: Int) =
    abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

/** 底部导航栏：「微信 通讯录 发现 我」「Chats Calls Settings」。会话列表、通讯录页有，聊天页没有。 */
internal object TabBar {
    private val TABS = setOf(
        "微信", "通讯录", "发现", "我", "消息", "联系人", "动态", "频道", "首页", "我的", "工作台", "日历", "文档",
        "chats", "calls", "contacts", "settings", "updates", "communities", "status", "people", "stories", "home",
    )

    fun matches(bottom: List<ChatLine>, w: Float): Boolean {
        val tabs = bottom.filter { it.text.trim().lowercase() in TABS }
        return tabs.size >= 3 && Zones.largestCluster(tabs.map { it.box.centerX }, 0.05f * w) == 1
    }
}

/** 粗化的像素网格：宽不超过 [WORK] 个格。截图缩到这个尺度上找头像就够了，也快。 */
internal class Grid private constructor(val w: Int, val h: Int, val step: Int, private val rgb: IntArray) {
    fun rgbAt(x: Int, y: Int) = rgb[y * w + x]

    /** 网格坐标 → 原图坐标。 */
    fun toImage(x0: Int, y0: Int, x1: Int, y1: Int) = Box(x0 * step.toFloat(), y0 * step.toFloat(), x1 * step.toFloat(), y1 * step.toFloat())

    fun fromImage(v: Float) = (v / step).toInt()

    companion object {
        const val WORK = 540

        fun of(p: Pixels): Grid {
            val step = max(1, (p.width + WORK - 1) / WORK)
            val w = p.width / step
            val h = p.height / step
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[y * w + x] = p.at(x * step + step / 2, y * step + step / 2)
            return Grid(w, h, step, out)
        }
    }
}

/**
 * 头像：贴着屏幕边、大小像头像、近正方形或圆的色块。
 *
 * 1. 左右两条「沟」（各占屏宽 20%）里，按 40 格高一段取这一边屏幕最边上的颜色当底色（壁纸变化慢，也成立；
 *    左右分开取：Telegram 的渐变壁纸斜着变色，屏幕左右两边差得比头像和底色还多）；
 * 2. 和底色差得多的格里，平缓过渡过来的渐变壁纸去掉（[unmarkDrift]），再开运算（去掉壁纸涂鸦那种细线）、闭运算、填洞，连成块，
 *    留下近正方形、填充率高、宽在屏宽 4.5%–13% 的块；被粗涂鸦和气泡连成一大块的，拆开再看（[SPLIT]）；
 * 3. 块要贴边（离屏幕边不超过屏宽 6%，另一边不过屏宽 15%：微信的头像到 12.9%），
 *    而且要有另一块大小相近、离边一样远（左右镜像也算）的作伴，单独一块的多半是图片消息、表情；
 * 4. 头像列找到以后，在列里再找一遍形状不规整的：和底色几乎一样的灰白默认头像只剩中间一个人像剪影，
 *    按列里头像的大小补成一个框。
 */
internal object Avatars {

    data class Found(val body: List<Box>, val mirrored: Boolean)

    /**
     * 头像上印着字（默认头像上的姓、首字母）时，OCR 会把它和旁边的昵称读成一行：「卢 曹雨」；
     * 原图上右边头像上的字也会接在自己那条消息后面：「地址：长江路88号… 何」。
     * 把压在头像上的那几个字切掉；整行都在头像上的去掉（返回 null）；没压着头像的原样返回。
     */
    fun cutOff(line: ChatLine, avatars: List<Box>, w: Float): ChatLine? {
        val b = line.box
        val a = avatars.firstOrNull { a ->
            b.left < a.right && b.right > a.left &&
                min(b.bottom, a.bottom) - max(b.top, a.top) > 0.5f * min(b.height, a.height)
        } ?: return line
        val chars = line.chars?.takeIf { it.size == line.text.length }
        // 每个字的横向中点：有逐字框按框，没有按字数比例
        val centers: List<Float?> = chars?.map { it?.centerX } ?: run {
            val units = line.text.map { if (it.code >= 0x2E80) 2f else 1f }
            val total = units.sum()
            var acc = 0f
            units.map { u -> (b.left + b.width * (acc + u / 2f) / total).also { acc += u } }
        }
        val onLeft = a.centerX < b.centerX
        var first: Int
        var last: Int                                                    // 留下 [first, last)
        if (onLeft) {
            if (b.right <= a.right + 0.02f * w) return null
            first = centers.indices.firstOrNull { centers[it]?.let { c -> c >= a.right } ?: false } ?: return null
            last = line.text.length
        } else {
            if (b.left >= a.left - 0.02f * w) return null
            first = 0
            last = (centers.indices.lastOrNull { centers[it]?.let { c -> c <= a.left } ?: false } ?: return null) + 1
        }
        while (first < last && line.text[first].isWhitespace()) first++
        while (last > first && line.text[last - 1].isWhitespace()) last--
        if (first >= last) return null
        val span = TextSpan.boxOf(line, first, last - first)
        val left = if (onLeft) max(chars?.get(first)?.left ?: span.left, a.right) else b.left
        val right = if (onLeft) b.right else min(chars?.get(last - 1)?.right ?: span.right, a.left)
        // 头像上的字通常比昵称大，整行的框是按它撑高的；有逐字框就按剩下的字收紧上下
        val kept = chars?.subList(first, last)?.filterNotNull().orEmpty()
        val top = kept.minOfOrNull { it.top } ?: b.top
        val bottom = kept.maxOfOrNull { it.bottom } ?: b.bottom
        return ChatLine(Box(left, top, right, bottom), line.text.substring(first, last), chars?.subList(first, last))
    }

    private const val GUTTER = 0.2f
    /** 头像离屏幕边远的那一侧最多到这里。贴边的气泡、图片消息被沟截断，截出来的块一直伸到沟的内侧边界，比这远。 */
    private const val REACH = 0.15f
    private const val EDGE = 0.06f
    private const val MIN_SIZE = 0.045f
    private const val MAX_SIZE = 0.13f
    private const val BAND = 40
    private const val INK = 30

    fun find(g: Grid, z: Zones): Found {
        val top = g.fromImage(z.headerBottom).coerceIn(0, g.h)
        val bottom = g.fromImage(z.bottomTop).coerceIn(top, g.h)
        val gut = (GUTTER * g.w).toInt()
        val edge = EDGE * g.w
        val reach = REACH * g.w
        val left = components(g, 0, gut, top, bottom, split = true).filter { it.avatarShaped(g) && it.x0 < edge && it.x1 <= reach }
        val right = components(g, g.w - gut, g.w, top, bottom, split = true).filter { it.avatarShaped(g) && it.x1 > g.w - edge && it.x0 >= g.w - reach }
        val all = left + right
        val tol = 0.02f * g.w
        fun anchor(b: Blob) = if (b.x0 < edge) b.x0.toFloat() else (g.w - b.x1).toFloat()
        fun same(a: Blob, o: Blob) = a !== o && abs(a.width - o.width) <= 0.12f * max(a.width, o.width) && abs(anchor(a) - anchor(o)) <= tol
        val kept = all.filter { a -> all.any { same(a, it) } }.toMutableList()
        kept += faint(g, kept.filter { it.x0 < edge }, top, bottom) + faint(g, kept.filter { it.x1 > g.w - edge }, top, bottom)
        val mirrored = kept.any { it.x0 < edge } && kept.any { it.x1 > g.w - edge }
        return Found(kept.sortedBy { it.y0 }.map { g.toImage(it.x0, it.y0, it.x1, it.y1) }, mirrored)
    }

    /**
     * 一列头像里漏掉的淡头像：在这一列的横向范围里，找没被已有头像盖住的、有一定大小的块（剪影、浅色字），
     * 底边对齐它补一个和列里头像一样大的框。列至少要有两个头像才补，免得凭一个孤例乱补。
     */
    private fun faint(g: Grid, column: List<Blob>, top: Int, bottom: Int): List<Blob> {
        if (column.size < 2) return emptyList()
        val size = column.map { it.width }.sorted()[column.size / 2]
        val x0 = column.map { it.x0 }.sorted()[column.size / 2]
        val x1 = x0 + size
        return components(g, max(0, x0 - 1), min(g.w, x1 + 1), top, bottom)
            .filter { c -> c.width >= 0.3f * size && c.height >= 0.3f * size && c.width <= size + 2 && c.height <= 1.3f * size }
            .filter { c -> c.y0 > top + 1 && c.y1 < bottom - 1 }             // 碰到上下边界的是顶栏、输入栏的边，不是头像
            .filter { c -> column.none { it.y0 < c.y1 && c.y0 < it.y1 } }
            .map { c -> Blob(x0, max(top, c.y1 - size), x1, c.y1, size * size) }
            .fold(ArrayList()) { acc, b -> if (acc.none { it.y0 < b.y1 && b.y0 < it.y1 }) acc += b; acc }
    }

    /**
     * 顶栏里的头像（WhatsApp、Telegram、Messenger、iMessage……）：状态栏以下、正文以上，不贴边的一个圆或方块，
     * 挨着标题（同一高度，或者就在标题上面）。
     */
    fun header(g: Grid, z: Zones): Box? {
        val title = z.title ?: return null
        val top = g.fromImage(Zones.STATUS * z.w).coerceIn(0, g.h)
        val bottom = g.fromImage(z.headerBottom).coerceIn(top, g.h)
        val edge = EDGE * g.w
        return components(g, 0, g.w, top, bottom)
            .filter { it.avatarShaped(g) && it.x0 > edge && it.x1 < g.w - edge }
            .map { g.toImage(it.x0, it.y0, it.x1, it.y1) }
            .filter { b ->
                val beside = b.top < title.box.bottom && b.bottom > title.box.top
                val above = b.bottom <= title.box.top + 0.02f * z.w && abs(b.centerX - title.box.centerX) < 0.1f * z.w
                (beside || above) && !(b.left < title.box.right && b.right > title.box.left && beside)
            }
            .minByOrNull { abs(it.centerY - title.box.centerY) }
    }

    private class Blob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val count: Int) {
        val width get() = x1 - x0
        val height get() = y1 - y0

        fun overlaps(o: Blob) = x0 < o.x1 && o.x0 < x1 && y0 < o.y1 && o.y0 < y1

        /** 大小像头像（屏宽 4.5%–13%）、近正方形、填充率高（圆是 0.79）。 */
        fun avatarShaped(g: Grid): Boolean {
            if (width < MIN_SIZE * g.w || width > MAX_SIZE * g.w) return false
            val ratio = width.toFloat() / height
            return ratio in 0.8f..1.25f && count.toFloat() / (width * height) >= 0.6f
        }
    }

    /**
     * 区域里和底色不同的连通块（开运算去细线、闭运算补缝、填洞之后）。
     * [split] 时再加上大块里拆出来的像头像的芯（见 [SPLIT]）。
     *
     * 先去掉渐变壁纸（[unmarkDrift]）找一遍；真去掉了什么的话，不去再找一遍，只补像头像、又没和前一遍的头像重叠的块：
     * 头像自己也可能是渐变的，一头和底色差不多（Messenger 顶栏那种橙色渐变到近白的），去渐变时从那一头被吃掉半边。
     */
    private fun components(g: Grid, x0: Int, x1: Int, y0: Int, y1: Int, split: Boolean = false): List<Blob> {
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw <= 2 || bh <= 2) return emptyList()
        val dist = IntArray(bw * bh)
        val raw = ink(g, x0, x1, y0, y1, dist)
        val drifted = raw.copyOf()
        val cleared = unmarkDrift(g, x0, y0, bw, bh, drifted, dist)
        val found = blobs(g, x0, y0, bw, bh, clean(drifted, bw, bh), split)
        if (!cleared) return found
        val shaped = found.filter { it.avatarShaped(g) }
        return found + blobs(g, x0, y0, bw, bh, clean(raw, bw, bh), split).filter { p -> p.avatarShaped(g) && shaped.none { it.overlaps(p) } }
    }

    /** 区域里和这一段底色差过 [INK] 的格（按行存），和每格跟底色的差（填进 [dist]）。 */
    private fun ink(g: Grid, x0: Int, x1: Int, y0: Int, y1: Int, dist: IntArray): BooleanArray {
        val bw = x1 - x0
        val bh = y1 - y0
        val mask = BooleanArray(bw * bh)
        var band = 0
        while (band < bh) {
            val end = min(bh, band + BAND)
            val bg = edgeColor(g, x0, x1, y0 + band, y0 + end)
            for (y in band until end) for (x in 0 until bw) {
                val d = diff(g.rgbAt(x0 + x, y0 + y), bg)
                dist[y * bw + x] = d
                mask[y * bw + x] = d > INK
            }
            band = end
        }
        return mask
    }

    /** 开运算去掉一两格宽的细线，闭运算补上头像边上的缺口，再填洞。 */
    private fun clean(m: BooleanArray, bw: Int, bh: Int): BooleanArray {
        val opened = dilate(erode(m, bw, bh), bw, bh)
        val closed = erode(erode(dilate(dilate(opened, bw, bh), bw, bh), bw, bh), bw, bh)
        fillHoles(closed, bw, bh)
        return closed
    }

    /** [mask]（区域左上角在网格的 [x0], [y0]）里的块；[split] 时加上拆出来的芯。 */
    private fun blobs(g: Grid, x0: Int, y0: Int, bw: Int, bh: Int, mask: BooleanArray, split: Boolean): List<Blob> {
        val whole = label(mask, bw, bh, IntArray(bw * bh), x0, y0)
        if (!split) return whole
        val shaped = whole.filter { it.avatarShaped(g) }
        var core = mask
        repeat(SPLIT) { core = erode(core, bw, bh) }
        val ids = IntArray(bw * bh)
        val depth = IntArray(bw * bh) { -1 }
        val queue = IntArray(bw * bh)
        // 芯每边比长回去的块小 SPLIT 格：芯就大小不对的不用长了
        val cores = label(core, bw, bh, ids, x0, y0).withIndex()
            .filter { (_, c) -> c.width + 2 * SPLIT >= MIN_SIZE * g.w && c.width <= MAX_SIZE * g.w && c.height <= MAX_SIZE * g.w }
            .map { (k, c) -> regrow(k + 1, c, ids, mask, bw, bh, x0, y0, depth, queue) }
            .filter { c -> c.avatarShaped(g) && shaped.none { it.overlaps(c) } }
        return whole + cores
    }

    /**
     * 拆大块。Telegram 的涂鸦线条粗，开运算去不掉，搭在头像和旁边的气泡之间，连成一个不像头像的大块：
     * 整片往里腐蚀这么多圈，宽不过两倍这么多格的搭桥断开，剩下的芯各自在原来的块里长回去，再看像不像头像。
     */
    private const val SPLIT = 3

    /** 连通块（四邻接），按找到的先后编号 1、2、3……写进 [ids]（不在块里的是 0）。框是网格坐标（区域左上角在 [x0], [y0]）。 */
    private fun label(mask: BooleanArray, bw: Int, bh: Int, ids: IntArray, x0: Int, y0: Int): List<Blob> {
        val out = ArrayList<Blob>()
        val stack = IntArray(bw * bh)
        for (start in mask.indices) {
            if (!mask[start] || ids[start] != 0) continue
            val id = out.size + 1
            var sp = 0
            stack[sp++] = start
            ids[start] = id
            var minX = bw; var minY = bh; var maxX = -1; var maxY = -1; var count = 0
            while (sp > 0) {
                val i = stack[--sp]
                val x = i % bw
                val y = i / bw
                count++
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                if (x > 0 && mask[i - 1] && ids[i - 1] == 0) { ids[i - 1] = id; stack[sp++] = i - 1 }
                if (x < bw - 1 && mask[i + 1] && ids[i + 1] == 0) { ids[i + 1] = id; stack[sp++] = i + 1 }
                if (y > 0 && mask[i - bw] && ids[i - bw] == 0) { ids[i - bw] = id; stack[sp++] = i - bw }
                if (y < bh - 1 && mask[i + bw] && ids[i + bw] == 0) { ids[i + bw] = id; stack[sp++] = i + bw }
            }
            out += Blob(x0 + minX, y0 + minY, x0 + maxX + 1, y0 + maxY + 1, count)
        }
        return out
    }

    /**
     * 编号 [id] 的芯（框是 [core]）在 [mask] 里往外长回 [SPLIT] 圈（八邻接），长成的块。
     * [depth]（全是 -1）和 [queue] 是借用的，用完 depth 还原。
     */
    private fun regrow(
        id: Int, core: Blob, ids: IntArray, mask: BooleanArray, bw: Int, bh: Int, x0: Int, y0: Int, depth: IntArray, queue: IntArray,
    ): Blob {
        var head = 0
        var tail = 0
        for (y in core.y0 - y0 until core.y1 - y0) for (x in core.x0 - x0 until core.x1 - x0) {
            val i = y * bw + x
            if (ids[i] == id) { depth[i] = 0; queue[tail++] = i }
        }
        var minX = bw; var minY = bh; var maxX = -1; var maxY = -1
        while (head < tail) {
            val i = queue[head++]
            val x = i % bw
            val y = i / bw
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (depth[i] == SPLIT) continue
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx !in 0 until bw || ny !in 0 until bh) continue
                val n = ny * bw + nx
                if (mask[n] && depth[n] < 0) { depth[n] = depth[i] + 1; queue[tail++] = n }
            }
        }
        for (k in 0 until tail) depth[queue[k]] = -1
        return Blob(x0 + minX, y0 + minY, x0 + maxX + 1, y0 + maxY + 1, tail)
    }

    /**
     * 相邻两格颜色差（三个通道差的和）不过这么多算平缓过渡：渐变壁纸一格只变零点几，加上压缩的噪点也就几个数；
     * 头像、气泡、涂鸦的边一下子差几十。
     */
    private const val STEP = 15

    /** 渐变壁纸和边上取的底色最多差这么多：再远的是东西，不当壁纸。 */
    private const val DRIFT = 90

    /**
     * 渐变的壁纸离屏幕边远了，颜色和边上取的底色就差过 [INK]（Telegram 的壁纸斜着渐变，隔屏宽一成就差出这么多），
     * 一片片算成「字」，连成和头像粘在一起的大块。从这一片外框上的底色出发，一格格平缓过渡（相邻差不过 [STEP]）
     * 走得到的都是壁纸，从 [mask] 里去掉。头像、气泡的边都是一下子变色的，走不进去；本身渐变、一头和底色差不多的头像拦不住，见 [components]。
     * 涂鸦的细线（开运算会去掉的）跨过去：线圈起来的那片壁纸也渐变了，跨不过去就留在里面，填洞以后成了一大块。
     * 跨过线以后拿线外的颜色比。去掉了格就返回 true。
     */
    private fun unmarkDrift(g: Grid, x0: Int, y0: Int, bw: Int, bh: Int, mask: BooleanArray, dist: IntArray): Boolean {
        val opened = dilate(erode(mask, bw, bh), bw, bh)
        val reached = BooleanArray(bw * bh)
        val ref = IntArray(bw * bh)                                      // 走到这一格时拿来比的颜色：细线上是线外的颜色
        val stack = IntArray(bw * bh)
        var sp = 0
        var cleared = false
        fun rgb(i: Int) = g.rgbAt(x0 + i % bw, y0 + i / bw)
        fun seed(i: Int) {
            if (!mask[i] && !reached[i]) { reached[i] = true; ref[i] = rgb(i); stack[sp++] = i }
        }
        fun visit(n: Int, c: Int) {
            if (reached[n]) return
            if (mask[n] && !opened[n]) {
                reached[n] = true
                ref[n] = c
                stack[sp++] = n
                return
            }
            if (dist[n] > DRIFT) return
            val o = rgb(n)
            if (diff(c, o) > STEP) return
            reached[n] = true
            ref[n] = o
            if (mask[n]) { mask[n] = false; cleared = true }
            stack[sp++] = n
        }
        for (x in 0 until bw) { seed(x); seed((bh - 1) * bw + x) }
        for (y in 0 until bh) { seed(y * bw); seed(y * bw + bw - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % bw
            val c = ref[i]
            if (x > 0) visit(i - 1, c)
            if (x < bw - 1) visit(i + 1, c)
            if (i >= bw) visit(i - bw, c)
            if (i + bw < bw * bh) visit(i + bw, c)
        }
        return cleared
    }

    /**
     * 一段的底色：取屏幕最边上一条窄边（1.5% 屏宽）里最常见的颜色（每通道量化到 8 级一档）。
     * 不在整条沟里取：纯色头像占了沟的一大半，取出来的「底色」成了头像的颜色。
     * 头像离屏幕边总留着一道缝（Telegram 的最窄，屏宽 1.3%，至多擦着窄边），窄边上大半是底色或壁纸。
     * 只取 [x0, x1) 这一片所在的那一边：斜着的渐变壁纸左右两边颜色不一样；横跨两边的（顶栏）两边都取。
     */
    private fun edgeColor(g: Grid, x0: Int, x1: Int, y0: Int, y1: Int): Int {
        val edge = max(2, (0.015f * g.w).toInt())
        val xs: Iterable<Int> = when {
            x1 <= g.w / 2 -> 0 until edge
            x0 >= g.w / 2 -> rightStrip(g, y0, y1, edge)
            else -> (0 until edge) + rightStrip(g, y0, y1, edge)
        }
        return mode(g, xs, y0, y1)?.let { color(it.key) } ?: 0
    }

    /** 最边上这么宽的一道看是不是滚动条（微信的滚动条约屏宽 1%）。 */
    private const val BAR = 0.012f

    /**
     * 右边的窄边。滑动着截的图右边上有一道滚动条（微信的是屏宽 1% 宽的灰条），把窄边占了大半，取出来的「底色」是滚动条的颜色：
     * 最边上 [BAR] 这一道里最常见的颜色和往里挨着的一条不一样、往里那条大半是同一种颜色时，取往里那一条。
     * 不要求最边上那道整个是滚动条：滚动条可能比这道窄一格，滑块的一头也只盖住这一段的一部分。
     * 左边不挪：没有滚动条，Telegram 的头像离左边只有屏宽 1.3%。
     */
    private fun rightStrip(g: Grid, y0: Int, y1: Int, edge: Int): IntRange {
        val bar = max(1, (BAR * g.w).toInt())
        val outer = mode(g, g.w - bar until g.w, y0, y1)
        val inner = mode(g, g.w - bar - edge until g.w - bar, y0, y1)
        val scrollbar = outer != null && inner != null && outer.key != inner.key && inner.share >= 0.6f
        return if (scrollbar) g.w - bar - edge until g.w - bar else g.w - edge until g.w
    }

    private class Mode(val key: Int, val share: Float)

    /** 这几列里最常见的颜色（[quantize] 过的）和它占的比例。 */
    private fun mode(g: Grid, xs: Iterable<Int>, y0: Int, y1: Int): Mode? {
        val counts = HashMap<Int, Int>()
        for (y in y0 until y1) for (x in xs) {
            val key = quantize(g.rgbAt(x, y))
            counts[key] = (counts[key] ?: 0) + 1
        }
        val top = counts.maxByOrNull { it.value } ?: return null
        return Mode(top.key, top.value.toFloat() / counts.values.sum())
    }

    private fun dilate(m: BooleanArray, w: Int, h: Int): BooleanArray = morph(m, w, h, true)
    private fun erode(m: BooleanArray, w: Int, h: Int): BooleanArray = morph(m, w, h, false)

    /** 3x3 的膨胀 / 腐蚀。腐蚀时出界的格算「是」，免得贴着沟边的头像被啃掉一圈。 */
    private fun morph(m: BooleanArray, w: Int, h: Int, grow: Boolean): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = !grow
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                val inside = nx in 0 until w && ny in 0 until h
                val s = if (inside) m[ny * w + nx] else !grow
                if (grow && s) { v = true; break@loop }
                if (!grow && !s) { v = false; break@loop }
            }
            out[y * w + x] = v
        }
        return out
    }

    /** 从边上灌水，灌不到的空格是洞，填上（头像里和底色相同的部分）。 */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int) {
        val outside = BooleanArray(m.size)
        val stack = IntArray(m.size)
        var sp = 0
        fun seed(i: Int) {
            if (!m[i] && !outside[i]) { outside[i] = true; stack[sp++] = i }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        for (i in m.indices) if (!m[i] && !outside[i]) m[i] = true
    }
}

/**
 * 发言者的昵称。几处：
 * 1. 消息上方一行小字（微信、QQ、钉钉、飞书、LINE、iMessage、Messenger 的群聊；WhatsApp、Telegram 写在气泡里第一行，
 *    在 OCR 行上看是一样的）：下面紧跟一行字号更大、左缘对齐的消息；
 * 2. 「名字 + 时间」的消息头（Discord、Slack）；
 * 3. 系统消息里的名字：「"张三" 撤回了一条消息」「Rob joined」；
 * 4. 被引用、被转发的人：微信消息下面的引用「方小禾：[图片]」，Telegram 的「转发自」。
 */
internal object Names {

    private const val MAX_LEN = 24

    /** 字号比消息小这么多才算昵称（真实界面上昵称大约是消息字号的七八成）。 */
    private const val SMALLER = 0.88f
    private const val SMALLER_THAN_TYPICAL = 0.95f

    /** 句子的标点；冒号结尾的是「地址：」这种标签。 */
    private val SENTENCE = Regex("[，。！？!?；;…]|[：:]$")

    /** 再加上三位以上的数字（金额、单号、电话）。 */
    private val NOT_NAME = Regex("${SENTENCE.pattern}|\\d{3,}")

    /**
     * 放宽时名字可以这么长：彩色名字后面常跟着灰色的头衔、助力数，OCR 读成一行（「名字 + 头衔」三十来个字）；
     * 转发来源的频道名也常比人名长。
     */
    private const val LONG = 40

    /**
     * 这一页消息的字高：取上四分位。群聊里昵称、时间这些小字可能和消息差不多一样多，中位数会落到它们中间；
     * 消息的字总是这一页最大的那一档（顶栏标题不在正文里）。
     */
    private fun typicalHeight(lines: List<ChatLine>): Float =
        lines.map { it.box.height }.sorted().let { if (it.isEmpty()) 0f else it[it.size * 3 / 4] }

    /**
     * 像个名字：不长（不过 [maxLen]）、有字母或汉字（「+1」「38"」这种短消息、语音时长不是）、不是句子、不是时间、不是「转发自」；
     * [digits] 时可以带三位以上的数字（用户名、频道名常带）。
     */
    private fun plausible(text: String, maxLen: Int = MAX_LEN, digits: Boolean = false) =
        text.length <= maxLen && text.any { it.isLetter() } && !(if (digits) SENTENCE else NOT_NAME).containsMatchIn(text) &&
            !ChatLayout.TIME.containsMatchIn(text) && !FORWARDED.containsMatchIn(text)

    fun find(z: Zones, avatars: List<Box>, chroma: (Box) -> Int = { 0 }): List<ChatText> {
        val out = ArrayList<ChatText>()
        val colored = colored(z, chroma)
        // 彩色名字底下紧跟的一行小字是这个人说的话（引用回复里被引用的那句），不是下一个昵称
        fun saidBy(n: ChatText) = colored.any { c -> directlyAbove(c.box, n.box, z.w) }
        out += aboveMessages(z, avatars).filterNot { saidBy(it) }
        // 同一列的小字只按上面认出的那些推：彩色昵称和消息一样大，拿它们推会把引用里的小字也算进来
        val small = sameColumn(z, out).filterNot { saidBy(it) }
        out += colored.filter { c -> out.none { it.box == c.box } }
        out += small.filter { c -> out.none { it.box == c.box } }
        out.replaceAll { joinRight(z, it) }
        out += headerRows(z).filter { r -> out.none { it.box == r.box } }
        out += forwarded(z, chroma).filter { r -> out.none { it.box == r.box } }
        out += quoted(z).filterNot { saidBy(it) }
        out += inSystemMessages(z)
        return out
    }

    /** [upper] 是 [lower] 紧挨着的上一行：左缘对齐，中间空当不到大半个字高。 */
    private fun directlyAbove(upper: Box, lower: Box, w: Float) =
        upper.bottom <= lower.top + 0.3f * lower.height && lower.top - upper.bottom < 0.8f * lower.height && abs(upper.left - lower.left) < 0.03f * w

    /**
     * 昵称右边同一行上紧挨着的一截也是它的：OCR 偶尔把一个名字检测成两段（「Ana S」「Souza」），
     * 名字后面也可能跟着备注、号码。字号一样、隔着不到一个字高的连上。
     */
    private fun joinRight(z: Zones, name: ChatText): ChatText {
        var b = name.box
        var text = name.text
        while (true) {
            val next = z.body.firstOrNull {
                it.box.left >= b.right - 1f && it.box.left - b.right < b.height && abs(it.box.centerY - b.centerY) < 0.3f * b.height &&
                    abs(it.box.height - b.height) < 0.25f * b.height && plausible(it.text.trim())
            } ?: return ChatText(b, text)
            b = Box(b.left, min(b.top, next.box.top), next.box.right, max(b.bottom, next.box.bottom))
            text = "$text ${next.text.trim()}"
        }
    }

    private fun aboveMessages(z: Zones, avatars: List<Box>): List<ChatText> {
        val body = z.body.filter { !z.isCentered(it) }
        val typical = typicalHeight(body)
        return body.mapNotNull { l ->
            val text = l.text.trim()
            if (!plausible(text)) return@mapNotNull null
            // 只认靠左的：自己的消息上方不写名字。靠左 = 左缘在屏宽 35% 以内，且右缘没到靠右那一列
            if (l.box.left > 0.35f * z.w) return@mapNotNull null
            // 下面的消息左缘对齐。微信的名字和气泡左缘对齐，字在气泡里缩进一点（屏宽 2.6% 上下）：对齐的找不到，再往右多放一点找
            fun below(right: Float) = body.filter {
                it !== l && it.box.top >= l.box.bottom - 0.2f * l.box.height &&
                    it.box.top - l.box.bottom < 2.2f * l.box.height &&
                    it.box.left - l.box.left > -0.03f * z.w && it.box.left - l.box.left < right * z.w
            }.minByOrNull { it.box.top }
            val below = below(0.03f) ?: below(0.045f)
            // 下面那行比名字还小得多（不到六成）的，是图片消息里截图上的字，当作下面没有字
            if (below != null && below.box.height >= 0.6f * l.box.height) {
                // 和这页消息的字高也比一比：下面那行要是网址、邮箱这种全是字母的，OCR 的框比同字号的汉字高一截
                if (l.box.height > SMALLER * below.box.height || l.box.height > SMALLER_THAN_TYPICAL * typical) return@mapNotNull null
            } else {
                // 下面没有字的：发的是图片、表情。名字顶边和左边的头像顶边对齐、字号比这页的消息小，也是昵称
                val small = l.box.height < SMALLER * typical
                val topAligned = avatars.any { a ->
                    a.right <= l.box.left + 0.01f * z.w && l.box.left - a.right < 0.12f * z.w && abs(a.top - l.box.top) < 0.4f * a.height
                }
                if (!small || !topAligned) return@mapNotNull null
            }
            // 名字上方紧挨着的同字号、左缘对齐的行说明它是消息里的一行（上一行消息折下来的小字），不是昵称
            val above = body.any {
                it !== l && it.box.bottom <= l.box.top + 0.2f * l.box.height && l.box.top - it.box.bottom < 0.6f * l.box.height &&
                    abs(it.box.left - l.box.left) < 0.03f * z.w && abs(it.box.height - l.box.height) < 0.15f * l.box.height
            }
            if (above) return@mapNotNull null
            // 有头像列的页上，名字在头像列右边：头像顶对齐的（微信、QQ）挨着头像，底对齐的（Telegram、iMessage）头像在一组消息的最后一条旁边，
            // 不一定挨着名字，所以只要求它落在左侧头像列的右边
            val leftAvatars = avatars.filter { it.centerX < z.w / 2f }
            if (leftAvatars.isNotEmpty() && leftAvatars.none { a -> a.right <= l.box.left + 0.01f * z.w && l.box.left - a.right < 0.12f * z.w }) {
                return@mapNotNull null
            }
            ChatText(l.box, text)
        }
    }

    /**
     * 彩色的昵称：Telegram、WhatsApp 这类把名字写在气泡第一行，字号和消息一样，按颜色区分（每人一种颜色）；
     * 引用回复里被引用的人名也是这样。靠左、像个名字、字是彩色的，下面紧跟着（左缘差不到 5% 屏宽）一行不是彩色的字。
     * 彩色的网址、话题标签不算。
     * 一段开头的（上面紧挨着没有别的字：气泡第一行的发送人、引用里的人名）放宽：名字后面可能跟着头衔、助力数（OCR 读成一行），
     * 用户名常带数字，长度、数字都放宽，整行都遮。消息中间的彩色字（链接、文档标题）照常认，不放宽。
     * 居中的也算：引用里带缩略图的那行往右挪了，宽了，可能正好落在屏幕正中。
     */
    private fun colored(z: Zones, chroma: (Box) -> Int): List<ChatText> {
        val body = z.body.filter { it.box.left <= 0.35f * z.w }
        val hue = HashMap<ChatLine, Int>()
        fun of(l: ChatLine) = hue.getOrPut(l) { chroma(l.box) }
        fun next(l: ChatLine) = body.filter {
            it !== l && it.box.top >= l.box.bottom - 0.2f * l.box.height && it.box.top - l.box.bottom < 2f * l.box.height &&
                abs(it.box.left - l.box.left) < 0.05f * z.w
        }.minByOrNull { it.box.top }
        return body.mapNotNull { l ->
            val text = l.text.trim()
            val opens = z.body.none { it !== l && directlyAbove(it.box, l.box, z.w) }
            // 单个字的（图片里的大字、表情上的字）不算
            if (text.count { !it.isWhitespace() } < 2 || !plausible(text, if (opens) LONG else MAX_LEN, digits = opens) ||
                LINK.containsMatchIn(text) || PINNED.containsMatchIn(text)
            ) return@mapNotNull null
            if (of(l) < COLORED) return@mapNotNull null
            // 往下找第一行不是彩色的字：名字下面可能先是引用里被引用的人名（也是彩色的）
            var below = next(l)
            var hops = 0
            while (below != null && of(below) >= COLORED && hops < 2) {
                below = next(below)
                hops++
            }
            if (below == null || of(below) >= PLAIN) return@mapNotNull null
            ChatText(l.box, text)
        }
    }

    /** 字的平均彩度（RGB 最大减最小）到这个数算彩色字；白、黑、灰字不到 [PLAIN]。 */
    private const val COLORED = 55
    private const val PLAIN = 35

    /** 置顶消息条：标题也是彩色的。 */
    private val PINNED = Regex("置顶|消息$|Pinned", RegexOption.IGNORE_CASE)

    private val LINK = Regex("https?://|www\\.|[A-Za-z0-9]\\.(?:com|net|org|cn|io|me|co)\\b|^#", RegexOption.IGNORE_CASE)

    /**
     * 已经认出两个以上昵称的页上，和它们左缘对齐（误差 1% 屏宽）、一样大小的短行也是昵称：
     * 头像没认出来（和底色一样淡）、下面又是图片消息的那几个，上面的认法接不住。
     */
    private fun sameColumn(z: Zones, found: List<ChatText>): List<ChatText> {
        if (found.size < 2) return emptyList()
        val lefts = found.map { it.box.left }
        val height = found.map { it.box.height }.sorted()[found.size / 2]
        val typical = typicalHeight(z.body)
        return z.body.filter { l ->
            found.none { it.box == l.box } && !z.isCentered(l) && plausible(l.text.trim()) && l.box.height < SMALLER * typical &&
                lefts.any { abs(it - l.box.left) < 0.01f * z.w } && abs(l.box.height - height) < 0.2f * height
        }.map { ChatText(it.box, it.text.trim()) }
    }

    /**
     * Telegram 转发来的消息：气泡里（发送人的彩色名字下面）彩色的「转发自」后面，或者紧接着的下一行（也是彩色的），
     * 是原发送人、频道的名字。打字打出来的「转发自……」是黑字白字，不算。
     */
    private val FORWARDED = Regex("^(?:转发自|轉發自|Forwarded from)\\s*[:：]?\\s*", RegexOption.IGNORE_CASE)

    private fun forwarded(z: Zones, chroma: (Box) -> Int): List<ChatText> = z.body.mapNotNull { l ->
        val text = l.text.trim()
        val m = FORWARDED.find(text) ?: return@mapNotNull null
        if (chroma(l.box) < COLORED) return@mapNotNull null
        val rest = text.substring(m.range.last + 1)
        if (rest.isNotEmpty()) {
            return@mapNotNull if (plausible(rest, LONG, digits = true)) ChatText(TextSpan.boxOf(l, text.length - rest.length, rest.length, text), rest) else null
        }
        val next = z.body.filter { it !== l && directlyAbove(l.box, it.box, z.w) }.minByOrNull { it.box.top } ?: return@mapNotNull null
        val name = next.text.trim()
        if (plausible(name, LONG, digits = true) && chroma(next.box) >= COLORED) ChatText(next.box, name) else null
    }

    /**
     * 微信的引用：消息下面一块灰底的小字「方小禾：[图片]」「方小禾：今晚加班吗」，冒号前是被引用的人。
     * 只认比这页消息小一号的（引用的字比消息小），「地址：长江路88号」这种消息不算；常见的标签词、网址也不算。
     */
    private fun quoted(z: Zones): List<ChatText> {
        val body = z.body.filter { !z.isCentered(it) }
        val typical = typicalHeight(body)
        return body.mapNotNull { l ->
            if (l.box.height >= SMALLER * typical) return@mapNotNull null
            val text = l.text.trim()
            val m = QUOTE.find(text) ?: return@mapNotNull null
            val name = m.groupValues[1].trim()
            // 「今天 09:12」的冒号是时间里的，「https://」的是网址里的
            val after = text.substring(m.range.last + 1).trimStart()
            if (after.startsWith("//") || name.lastOrNull()?.isDigit() == true && after.firstOrNull()?.isDigit() == true) return@mapNotNull null
            if (!plausible(name) || LINK.containsMatchIn(name) || LABELS.any { it.equals(name, ignoreCase = true) }) return@mapNotNull null
            ChatText(TextSpan.boxOf(l, text.indexOf(name), name.length, text), name)
        }
    }

    private val QUOTE = Regex("^([^:：]{1,$MAX_LEN}?)\\s*[:：]")

    private val LABELS = setOf(
        "地址", "电话", "手机", "时间", "日期", "备注", "回复", "原因", "金额", "账号", "微信号", "邮箱", "姓名", "来源", "链接", "密码", "价格",
        "大小", "作者", "时长", "文件", "数量", "单号", "订单号", "状态", "说明", "提示", "注意",
        "Re", "To", "From", "Tel", "Phone", "Email", "Note", "Address", "Size", "Author", "Update", "Edit", "Fwd", "ETA", "PS", "Q", "A",
        "Total", "Status", "Tip", "Warning",
    )

    /** 「名字 + 时间」的消息头：同一行里名字后面紧跟一个时间（或 OCR 把两者读成一行），下面是消息正文。 */
    fun headerRows(z: Zones): List<ChatText> {
        val out = ArrayList<ChatText>()
        val body = z.body.filter { !z.isCentered(it) && it.box.left < 0.4f * z.w }
        for (l in body) {
            val text = l.text.trim()
            val joined = HEADER.find(text)
            if (joined != null && joined.groupValues[1].isNotBlank() && joined.groupValues[1].length <= MAX_LEN) {
                val name = joined.groupValues[1].trim()
                out += ChatText(TextSpan.boxOf(l, text.indexOf(name), name.length, text), name)
                continue
            }
            if (text.length > MAX_LEN || NOT_NAME.containsMatchIn(text) || ChatLayout.TIME.containsMatchIn(text)) continue
            val stamp = z.body.any {
                it !== l && abs(it.box.centerY - l.box.centerY) < 0.5f * l.box.height && it.box.left >= l.box.right &&
                    it.box.left - l.box.right < 0.1f * z.w && STAMP.matches(it.text.trim())
            }
            if (stamp) out += ChatText(l.box, text)
        }
        return out
    }

    /** OCR 把名字和时间读成了一行：「Rob Fletcher Today at 10:24 AM」「张三 今天 09:12」。 */
    private val HEADER = Regex("^(.+?)\\s+((?:今天|昨天|Today at|Yesterday at)\\s*\\d{1,2}:\\d{2}(?:\\s*[AP]M)?)$", RegexOption.IGNORE_CASE)

    /** 单独一行的消息头时间。 */
    private val STAMP = Regex("(?:今天|昨天|Today at|Yesterday at|\\d{4}/\\d{1,2}/\\d{1,2})?\\s*\\d{1,2}:\\d{2}(?:\\s*[AP]M)?", RegexOption.IGNORE_CASE)

    /** 引号：小字里的「“」常被读成「'」「‘」，前引号也会整个漏掉（「番茄炒蛋” 撤回了一条消息」）。 */
    private const val QUOTES = "\"“”「」'‘’"

    private val SYSTEM_NAME = listOf(
        Regex("[\"“「'‘]([^$QUOTES]{1,$MAX_LEN})[\"”」'’]\\s*(?:撤回了一条消息|拍了拍|加入了群聊|退出了群聊|移出了群聊)"),
        Regex("^(.{1,$MAX_LEN}?)\\s*(?:撤回了一条消息|加入了群聊|退出了群聊)"),
        Regex("^(.{1,$MAX_LEN}?)\\s+(?:joined|left|unsent a message)\\b", RegexOption.IGNORE_CASE),
    )

    /** OCR 在小字之间常补空格（「"孟 华" 拍 了 拍 我」）：去掉汉字之间的空格再配，名字的位置换回原文里的下标。 */
    private fun inSystemMessages(z: Zones): List<ChatText> = z.body.filter { z.isCentered(it) }.mapNotNull { l ->
        val text = l.text.trim()
        val kept = text.indices.filter { i ->
            !(text[i] == ' ' && i > 0 && i < text.length - 1 && (text[i - 1].code >= 0x2E80 || text[i + 1].code >= 0x2E80))
        }
        val compact = String(CharArray(kept.size) { text[kept[it]] })
        val m = SYSTEM_NAME.firstNotNullOfOrNull { it.find(compact) } ?: return@mapNotNull null
        val group = m.groups[1] ?: return@mapNotNull null
        var from = group.range.first
        var to = group.range.last
        while (from <= to && (compact[from] in QUOTES || compact[from].isWhitespace())) from++
        while (to >= from && (compact[to] in QUOTES || compact[to].isWhitespace())) to--
        if (from > to) return@mapNotNull null
        val name = compact.substring(from, to + 1)
        if (name == "你" || name.equals("You", ignoreCase = true)) return@mapNotNull null
        val first = kept[from]
        val last = kept[to]
        ChatText(TextSpan.boxOf(l, first, last - first + 1, text), name)
    }
}
