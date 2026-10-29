package moe.flinty.yomark.statusbar

import moe.flinty.yomark.chat.Box
import moe.flinty.yomark.chat.ChatLine
import moe.flinty.yomark.chat.Pixels
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * 截图顶上的状态栏到哪一行为止：导出时裁掉它（设置 → 实验室 →「裁切状态栏」，安卓那一层见 StatusBarCropper）。
 *
 * 靠时间找：状态栏左边（有的系统放正中）是一行「14:30」，字在状态栏里上下居中，所以状态栏的底差不多是字心高度的两倍。
 * 估出来的这一行再拿像素校准两处：
 * - 状态栏和底下的页面颜色不一样：两种颜色的分界行就是底，比估的准（ColorOS 的状态栏比两倍字心还低几个像素）；
 * - 挖孔屏上的胶囊（流体云、超级岛、灵动胶囊……）：截图里是一块黑色的圆角块，比时间高，下沿常常伸出状态栏。
 *   时间那几行上比字高一截、往下伸过字底的深色实心块就是它，裁切线挪到它的下沿以下。
 *   状态栏本身也是黑的（深色模式）时胶囊和它连成一片，看不出来，也就不用管。
 *
 * 和 chat 包一样只用 Kotlin 标准库，行和像素用的也是它的 ChatLine、Pixels，好在桌面 JVM 上拿合成图跑。
 * 长度都按图的短边 U 量（竖屏截图就是屏宽）：长截图的高可以是宽的五六倍。
 */

/**
 * 找状态栏的结果：[bottom] 以上的行（不含 [bottom]）裁掉，没找到是 null。
 * [notes] 是一步步怎么定的、没找到的话卡在了哪一步，调试信息里列出来。
 */
data class StatusBarCut(val bottom: Int?, val notes: List<String>)

object StatusBar {

    /**
     * 安卓那一层只读图顶上这么高（短边的倍数）的像素：估的状态栏底最多 [MAX_BAR]，
     * 胶囊往下最多找到它的 [REACH] 倍，分界只找到 [BOUNDARY_REACH] 倍，都在这以内。
     */
    const val BAND = 0.6f

    /**
     * @param p 图顶上的一截像素，宽是整张图的宽，高至少是 [BAND] 倍短边（图没那么高就是整张图）
     * @param height 整张图的高
     */
    fun find(lines: List<ChatLine>, p: Pixels, height: Int = p.height): StatusBarCut {
        val notes = ArrayList<String>()
        fun none(why: String) = StatusBarCut(null, notes + why)
        val w = p.width
        val unit = min(w, height)
        val clock = clock(lines, w, unit) ?: return none("没在图顶上读到时间")
        notes += "时间「${clock.text}」${clock.box.describe()}"
        val ink = inkRows(clock.box, p) ?: return none("时间那块看不出字")
        val t = ink.first
        val b = ink.last
        val th = b - t + 1
        // 截过的图从一行带时间的字开始（日程表上的「14:30 开会」）：字贴着图顶。状态栏上的字离图顶总有大半个字高
        if (t < TOP_GAP * th) return none("字从第 $t 行起，贴着图顶，不像状态栏")
        val estimate = t + b + 1                      // 字心 (t + b + 1) / 2 的两倍
        if (estimate > MAX_BAR * unit) return none("字心太靠下（估的底在第 $estimate 行），不像状态栏")
        notes += "字在第 $t–$b 行，估状态栏的底在第 $estimate 行"

        val limit = min(p.height, (REACH * estimate).roundToInt())
        val rows = Rows(p, min(p.height, max(limit, (BOUNDARY_REACH * estimate).roundToInt() + 3)))
        val fg = Foreground(p, limit)
        // 状态栏右头是信号、电池这些图标：时间那几行的右边四分之一里得有东西。居中的聊天时间、右边空着的一行字都不是
        if (!fg.any(t..b, (RIGHT * w).toInt() until w)) return none("时间那几行右边没有图标，不像状态栏")

        val margin = max(2, (MARGIN * th).roundToInt())
        val floor = b + 1 + margin
        val edge = rows.boundary(b + max(2, (BOUNDARY_GAP * th).roundToInt()), (BOUNDARY_REACH * estimate).roundToInt())
        var cut = if (edge != null) {
            notes += "状态栏和页面的分界在第 $edge 行"
            edge
        } else {
            rows.snap(estimate, floor, limit).also {
                notes += if (it == estimate) "没有分界，按估的" else "没有分界，估的那行压着东西，挪到第 $it 行"
            }
        }
        fg.blobs().filter { it.isCapsule(b, th, w, limit) }.forEach { c ->
            val end = fg.tail(c)
            notes += "胶囊 ${c.describe()}，下沿连灰边到第 $end 行"
            cut = max(cut, end + 1 + margin)
        }
        cut = max(cut, floor)
        notes += "裁掉顶上 $cut 行"
        return StatusBarCut(cut, notes)
    }

