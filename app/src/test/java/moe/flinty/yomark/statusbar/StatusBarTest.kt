package moe.flinty.yomark.statusbar

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import moe.flinty.yomark.chat.Box
import moe.flinty.yomark.chat.ChatFixtures
import moe.flinty.yomark.chat.ChatLine
import moe.flinty.yomark.chat.Pixels
import org.junit.Test

/**
 * 状态栏从哪儿裁（[StatusBar]），在合成的页上跑：时间是几块和字一样高的墨，OCR 行是画的时候记下的，
 * 不走真模型，量的是规则本身。真模型读的截图在 StatusBarCropperTest 里。
 *
 * 默认的页仿 1080×2340 截图缩到一半：540 宽，状态栏 50 高，时间的字 14 高、上下居中，右边是信号和电池。
 */
class StatusBarTest {

    @Test fun `a status bar the same colour as the page is cut at twice the clock's middle`() {
        val page = Page()
        assertThat(cut(page)).isEqualTo(50)
    }

    /** ColorOS 这样的：状态栏比两倍字心高几个像素，颜色和底下的页面不一样，就按分界裁。 */
    @Test fun `a status bar of its own colour is cut where the colour changes`() {
        val page = Page(bar = 56, clockY = 25, barColor = BLUE, ink = WHITE)
        assertThat(cut(page)).isEqualTo(56)
    }

    @Test fun `a capsule hanging below the status bar pushes the cut below it`() {
        val page = Page().apply { pill(220, 6, 320, 62, BLACK) }
        assertThat(cut(page)).isEqualTo(62 + 2)
    }

    @Test fun `a capsule inside the status bar leaves the cut at the bottom of the bar`() {
        val page = Page().apply { pill(225, 8, 315, 42, BLACK) }
        assertThat(cut(page)).isEqualTo(50)
    }

    /** 胶囊里放着歌、计时：白字、彩色的封面把它掏出几个洞，还是一块；计时的「00:12」也不当成时间。 */
    @Test fun `a capsule with a timer in it is still a capsule and the timer is not the clock`() {
        val page = Page().apply {
            pill(200, 6, 340, 62, BLACK)
            rect(214, 20, 238, 44, 0xFFE91E63.toInt())
            rect(250, 26, 300, 38, WHITE)
            lines += ChatLine(Box(248f, 23f, 302f, 41f), "00:12")
        }
        val found = StatusBar.find(page.lines, page.pixels)
        assertThat(found.bottom).isEqualTo(64)
        assertThat(found.notes.first()).contains("14:30")
    }

    /** 深色模式：状态栏是黑的，胶囊和它连成一片，截图上看不出来，按状态栏裁。 */
    @Test fun `a black capsule on a black status bar is left alone`() {
        val page = Page(barColor = BLACK, pageColor = BLACK, ink = WHITE).apply { pill(220, 6, 320, 62, BLACK) }
        assertThat(cut(page)).isEqualTo(50)
    }

    @Test fun `a capsule on a dark grey status bar is found`() {
        val page = Page(barColor = DARK_GREY, pageColor = DARK_GREY, ink = WHITE).apply { pill(220, 6, 320, 62, BLACK) }
        assertThat(cut(page)).isEqualTo(64)
    }

    /** 胶囊的下沿抗锯齿、JPEG 糊出来一两行灰边：和底色差得不到算前景，也要裁掉。 */
    @Test fun `the faint rows under a capsule are cut too`() {
        val page = Page().apply {
            pill(220, 6, 320, 62, BLACK)
            rect(240, 62, 300, 63, 0xFFEBEBEB.toInt())
            rect(240, 63, 300, 64, 0xFFF6F6F6.toInt())
        }
        assertThat(cut(page)).isEqualTo(64 + 2)
    }

    /** 状态栏底下接着渐变的头图：一行比一行浅，哪儿都不是分界，按估的裁。 */
    @Test fun `a gradient under the status bar is not taken for a boundary`() {
        listOf(1, 3, 8).forEach { step ->
            val page = Page(bar = 35, clockY = 25, barColor = DARK_BLUE, ink = WHITE).apply {
                for (y in 35 until 120) rect(0, y, w, y + 1, rgb(30 + step * (y - 35), 50 + step * (y - 35), 110 + step * (y - 35)))
            }
            assertWithMessage("每行每个通道浅 $step").that(cut(page)).isEqualTo(50)
        }
    }

