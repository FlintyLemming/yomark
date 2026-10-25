package moe.flinty.yomark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.R
import moe.flinty.yomark.ui.BigBangState
import moe.flinty.yomark.ui.bigbang.BigBangPage
import moe.flinty.yomark.ui.bigbang.Glyph
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 要长出来的大爆炸面板：开着的大爆炸，和它的字里已经被别的码盖住的（BigBangPage.covered）。 */
class BigBangPanelModel(val bang: BigBangState, val covered: Set<Int>)

/** 面板上的动作，都交给 ViewModel（见 EditorScreen）。 */
interface BigBangActions {
    fun onToggle(id: Int) {}
    fun onSweepStart(id: Int) {}
    fun onSweep(ids: List<Int>) {}
    fun onClose() {}
}

/**
 * 大爆炸（2026-10-16，见 ui/bigbang）的面板里的东西：这张图上识别出的字一个个摆成字块，点一下就打码（图上马上盖上），再点一下拿掉；
 * 横着划过去一串都打上（从打了码的字划起是一串都拿掉），竖着划是滚动。选中的连着的字连成一整条，和图上的码一个样。
 * 别的码盖着的字是一块灰。右上角「完成」收起，打上的码留着。
 *
 * 手机上面板是底栏的胶囊从「工具」长出来的那一块（见 EditorBottomBar）；宽屏上从大爆炸那一块方块长出来，盖满方块和列表
 * （见 WideEditorPanel）。图跟着让开，选字时看得到图上选中的是哪几块。
 * 顶上一行是名字、选了几个字和右边的「完成」，底下是字块（放不下在里面滚动），面板只有字块那么高。
 * 不另设「打码」：点字就是打码。
 */
@Composable
internal fun BigBangContent(
    bang: BigBangState,
    covered: Set<Int>,
    actions: BigBangActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 4.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.big_bang_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.big_bang_count, bang.selected.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions::onClose) { Text(stringResource(R.string.done)) }
        }
        val scroll = rememberScrollState()
        Box(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(scroll)
                .padding(horizontal = 16.dp, vertical = 4.dp),
        ) {
            GlyphField(bang.page, bang.selected, covered, actions, bang.focusLine, scroll)
        }
    }
}

/**
 * 字块：每一行字从新的一排起，排不下折到下一排（[layoutChips]）。整块自己画，不是一个字一个控件：
 * 长截图上上千个字，一个字一个 Text 打开面板要卡一下。点、划按 [ChipLayout] 里记的格子找字。
 * 面板打开时滚到长按的那一行（[focusLine]）。
 */
@Composable
internal fun GlyphField(
    page: BigBangPage,
    selected: Set<Int>,
    covered: Set<Int>,
    actions: BigBangActions,
    focusLine: Int,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.titleMedium
    val density = LocalDensity.current
    // 同一个字只量一次：中文页上常用字反复出现
    val texts = remember(page, style, density, measurer) {
        page.glyphs.map { it.text }.distinct().associateWith { measurer.measure(it, style) }
    }
    val metrics = remember(density) { ChipMetrics.of(density) }
    val scheme = MaterialTheme.colorScheme
    // 已经打了码的是一块灰：和选中的（主题色）差在深浅上，不只差在颜色上，系统取色是单色的时候也分得开
    val colors = remember(scheme) {
        ChipColors(
            idle = scheme.surfaceContainerHighest, onIdle = scheme.onSurface,
            covered = scheme.onSurface.copy(alpha = COVERED_ALPHA), onCovered = scheme.surface,
            selected = scheme.primary, onSelected = scheme.onPrimary,
        )
    }
    val haptics = LocalHapticFeedback.current
    val latestActions by rememberUpdatedState(actions)
    val description = remember(page) { page.lines.joinToString("\n") { line -> line.glyphs.joinToString("") { it.text } } }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.toFloat()
        val layout = remember(page, width, metrics, texts) {
            layoutChips(page, { texts.getValue(it.text).size.width.toFloat() }, width, metrics)
        }
        LaunchedEffect(page, focusLine) {
            // 长按的那一行上面留一排，看得出它前面还有字
            val top = layout.lineTops.getOrNull(focusLine) ?: return@LaunchedEffect
            scroll.scrollTo((top - metrics.cellHeight - metrics.rowGap).roundToInt().coerceAtLeast(0))
        }
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(with(density) { layout.height.toDp() })
                .semantics { contentDescription = description }
                .pointerInput(layout) { glyphGestures(layout, { latestActions }, haptics, metrics.sweepStep) },
        ) {
            // 只画露在面板里的那几排（上下各多画一排，盖住字块那一块上面的留白）：长截图上几千个字，
            // 每选一个字都全画一遍要掉帧。读的是滚动的位置，滚动时只重画，不重组
            val margin = metrics.cellHeight + metrics.lineGap
            val top = scroll.value - margin
            drawChips(page, layout, selected, covered, texts, colors, metrics, top, top + scroll.viewportSize + margin * 2)
        }
    }
}