    /** 读出的时间：原文里的那几个字和它们的框。 */
    private class Clock(val text: String, val box: Box)

    /**
     * 「14:30」「9:41」，冒号可以是全角。前面连着数字、冒号的不算（「10:14:30」的后半截）；
     * 后面跟着秒的照样算：有的系统让状态栏显示秒。
     */
    private val CLOCK = Regex("""(?<![\d:：∶])(?:[01]?\d|2[0-3])[:：∶][0-5]\d(?!\d)""")

    /**
     * 顶上的时间：读成「14:30」的字，在图的最上面（上面没读到别的字）、靠左或者居中，字高像状态栏上的字。
     * 右边的不认：老系统、少数系统把时间放右边，同一处更常见的是截过的会话列表第一行右头的时间。
     * 有好几个（胶囊里的计时也是「00:12」）取最靠上的，差不多一样高的取最左的。
     */
    private fun clock(lines: List<ChatLine>, w: Int, unit: Int): Clock? {
        val found = lines.flatMap { line ->
            CLOCK.findAll(line.text).mapNotNull { m ->
                val box = boxOf(line, m.range)
                val h = box.height
                val atTop = box.centerY <= MAX_CLOCK_Y * unit && box.top <= TOP_ROOM * h
                val side = box.centerX < LEFT * w || abs(box.centerX - w / 2f) < CENTER * w
                if (h in MIN_CLOCK_H * unit..MAX_CLOCK_H * unit && atTop && side) Clock(m.value, box) else null
            }.toList()
        }.filter { c -> lines.none { it.box.bottom < c.box.top - ABOVE * c.box.height } }
        val top = found.minOfOrNull { it.box.centerY } ?: return null
        return found.filter { it.box.centerY - top < it.box.height / 2 }.minBy { it.box.left }
    }

    /** 一行里 [range] 那几个字的框：有逐字的框就并起来，没有就是整行的框。 */
    private fun boxOf(line: ChatLine, range: IntRange): Box {
        val chars = line.chars?.let { c -> range.mapNotNull { c.getOrNull(it) } }.orEmpty()
        if (chars.isEmpty()) return line.box
        return Box(chars.minOf { it.left }, chars.minOf { it.top }, chars.maxOf { it.right }, chars.maxOf { it.bottom })
    }

    /**
     * 时间的字从哪一行到哪一行：OCR 的框上下总留着空，字心要按墨迹量。框左右之间、上下各多看半个框高，
     * 最常见的颜色是底色，和它差到最大反差一半的像素是字；从框的中线往上下找连着有字的那一段行。
     */
    private fun inkRows(box: Box, p: Pixels): IntRange? {
        val x0 = floor(box.left).toInt().coerceIn(0, p.width - 1)
        val x1 = ceil(box.right).toInt().coerceIn(x0 + 1, p.width)
        val half = (box.height / 2).toInt()
        val y0 = (floor(box.top).toInt() - half).coerceIn(0, p.height - 1)
        val y1 = (ceil(box.bottom).toInt() + half).coerceIn(y0 + 1, p.height)
        val bg = Mode().apply { for (y in y0 until y1) for (x in x0 until x1) add(p.at(x, y)) }.color()
        var strongest = 0
        for (y in y0 until y1) for (x in x0 until x1) strongest = max(strongest, diff(p.at(x, y), bg))
        if (strongest < CONTRAST) return null
        val ink = max(INK, strongest / 2)
        val rows = IntArray(y1 - y0) { i -> (x0 until x1).count { x -> diff(p.at(x, y0 + i), bg) > ink } }
        val middle = ((box.top + box.bottom) / 2).toInt().coerceIn(y0, y1 - 1) - y0
        val mid = if (rows[middle] > 0) middle else rows.indices.filter { rows[it] > 0 }.minByOrNull { abs(it - middle) } ?: return null
        var t = mid
        while (t > 0 && rows[t - 1] > 0) t--
        var b = mid
        while (b + 1 < rows.size && rows[b + 1] > 0) b++
        return (y0 + t)..(y0 + b)
    }