    /** 估的那一行压着底下工具栏的图标：挪到附近干净的一行，不从图标中间裁过去。 */
    @Test fun `the estimate moves off a row with something on it`() {
        val page = Page().apply { rect(20, 48, 40, 80, BLACK) }
        assertThat(cut(page)).isEqualTo(47)
    }

    /** 1080 宽的原图、长截图：长度按短边量，和缩到一半的一样。 */
    @Test fun `full size and long screenshots are cut the same way`() {
        assertThat(cut(Page(w = 1080, h = 2340, scale = 2))).isEqualTo(100)
        assertThat(cut(Page(w = 1080, h = 5148, scale = 2).apply { pill(440, 12, 640, 124, BLACK) })).isEqualTo(124 + 3)
    }

    /** 安卓那一层只读图顶上一截像素，整张图的高另给：结果和给整张图一样。 */
    @Test fun `only the top of the image is needed`() {
        val page = Page().apply { pill(220, 6, 320, 62, BLACK) }
        val full = page.pixels
        val rows = (StatusBar.BAND * page.w).toInt()
        val top = Pixels(page.w, rows, full.argb.copyOfRange(0, page.w * rows))
        assertThat(StatusBar.find(page.lines, top, page.h).bottom).isEqualTo(cut(page))
    }

    // ---------- 不是状态栏 ----------

    @Test fun `without icons on the right it is not a status bar`() {
        val page = Page(icons = false)
        assertThat(cut(page)).isNull()
    }

    /** 截过的会话列表，第一行右头是时间。 */
    @Test fun `a time on the right is not the clock`() {
        val page = Page(clockX = 460, icons = false).apply { rect(30, 18, 120, 32, BLACK) }
        assertThat(cut(page)).isNull()
    }

    /** 截过的日程表从「14:30 开会」那行开始：字贴着图顶。 */
    @Test fun `a time flush with the top of a cropped image is not the clock`() {
        val page = Page(clockY = 9)
        assertThat(cut(page)).isNull()
    }

    @Test fun `a time with text above it is not at the top`() {
        val page = Page(bar = 100, clockY = 50).apply { rect(30, 10, 200, 26, BLACK) }
        assertThat(cut(page)).isNotNull()
        page.lines += ChatLine(Box(28f, 8f, 202f, 28f), "今天的日程")
        assertThat(cut(page)).isNull()
    }

    /** 截过的聊天页顶上一条居中的时间：右边空着。 */
    @Test fun `a centred chat time stamp is not a status bar`() {
        val page = Page(clockX = 243, icons = false)
        assertThat(cut(page)).isNull()
    }

    /** 锁屏上的大时钟：字比状态栏上的大好几倍。 */
    @Test fun `a big clock is not a status bar clock`() {
        val page = Page(bar = 260, clockY = 130, digit = 80)
        assertThat(cut(page)).isNull()
    }

    @Test fun `text that is not a time is not the clock`() {
        val page = Page().apply { lines[0] = lines[0].copy(text = "1430") }
        assertThat(cut(page)).isNull()
    }

    /** 读不出时间的图：没有一行字，或者时间那块是纯色（OCR 读错了地方）。 */
    @Test fun `no clock no cut`() {
        assertThat(StatusBar.find(emptyList(), Page().pixels).bottom).isNull()
        val blank = Page().apply { rect(0, 0, w, bar, WHITE) }
        assertThat(cut(blank)).isNull()
    }

    // ---------- 合成的聊天截图 ----------

    /**
     * 生成脚本画的聊天页，状态栏 50 高（540 宽），和顶栏一个颜色、没有分界：按估的裁，正好 50。
     * Discord 和不是聊天的那几页，状态栏上只画了时间、没画电池：不裁。
     */
    @Test fun `synthetic chat screenshots lose their status bars`() {
        ChatFixtures.all.forEach { fx ->
            val found = StatusBar.find(fx.lines, fx.pixels)
            val bare = fx.name.startsWith("neg") || fx.name.contains("discord")
            assertWithMessage("${fx.name}: ${found.notes}").that(found.bottom).isEqualTo(if (bare) null else 50)
        }
    }