/** 字块的尺寸，像素。 */
internal class ChipMetrics(
    val cellHeight: Float,
    val minCellWidth: Float,
    val padX: Float,
    /** 同一个词里两个字之间。 */
    val chipGap: Float,
    /** 隔着空格的两个字之间。 */
    val spaceGap: Float,
    /** 连不上的两段之间（BigBangPage.joins 为 false：并排的按钮、左右两栏）。 */
    val farGap: Float,
    /** 一行字折成几排时排与排之间。 */
    val rowGap: Float,
    /** 两行字之间。 */
    val lineGap: Float,
    /** 隔得远的两行（BigBangLine.paragraphBefore）之间再多空这么多。 */
    val paragraphGap: Float,
    val radius: Float,
    /** 划字时每隔多远看一次手指下是哪个字。 */
    val sweepStep: Float,
) {
    companion object {
        fun of(density: Density) = with(density) {
            ChipMetrics(
                cellHeight = 40.dp.toPx(), minCellWidth = 34.dp.toPx(), padX = 8.dp.toPx(),
                chipGap = 3.dp.toPx(), spaceGap = 10.dp.toPx(), farGap = 22.dp.toPx(),
                rowGap = 4.dp.toPx(), lineGap = 10.dp.toPx(), paragraphGap = 12.dp.toPx(),
                radius = 8.dp.toPx(), sweepStep = 6.dp.toPx(),
            )
        }
    }
}

/**
 * 摆好的字块。[cells] 按 Glyph.id，[lineTops] 是每一行字第一排的上沿，[height] 是整块的高。
 */
internal class ChipLayout(val cells: List<Rect>, val lineTops: List<Float>, val height: Float, private val slackX: Float, private val slackY: Float) {

    /** 下沿在 [y] 以下的第一个字块（没有就是字块的个数）。字块按排从上往下排，二分找。 */
    fun firstBelow(y: Float): Int {
        var lo = 0
        var hi = cells.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cells[mid].bottom < y) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** [p] 上的字块；落在两块之间的缝里算离得近的那块（缝的一半归一边）。 */
    fun hit(p: Offset): Int? {
        val i = cells.indexOfFirst { p.x >= it.left - slackX && p.x <= it.right + slackX && p.y >= it.top - slackY && p.y <= it.bottom + slackY }
        return i.takeIf { it >= 0 }
    }

    /** 从 [from] 划到 [to] 经过的字块，按经过的先后，不重复。每隔 [step] 看一次。 */
    fun along(from: Offset, to: Offset, step: Float): List<Int> {
        val length = hypot(to.x - from.x, to.y - from.y)
        val n = max(1, ceil(length / step.coerceAtLeast(1f)).toInt())
        val out = LinkedHashSet<Int>()
        for (k in 0..n) {
            val t = k.toFloat() / n
            hit(Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))?.let { out += it }
        }
        return out.toList()
    }
}

/**
 * 字块怎么摆：每一行字从新的一排起，一个字一块，排不下折到下一排。字和字之间的空隙照它们在图上的样子：
 * 同一个词里挨着，隔着空格的空一点，连不上的两段（BigBangPage.joins）空得更多，隔得远的两行之间也多空一点。
 * 字块宽是字宽加两边的留白，不窄于 [ChipMetrics.minCellWidth]，手指点得中。
 *
 * @param textWidth 一个字画出来多宽（像素）
 */
internal fun layoutChips(page: BigBangPage, textWidth: (Glyph) -> Float, maxWidth: Float, m: ChipMetrics): ChipLayout {
    val cells = ArrayList<Rect>(page.glyphs.size)
    val tops = ArrayList<Float>(page.lines.size)
    var y = 0f
    page.lines.forEachIndexed { li, line ->
        if (li > 0) y += m.cellHeight + m.lineGap + if (line.paragraphBefore) m.paragraphGap else 0f
        tops += y
        var x = 0f
        line.glyphs.forEachIndexed { k, g ->
            val w = min(max(m.minCellWidth, textWidth(g) + m.padX * 2), maxWidth)
            if (k > 0) {
                val gap = when {
                    !page.joinsNext(g.id - 1) -> m.farGap
                    g.spaceBefore -> m.spaceGap
                    else -> m.chipGap
                }
                if (x + gap + w > maxWidth) {
                    x = 0f
                    y += m.cellHeight + m.rowGap
                } else {
                    x += gap
                }
            }
            cells += Rect(x, y, x + w, y + m.cellHeight)
            x += w
        }
    }
    val height = if (page.lines.isEmpty()) 0f else y + m.cellHeight
    return ChipLayout(cells, tops, height, m.chipGap / 2, m.rowGap / 2)
}