    /** 图顶上 [count] 行各自最常见的颜色，和这一行里有多少成像素是这个颜色。找分界、找干净的一行用。 */
    private class Rows(private val p: Pixels, count: Int) {
        private val color = IntArray(count)
        private val share = FloatArray(count)

        init {
            for (y in 0 until count) {
                val mode = Mode().apply { for (x in 0 until p.width) add(p.at(x, y)) }.color()
                color[y] = mode
                share[y] = (0 until p.width).count { diff(p.at(it, y), mode) <= SAME }.toFloat() / p.width
            }
        }

        /**
         * 状态栏和页面的分界：从 [lo] 往下到 [hi]，头一个上面三行是一种颜色、下面三行是另一种颜色的行。
         * 每一行都得几乎是纯色（胶囊横在上面的行不算），上下三行各自一行行地几乎不变：渐变的头图一行比一行深，不是分界。
         */
        fun boundary(lo: Int, hi: Int): Int? {
            for (y in max(lo, 3)..min(hi, color.size - 3)) {
                if (diff(color[y - 1], color[y]) <= EDGE) continue
                val flat = (y - 3 until y + 3).all { share[it] >= FLAT }
                val steady = (y - 3 until y - 1).all { diff(color[it], color[it + 1]) <= STEADY } &&
                    (y until y + 2).all { diff(color[it], color[it + 1]) <= STEADY }
                if (flat && steady) return y
            }
            return null
        }

        /**
         * 没有分界时按估的那一行裁。它压着东西（底下工具栏的图标、字）的话，挪到附近最近的一行干净的，
         * 不从半截图标中间裁过去；附近没有就按估的。不挪到 [floor] 以上、[limit] 以下。
         */
        fun snap(estimate: Int, floor: Int, limit: Int): Int {
            val reach = max(2, (SNAP * estimate).roundToInt())
            for (d in 0..reach) for (y in intArrayOf(estimate - d, estimate + d)) {
                if (y < floor || y >= min(limit, color.size)) continue
                if (share[y] >= CLEAN) return y
            }
            return estimate
        }
    }

    /**
     * 图顶上 [limit] 行里的前景：和这一行的底色差得多的像素。每行的底色取这一行最左、最右两条上最常见的颜色——
     * 图标、胶囊都不贴着屏幕边；不取整行的：胶囊比半个屏宽还宽时，整行最常见的颜色就成了胶囊的黑。
     */
    private class Foreground(private val p: Pixels, private val limit: Int) {
        private val w = p.width
        private val bg = IntArray(limit)
        private val on = BooleanArray(w * limit)

        init {
            val strip = max(2, (EDGE_STRIP * w).roundToInt()).coerceAtMost(w / 2)
            for (y in 0 until limit) {
                val mode = Mode()
                for (x in 0 until strip) {
                    mode.add(p.at(x, y))
                    mode.add(p.at(w - 1 - x, y))
                }
                bg[y] = mode.color()
                for (x in 0 until w) on[y * w + x] = diff(p.at(x, y), bg[y]) > FG
            }
        }

        fun any(rows: IntRange, cols: IntRange): Boolean = rows.any { y -> y < limit && cols.any { x -> on[y * w + x] } }