    private fun cut(page: Page): Int? = StatusBar.find(page.lines, page.pixels).bottom

    /**
     * 合成的一页：顶上 [bar] 高的状态栏（[barColor]），底下是页面（[pageColor]），页面上 70～94 行有一个返回箭头和一行标题。
     * 状态栏左边 [clockX] 起是时间「14:30」：几块 [digit] 高的墨，字心在第 [clockY] 行；[icons] 时右边是信号和电池。
     * 尺寸按 [scale] 放大（1 是 540 宽）。
     */
    private class Page(
        val w: Int = 540,
        val h: Int = 1170,
        val scale: Int = 1,
        val bar: Int = 50 * scale,
        val barColor: Int = WHITE,
        val pageColor: Int = WHITE,
        val ink: Int = BLACK,
        val clockX: Int = 30 * scale,
        val clockY: Int = bar / 2,
        val digit: Int = 14 * scale,
        val icons: Boolean = true,
    ) {
        val argb = IntArray(w * h) { pageColor }
        val lines = ArrayList<ChatLine>()
        val pixels get() = Pixels(w, h, argb)

        init {
            rect(0, 0, w, bar, barColor)
            rect(25 * scale, 70 * scale, 36 * scale, 94 * scale, ink)
            rect(200 * scale, 75 * scale, 340 * scale, 90 * scale, ink)
            drawStatusBar()
        }

        /** 时间、图标（画在已经涂好的底上）。 */
        fun drawStatusBar() {
            val top = clockY - digit / 2
            val s = scale
            // 「14:30」：四个数字各 8 宽、中间一个冒号
            listOf(0, 11, 28, 39).forEach { dx -> rect(clockX + dx * s, top, clockX + (dx + 8) * s, top + digit, ink) }
            rect(clockX + 22 * s, top + 3 * s, clockX + 25 * s, top + 6 * s, ink)
            rect(clockX + 22 * s, top + digit - 6 * s, clockX + 25 * s, top + digit - 3 * s, ink)
            lines.add(0, ChatLine(Box(clockX - 2f * s, top - 3f * s, clockX + 49f * s, top + digit + 3f * s), "14:30"))
            if (!icons) return
            val tall = digit * 6 / 5
            val iconTop = clockY - tall / 2
            // 信号：四根一根比一根高；电池：一圈框
            (0 until 4).forEach { i -> rect(w - (100 - i * 6) * s, iconTop + tall - tall * (i + 1) / 4, w - (96 - i * 6) * s, iconTop + tall, ink) }
            rect(w - 60 * s, iconTop, w - 30 * s, iconTop + tall, ink)
            rect(w - 58 * s, iconTop + 2 * s, w - 32 * s, iconTop + tall - 2 * s, barColor)
        }

        fun rect(l: Int, t: Int, r: Int, b: Int, c: Int) {
            for (y in t.coerceAtLeast(0) until b.coerceAtMost(h)) for (x in l.coerceAtLeast(0) until r.coerceAtMost(w)) argb[y * w + x] = c
        }

        /** 两头是半圆的长条（[r] 不含，[b] 不含）。 */
        fun pill(l: Int, t: Int, r: Int, b: Int, c: Int) {
            val radius = (b - t) / 2f
            val cy = (t + b) / 2f
            for (y in t until b) for (x in l until r) {
                val px = x + 0.5f
                val py = y + 0.5f
                val nearest = px.coerceIn(l + radius, r - radius)
                val dx = px - nearest
                val dy = py - cy
                if (dx * dx + dy * dy <= radius * radius) argb[y * w + x] = c
            }
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val BLUE = 0xFF1976D2.toInt()
        const val DARK_GREY = 0xFF1F1F1F.toInt()
        const val DARK_BLUE = 0xFF1E3264.toInt()

        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
    }
}