internal class ChipColors(
    val idle: Color, val onIdle: Color,
    val covered: Color, val onCovered: Color,
    val selected: Color, val onSelected: Color,
)

/**
 * 画字块，只画上沿在 [bottom] 以上、下沿在 [top] 以下的那几排。选中的、连得上的（BigBangPage.joinsNext）、
 * 又在同一排上的几个字画成一整条：打出来的码也是一整块。
 */
private fun DrawScope.drawChips(
    page: BigBangPage,
    layout: ChipLayout,
    selected: Set<Int>,
    covered: Set<Int>,
    texts: Map<String, TextLayoutResult>,
    colors: ChipColors,
    m: ChipMetrics,
    top: Float,
    bottom: Float,
) {
    val glyphs = page.glyphs
    val corner = CornerRadius(m.radius)
    val from = layout.firstBelow(top)
    var end = from
    while (end < glyphs.size && layout.cells[end].top <= bottom) end++
    var i = from
    while (i < end) {
        val cell = layout.cells[i]
        if (i in selected) {
            var j = i
            while (j + 1 < glyphs.size && (j + 1) in selected && page.joinsNext(j) && layout.cells[j + 1].top == cell.top) j++
            drawRoundRect(colors.selected, Offset(cell.left, cell.top), Size(layout.cells[j].right - cell.left, cell.height), corner)
            i = j + 1
        } else {
            drawRoundRect(if (i in covered) colors.covered else colors.idle, Offset(cell.left, cell.top), cell.size, corner)
            i++
        }
    }
    for (k in from until end) {
        val g = glyphs[k]
        val text = texts[g.text] ?: continue
        val cell = layout.cells[k]
        val color = when (k) {
            in selected -> colors.onSelected
            in covered -> colors.onCovered
            else -> colors.onIdle
        }
        drawText(text, color, Offset(cell.center.x - text.size.width / 2f, cell.center.y - text.size.height / 2f))
    }
}

/**
 * 字块上的手势：点一下是点那个字；横着划起来是划字（从第一个经过的字定是选还是取消，见 BigBangSelection.sweep），
 * 划起来以后往哪儿走都接着划，跨排、跨行都行；竖着划起来的不管，交给外面的滚动。
 */
private suspend fun PointerInputScope.glyphGestures(
    layout: ChipLayout,
    actions: () -> BigBangActions,
    haptics: HapticFeedback,
    step: Float,
) = awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    // 外面的滚动还在惯性滑着时，按下去是让它停下（滚动先拿走了这一下），不是点字
    if (down.isConsumed) return@awaitEachGesture
    val first = layout.hit(down.position)
    var sweeping = false
    var started = false
    var from = down.position
    val swept = HashSet<Int>()
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.count { it.pressed } > 1) break
        val change = event.changes.firstOrNull { it.id == down.id } ?: break
        if (!change.pressed) {
            if (!sweeping && first != null) actions().onToggle(first)
            break
        }
        if (!sweeping) {
            val d = change.position - down.position
            if (d.getDistance() <= viewConfiguration.touchSlop) continue
            if (abs(d.x) <= abs(d.y)) break
            sweeping = true
        }
        val hits = layout.along(from, change.position, step)
        from = change.position
        if (hits.isNotEmpty()) {
            if (!started) {
                started = true
                actions().onSweepStart(hits.first())
            }
            actions().onSweep(hits)
            if (swept.addAll(hits)) haptics.performHapticFeedback(DETENT)
        }
        change.consume()
    }
}

/**
 * 高度最多占给它的 [fraction]：字少的时候只有字块那么高，画布留得多。分屏里很矮的窗口上 [fraction] 不够摆下名字、两排字块和按钮，
 * 就至少给 [MIN_PANEL]（不超过给它的整个高度）：字块挤成一条缝就没法选了。
 */
private fun Modifier.capHeight(fraction: Float): Modifier = layout { measurable, constraints ->
    val cap = if (!constraints.hasBoundedHeight) constraints.maxHeight else {
        maxOf((constraints.maxHeight * fraction).roundToInt(), MIN_PANEL.roundToPx()).coerceAtMost(constraints.maxHeight)
    }
    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = cap))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * 手机上从底栏的胶囊长出来的大爆炸面板多高（见 EditorBottomBar）：最多占工具那一行上面的一半，上面那一半留给图，
 * 看得到选中的是哪几块。
 */
internal fun Modifier.phoneBigBangHeight(): Modifier = capHeight(PHONE_FRACTION)

private const val PHONE_FRACTION = 0.5f

/** 面板至少这么高：名字那一行、两排字块、按钮那一行（见 [capHeight]）。 */
private val MIN_PANEL = 220.dp

/** 已经打了码的字块的底：正文颜色的这么多，深浅落在没选的和主题色的选中之间（深色界面里三者的深浅整个倒过来）。 */
private const val COVERED_ALPHA = 0.38f