        /** 八邻接的连通块。 */
        fun blobs(): List<Blob> {
            val seen = BooleanArray(on.size)
            val stack = IntArray(on.size)
            val out = ArrayList<Blob>()
            for (start in on.indices) {
                if (!on[start] || seen[start]) continue
                var sp = 0
                stack[sp++] = start
                seen[start] = true
                var left = w
                var top = limit
                var right = -1
                var bottom = -1
                var count = 0
                var luma = 0L
                while (sp > 0) {
                    val i = stack[--sp]
                    val x = i % w
                    val y = i / w
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                    count++
                    luma += luma(p.at(x, y))
                    for (dy in -1..1) for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until w || ny !in 0 until limit) continue
                        val j = ny * w + nx
                        if (on[j] && !seen[j]) {
                            seen[j] = true
                            stack[sp++] = j
                        }
                    }
                }
                out += Blob(left, top, right, bottom, count, (luma / count).toInt())
            }
            return out
        }

        /**
         * 胶囊下沿往下还有一两行抗锯齿、JPEG 糊出来的灰边：和底色差得不到算前景，可看得见。
         * 下沿中间那一半（圆角两头不算）一行行往下看，平均还差着 [TAIL] 的就接上。
         */
        fun tail(c: Blob): Int {
            val quarter = (c.right - c.left + 1) / 4
            val cols = (c.left + quarter)..(c.right - quarter)
            var end = c.bottom
            while (end + 1 < limit && cols.sumOf { diff(p.at(it, end + 1), bg[end + 1]) } > TAIL * (cols.last - cols.first + 1)) end++
            return end
        }
    }

    /** 一块前景：外接框（含两头）、像素数、平均亮度。 */
    private class Blob(val left: Int, val top: Int, val right: Int, val bottom: Int, val count: Int, val luma: Int) {
        val width get() = right - left + 1
        val height get() = bottom - top + 1

        /**
         * 是不是胶囊：从时间的字底（[b]）以上往下伸过字底，比字高一截（信号、电池图标只高两成），
         * 宽至少有高的六成、不超过屏宽的六成，像素占外接框一半以上，深色，不贴着图的左右边和找的范围的底（[limit]，不含）：
         * 贴着的是页面上的大块，深色的头图、横幅。
         */
        fun isCapsule(b: Int, th: Int, w: Int, limit: Int): Boolean =
            top <= b && bottom > b &&
                height >= TALL * th && width >= ROUND * height && width <= WIDE * w &&
                left > 0 && right < w - 1 && bottom < limit - 1 &&
                count >= SOLID * width * height && luma <= DARK

        fun describe() = "$left,$top ${width}×$height"
    }

    /**
     * 一堆颜色里最常见的那种：每通道量化到 8 级一档数个数，取最多的那一档里原来的颜色的平均。
     * 不取档的中间：纯白落在 248..255 那一档，档中间是 252，和真的白差了 9，比这里找灰边的门槛 [TAIL] 差不了多少。
     */
    private class Mode {
        private val counts = HashMap<Int, IntArray>()

        fun add(c: Int) {
            val sum = counts.getOrPut((c shr 19 and 31 shl 10) or (c shr 11 and 31 shl 5) or (c shr 3 and 31)) { IntArray(4) }
            sum[0]++
            sum[1] += c shr 16 and 0xFF
            sum[2] += c shr 8 and 0xFF
            sum[3] += c and 0xFF
        }

        fun color(): Int {
            val s = counts.values.maxByOrNull { it[0] } ?: return 0
            return (s[1] / s[0] shl 16) or (s[2] / s[0] shl 8) or (s[3] / s[0])
        }
    }

    private fun Box.describe() =
        "${left.roundToInt()},${top.roundToInt()} ${width.roundToInt()}×${height.roundToInt()}"

    /** 两个颜色三个通道差的和。 */
    private fun diff(a: Int, b: Int) =
        abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) + abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

    private fun luma(c: Int) = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000

    // ---------- 时间 ----------

    /** 时间的框高（OCR 的框，比字高）在短边的这个范围里：540 宽的分析图上 4～32 像素。 */
    private const val MIN_CLOCK_H = 0.008f
    private const val MAX_CLOCK_H = 0.06f

    /** 框的中线不低于短边的 12%：1080 宽的截图上状态栏一百来像素高，字心在五六十。 */
    private const val MAX_CLOCK_Y = 0.12f

    /** 框顶离图顶不超过这么多个框高。 */
    private const val TOP_ROOM = 2.5f

    /** 靠左：框的中心在左边 40% 里；居中：离正中不到屏宽的 10%。 */
    private const val LEFT = 0.4f
    private const val CENTER = 0.1f

    /** 别的字的底高过时间的顶这么多个框高，就是时间上面还有字：时间不在图顶上。 */
    private const val ABOVE = 0.25f

    /** 框里最大的反差（三个通道差的和）不到这个，就是没有字。 */
    private const val CONTRAST = 40

    /** 算字的门槛至少这么多：最大反差一半太低时（灰字），JPEG 的噪点也算成了字。 */
    private const val INK = 30

    /** 字顶离图顶至少这么多个字高。真状态栏上是半个到一个半字高。 */
    private const val TOP_GAP = 0.4f

    /** 估的状态栏底不超过短边的这么多：再低就不是状态栏上的字了。 */
    private const val MAX_BAR = 0.25f

    // ---------- 分界、估的底 ----------

    /** 分界从字底往下这么多个字高起找：避开状态栏图标的下沿（图标比字低一点）。 */
    private const val BOUNDARY_GAP = 0.25f

    /** 分界找到估的底的这么多倍为止。ColorOS 的真底是估的 1.04 倍；再往下就会把工具栏里搜索框的上沿当成分界。 */
    private const val BOUNDARY_REACH = 1.2f

    /** 上下两种颜色差过这些才算分界：#FFFFFF 和 #F7F7F7 差 24。 */
    private const val EDGE = 20

    /** 分界上下三行，相邻两行最常见的颜色差不过这些：JPEG 压过的纯色差两三，渐变的头图一行差得比这多。 */
    private const val STEADY = 12

    /** 和一行最常见的颜色差不过这些算同一种颜色：JPEG 的噪点、字边的振铃。 */
    private const val SAME = 24

    /** 分界上下三行里每一行至少这么多成是同一种颜色。比一成宽的胶囊横在上面的行就不够了。 */
    private const val FLAT = 0.9f

    /** 估的底压着东西时，上下挪不超过它的这么多。 */
    private const val SNAP = 0.12f

    /** 一行里至少这么多成是同一种颜色才算干净，可以从它这儿裁。 */
    private const val CLEAN = 0.98f

    // ---------- 图标、胶囊 ----------

    /** 和这一行的底色差这么多算前景：浅灰的图标和白底也差一百五以上，JPEG 的噪点二三十。 */
    private const val FG = 60

    /** 每行的底色看最左、最右各这么宽（屏宽的比例）的两条：图标离屏幕边总有屏宽的 4% 往上。 */
    private const val EDGE_STRIP = 0.03f

    /** 图标在右边四分之一里。 */
    private const val RIGHT = 0.75f

    /** 胶囊往下最多找到估的底的这么多倍。 */
    private const val REACH = 1.7f

    /** 胶囊至少有字高的这么多倍：信号、电池图标一点二倍上下。 */
    private const val TALL = 1.6f

    /** 胶囊的宽至少有高的这么多：只围着摄像头的一圈是圆的，宽和高一样。 */
    private const val ROUND = 0.6f

    /** 胶囊不比屏宽的 60% 宽：展开着的超级岛也就一半。 */
    private const val WIDE = 0.6f

    /** 胶囊的像素占外接框的这么多以上：圆是 0.79，里面有白字、图标也有六七成。 */
    private const val SOLID = 0.5f

    /** 胶囊的平均亮度不超过这些（0～255）：截图里它是黑的。 */
    private const val DARK = 60

    /** 胶囊下沿往下，下沿中间那一半的平均差还过这些的行算灰边。 */
    private const val TAIL = 12

    /** 裁切线离字底、胶囊的下沿至少再留这么多个字高（至少 2 像素）：抗锯齿、JPEG 的灰边不留在图上。 */
    private const val MARGIN = 0.1f
}
