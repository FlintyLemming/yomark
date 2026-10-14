package moe.flinty.yomark.chat

import kotlin.math.abs
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

/** OCR 读出的一行：外接框和原文。 */
data class ChatLine(val box: Box, val text: String)

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

        val zones = Zones.of(lines, w, h)
        val grid = Grid.of(pixels)
        val avatars = Avatars.find(grid, zones)
        val (score, reasons) = score(zones, avatars)
        if (score < CHAT_THRESHOLD) return ChatFindings(false, score, reasons, emptyList(), emptyList(), null)

        val header = Avatars.header(grid, zones)
        val names = Names.find(zones, avatars.body)
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
 * 一页的几个区：状态栏、顶栏、正文、底栏。只按 OCR 行的位置分，不看像素。
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

    /** 贴着右边的一个时间：会话列表每一行右上角的「10:24」「昨天」。聊天页上没有这种。 */
    fun isRightEdgeTime(l: ChatLine) =
        l.box.right > 0.9f * w && l.box.width < 0.25f * w && ChatLayout.TIME.containsMatchIn(l.text) && l.text.length <= 12

    companion object {
        /** 状态栏下沿：手机状态栏大约是屏宽的 8%–11%。 */
        const val STATUS = 0.085f
        /** 顶栏最深到这里（iMessage 的顶栏有头像加名字两层，大约屏宽的 28%）。 */
        const val HEADER_MAX = 0.30f
        /** 底栏：输入栏、导航栏大约屏宽的 15%。 */
        const val BOTTOM = 0.17f

        fun of(lines: List<ChatLine>, w: Float, h: Float): Zones {
            val status = STATUS * w
            val visible = lines.filter { it.text.isNotBlank() && it.box.centerY > status }
            val title = Titles.pick(visible, w)
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

    /** 群聊人数「(23)」「（23）」，iMessage 名字后面的「›」「>」。 */
    private val SUFFIX = Regex("\\s*([(（]\\d+[)）]|[›>〉]+)\\s*$")

    /**
     * 顶栏标题：状态栏以下第一行字（屏宽 [Zones.HEADER_MAX] 以上，去掉状态行）。按字号挑不可靠：
     * 有的应用标题和消息一样大。iMessage 标题上面是对方头像，没有字，第一行仍是标题。
     * 太长的不是标题（标题一般一行放得下，宽不过屏宽七成）。
     */
    fun pick(lines: List<ChatLine>, w: Float): ChatLine? = lines
        .filter { it.box.centerY < Zones.HEADER_MAX * w && it.box.width < 0.7f * w && !STATUS_LINE.containsMatchIn(it.text) }
        .filter { it.text.trim().length in 1..40 && !ChatLayout.TIME.matches(it.text.trim()) }
        .minByOrNull { it.box.top }

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

    /** 标题去掉人数和箭头，框按字数比例缩到名字那一截。 */
    fun clean(line: ChatLine): ChatText {
        val raw = line.text.trim()
        val name = SUFFIX.replace(raw, "").trim().ifEmpty { raw }
        return ChatText(TextSpan.boxOf(line, raw.indexOf(name).coerceAtLeast(0), name.length, raw), name)
    }
}

/** 一行里一段字的框：OCR 只给整行的框，按字宽比例（汉字算两份，其余算一份）切出来。 */
internal object TextSpan {
    private fun units(s: String) = s.sumOf { if (it.code >= 0x2E80) 2 else 1 }.toFloat()

    fun boxOf(line: ChatLine, start: Int, length: Int, text: String = line.text): Box {
        val total = units(text)
        if (total <= 0f || length >= text.length) return line.box
        val before = units(text.substring(0, start.coerceIn(0, text.length)))
        val span = units(text.substring(start.coerceIn(0, text.length), (start + length).coerceIn(0, text.length)))
        val b = line.box
        val left = b.left + b.width * before / total
        return Box(left, b.top, min(b.right, left + b.width * span / total), b.bottom)
    }
}

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
    fun r(x: Int, y: Int) = (rgb[y * w + x] shr 16) and 0xFF
    fun g(x: Int, y: Int) = (rgb[y * w + x] shr 8) and 0xFF
    fun b(x: Int, y: Int) = rgb[y * w + x] and 0xFF

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
 * 1. 左右两条「沟」（各占屏宽 13%）里，按 40 格高一段取屏幕最边上的颜色当底色（壁纸变化慢，也成立）；
 * 2. 和底色差得多的格先开运算（去掉壁纸涂鸦那种细线），再闭运算、填洞，连成块，
 *    留下近正方形、填充率高、宽在屏宽 4.5%–13% 的块；
 * 3. 块要贴边（离屏幕边不超过屏宽 6%）、整个落在沟里，而且要有另一块大小相近、离边一样远（左右镜像也算）的作伴，
 *    单独一块的多半是图片消息、表情；
 * 4. 头像列找到以后，在列里再找一遍形状不规整的：和底色几乎一样的灰白默认头像只剩中间一个人像剪影，
 *    按列里头像的大小补成一个框。
 */
internal object Avatars {

    data class Found(val body: List<Box>, val mirrored: Boolean)

    private const val GUTTER = 0.13f
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
        // 碰到沟内侧边界的块是被沟截断的大东西（贴边的气泡、图片消息），不是头像：头像整个落在沟里
        val left = components(g, 0, gut, top, bottom).filter { it.avatarShaped(g) && it.x0 < edge && it.x1 < gut - 1 }
        val right = components(g, g.w - gut, g.w, top, bottom).filter { it.avatarShaped(g) && it.x1 > g.w - edge && it.x0 > g.w - gut + 1 }
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

        /** 大小像头像（屏宽 4.5%–13%）、近正方形、填充率高（圆是 0.79）。 */
        fun avatarShaped(g: Grid): Boolean {
            if (width < MIN_SIZE * g.w || width > MAX_SIZE * g.w) return false
            val ratio = width.toFloat() / height
            return ratio in 0.8f..1.25f && count.toFloat() / (width * height) >= 0.6f
        }
    }

    /** 区域里和底色不同的连通块（开运算去细线、闭运算补缝、填洞之后）。 */
    private fun components(g: Grid, x0: Int, x1: Int, y0: Int, y1: Int): List<Blob> {
        val bw = x1 - x0
        val bh = y1 - y0
        if (bw <= 2 || bh <= 2) return emptyList()
        var mask = BooleanArray(bw * bh)
        var band = 0
        while (band < bh) {
            val end = min(bh, band + BAND)
            val bg = edgeColor(g, y0 + band, y0 + end)
            for (y in band until end) for (x in 0 until bw) {
                val gx = x0 + x
                val gy = y0 + y
                val d = abs(g.r(gx, gy) - (bg shr 16 and 0xFF)) + abs(g.g(gx, gy) - (bg shr 8 and 0xFF)) + abs(g.b(gx, gy) - (bg and 0xFF))
                mask[y * bw + x] = d > INK
            }
            band = end
        }
        mask = dilate(erode(mask, bw, bh), bw, bh)                                       // 开：去掉一两格宽的细线
        mask = erode(erode(dilate(dilate(mask, bw, bh), bw, bh), bw, bh), bw, bh)        // 闭：补上头像边上的缺口
        fillHoles(mask, bw, bh)
        val out = ArrayList<Blob>()
        val seen = BooleanArray(bw * bh)
        val stack = IntArray(bw * bh)
        for (start in mask.indices) {
            if (!mask[start] || seen[start]) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
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
                for (n in intArrayOf(if (x > 0) i - 1 else -1, if (x < bw - 1) i + 1 else -1, if (y > 0) i - bw else -1, if (y < bh - 1) i + bw else -1)) {
                    if (n >= 0 && mask[n] && !seen[n]) { seen[n] = true; stack[sp++] = n }
                }
            }
            out += Blob(x0 + minX, y0 + minY, x0 + maxX + 1, y0 + maxY + 1, count)
        }
        return out
    }

    /**
     * 一段的底色：取屏幕最左、最右两条窄边（各 1.5% 屏宽）里最常见的颜色（每通道量化到 8 级一档）。
     * 不在整条沟里取：沟一共才屏宽 13%，纯色头像就占了大半，取出来的「底色」成了头像的颜色。
     * 头像离屏幕边总留着一道缝（屏宽 2% 上下），两条窄边上就是底色或壁纸。
     */
    private fun edgeColor(g: Grid, y0: Int, y1: Int): Int {
        val edge = max(2, (0.015f * g.w).toInt())
        val counts = HashMap<Int, Int>()
        for (y in y0 until y1) for (x in (0 until edge) + (g.w - edge until g.w)) {
            val key = (g.r(x, y) shr 3 shl 10) or (g.g(x, y) shr 3 shl 5) or (g.b(x, y) shr 3)
            counts[key] = (counts[key] ?: 0) + 1
        }
        val k = counts.maxByOrNull { it.value }?.key ?: return 0
        return ((k shr 10 and 31) * 8 + 4 shl 16) or ((k shr 5 and 31) * 8 + 4 shl 8) or ((k and 31) * 8 + 4)
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
 * 发言者的昵称。三处：
 * 1. 消息上方一行小字（微信、QQ、钉钉、飞书、LINE、iMessage、Messenger 的群聊；WhatsApp、Telegram 写在气泡里第一行，
 *    在 OCR 行上看是一样的）：下面紧跟一行字号更大、左缘对齐的消息；
 * 2. 「名字 + 时间」的消息头（Discord、Slack）；
 * 3. 系统消息里的名字：「"张三" 撤回了一条消息」「Rob joined」。
 */
internal object Names {

    private const val MAX_LEN = 24

    /** 字号比消息小这么多才算昵称（真实界面上昵称大约是消息字号的七八成）。 */
    private const val SMALLER = 0.88f

    private val NOT_NAME = Regex("[，。！？!?；;…]|\\d{3,}")

    /**
     * 这一页消息的字高：取上四分位。群聊里昵称、时间这些小字可能和消息差不多一样多，中位数会落到它们中间；
     * 消息的字总是这一页最大的那一档（顶栏标题不在正文里）。
     */
    private fun typicalHeight(lines: List<ChatLine>): Float =
        lines.map { it.box.height }.sorted().let { if (it.isEmpty()) 0f else it[it.size * 3 / 4] }

    /** 像个名字：不长、有字母或汉字（「+1」「38"」这种短消息、语音时长不是）、不是句子、不是时间。 */
    private fun plausible(text: String) =
        text.length <= MAX_LEN && text.any { it.isLetter() } && !NOT_NAME.containsMatchIn(text) && !ChatLayout.TIME.containsMatchIn(text)

    fun find(z: Zones, avatars: List<Box>): List<ChatText> {
        val out = ArrayList<ChatText>()
        out += aboveMessages(z, avatars)
        out += sameColumn(z, out)
        out += headerRows(z).filter { r -> out.none { it.box == r.box } }
        out += inSystemMessages(z)
        return out
    }

    private fun aboveMessages(z: Zones, avatars: List<Box>): List<ChatText> {
        val body = z.body.filter { !z.isCentered(it) }
        val typical = typicalHeight(body)
        return body.mapNotNull { l ->
            val text = l.text.trim()
            if (!plausible(text)) return@mapNotNull null
            // 只认靠左的：自己的消息上方不写名字。靠左 = 左缘在屏宽 35% 以内，且右缘没到靠右那一列
            if (l.box.left > 0.35f * z.w) return@mapNotNull null
            val below = body.filter {
                it !== l && it.box.top >= l.box.bottom - 0.2f * l.box.height &&
                    it.box.top - l.box.bottom < 2.2f * l.box.height &&
                    abs(it.box.left - l.box.left) < 0.03f * z.w
            }.minByOrNull { it.box.top }
            if (below != null) {
                if (l.box.height > SMALLER * below.box.height) return@mapNotNull null
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

    private val SYSTEM_NAME = listOf(
        Regex("[\"“「]([^\"”」]{1,$MAX_LEN})[\"”」]\\s*(?:撤回了一条消息|拍了拍|加入了群聊|退出了群聊|移出了群聊)"),
        Regex("^(.{1,$MAX_LEN}?)\\s*(?:撤回了一条消息|加入了群聊|退出了群聊)"),
        Regex("^(.{1,$MAX_LEN}?)\\s+(?:joined|left|unsent a message)\\b", RegexOption.IGNORE_CASE),
    )

    private fun inSystemMessages(z: Zones): List<ChatText> = z.body.filter { z.isCentered(it) }.mapNotNull { l ->
        val text = l.text.trim()
        val m = SYSTEM_NAME.firstNotNullOfOrNull { it.find(text) } ?: return@mapNotNull null
        val name = m.groups[1] ?: return@mapNotNull null
        if (name.value.isBlank() || name.value == "你" || name.value.equals("You", ignoreCase = true)) return@mapNotNull null
        ChatText(TextSpan.boxOf(l, name.range.first, name.value.length, text), name.value)
    }
}
