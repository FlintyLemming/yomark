package moe.flinty.yomark.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/**
 * 宽屏版式里画布旁边的那一块（宽屏增补设计）：样式方块、框列表、导出那一行。
 *
 * 竖着拿（[atSide] 为 false）在画布下面：左半边是样式方块、右半边是列表，导出那一行横在最底下，
 * 和手机底栏的导出一个位置。横着拿（[atSide] 为 true）挪到画布右边：样式在上、列表在下，导出在最底下。
 * 样式方块都铺满给它的那一块（见 StyleGrid）。
 *
 * 样式的调节面板弹出来时占列表的位置，收起后列表回来。竖着拿时面板比列表那一块高，这一块就跟着长高，
 * 面板不至于挤成一条缝；画布跟着变矮，和手机上面板把画布顶上去是一回事。
 * 用途水印的面板（顶栏上的水印按钮）也占这个位置，和样式面板一次只开一个。
 *
 * 手机横着拿也用横着的排法，只是没有列表（[list] 为 null）：样式方块在上，导出在最底下。
 * 这一栏矮，方块底下放不下调节面板，面板弹出来时顶替方块，收起后方块回来；画布照样不被盖住。
 *
 * @param list 框列表，传进来的 Modifier 定好了它的位置和大小。手机横着拿时为 null
 * @param actionRow 导出那一行
 */
@Composable
fun WideEditorPanel(
    atSide: Boolean,
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    list: (@Composable (Modifier) -> Unit)?,
    actionRow: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    purpose: PurposePanelModel = PurposePanelModel(),
    purposeActions: PurposePanelActions = object : PurposePanelActions {},
) {
    val open = styleBar.panelOpen || purpose.open

    /** 开着的那一个面板。 */
    @Composable
    fun Panel(modifier: Modifier = Modifier, standalone: Boolean = false) {
        if (purpose.open) PurposePanel(purpose, purposeActions, modifier)
        else StylePanel(styleBar, styleActions, modifier, standalone)
    }

    if (atSide) {
        Column(
            modifier
                .wideSidePanelWidth()
                .fillMaxHeight()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            if (list != null) {
                StyleGrid(styleBar, styleActions, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (open) Panel() else list(Modifier.fillMaxSize())
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (open) {
                        Panel(standalone = true)
                    } else {
                        // 一般放得下；字号调大的矮手机上放不下时，方块和说明在这一块里滚动，不压到导出那一行上
                        StyleGrid(styleBar, styleActions, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()))
                    }
                }
            }
            actionRow(Modifier.padding(top = 8.dp))
        }
    } else {
        // 列表撑满这一块；调节面板只要它自己那么高，比列表那一块高时这一块往上长（见 wideBottomPanelHeight）。
        // 长高、缩回去都是一点点来的，导出那一行始终贴着底边，画布跟着一点点变矮
        val growth by animateFloatAsState(if (open) 1f else 0f, label = "stylePanel")
        val openHeight = remember { OpenHeight() }
        Column(
            modifier
                .fillMaxWidth()
                .wideBottomPanelHeight(open, { growth }, openHeight)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 左右各一半：样式方块铺满左半边，列表（或调节面板）占右半边
            Row(Modifier.weight(1f, fill = !open).fillMaxWidth()) {
                StyleGrid(styleBar, styleActions, Modifier.weight(1f), fillHeight = true, holdHeight = open)
                Spacer(Modifier.width(16.dp))
                if (open) Panel(Modifier.weight(1f))
                else list?.invoke(Modifier.weight(1f).fillMaxHeight())
            }
            actionRow(Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * 横着拿时右边那一栏的宽度：画布能用的宽度的三成，夹在 320～400 dp 之间。
 * 320 时调节面板里的滑条还有一百多 dp 能拖；再宽列表也只是空着，不如留给画布。
 * 手机横着拿时三成不到 320，就是 320。大爆炸的面板在右边时也是这么宽（见 BigBangPanel）。
 */
internal fun Modifier.wideSidePanelWidth(): Modifier = layout { measurable, constraints ->
    val available = constraints.maxWidth
    val target = if (available == Constraints.Infinity) SIDE_MIN.roundToPx() else {
        (available * SIDE_FRACTION).roundToInt()
            .coerceIn(SIDE_MIN.roundToPx(), SIDE_MAX.roundToPx())
            .coerceAtMost(available)
    }
    val placeable = measurable.measure(constraints.copy(minWidth = target, maxWidth = target))
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/** 调节面板开着时这一块最后量出来的高度（像素）。收起时从这儿缩回去；只在量的时候读写，不触发重组。 */
private class OpenHeight {
    var px = 0
}

/**
 * 竖着拿时下面那一块的高度：放列表时占画布能用的高度的三成多，夹在 260～380 dp。
 * 样式的调节面板开着时（[stylePanelOpen]）至少还是这么高，面板更高就跟着长，最多长到一半上下（340～520 dp），
 * 再高面板里面滚动。画布总留至少 [MIN_CANVAS]。
 *
 * 长高的上限随 [growth]（0 收起、1 展开）一点点放开，面板短的话这一块根本不动；收起时从展开时实际的高度缩回去，
 * 而不是从上限缩：短面板收起时这一块不会先蹿到上限再落下来。[growth] 在量的时候才读，动画只重新量、不重组。
 */
private fun Modifier.wideBottomPanelHeight(
    stylePanelOpen: Boolean,
    growth: () -> Float,
    openHeight: OpenHeight,
): Modifier = layout { measurable, constraints ->
    val available = constraints.maxHeight
    val placeable = if (available == Constraints.Infinity) measurable.measure(constraints) else {
        fun height(fraction: Float, min: Dp, max: Dp) =
            (available * fraction).roundToInt().coerceIn(min.roundToPx(), max.roundToPx())
        val cap = (available - MIN_CANVAS.roundToPx()).coerceAtLeast(0)
        val low = height(CLOSED_FRACTION, CLOSED_MIN, CLOSED_MAX).coerceAtMost(cap)
        val high = (if (stylePanelOpen) height(OPEN_FRACTION, OPEN_MIN, OPEN_MAX) else openHeight.px)
            .coerceIn(low, maxOf(low, cap))
        measurable.measure(constraints.copy(minHeight = low, maxHeight = lerp(low, high, growth())))
            .also { if (stylePanelOpen) openHeight.px = it.height }
    }
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

private const val SIDE_FRACTION = 0.3f
private val SIDE_MIN = 320.dp
private val SIDE_MAX = 400.dp

private const val CLOSED_FRACTION = 0.34f
private val CLOSED_MIN = 260.dp
private val CLOSED_MAX = 380.dp
private const val OPEN_FRACTION = 0.48f
private val OPEN_MIN = 340.dp
private val OPEN_MAX = 520.dp
private val MIN_CANVAS = 200.dp
