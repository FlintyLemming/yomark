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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.roundToInt

/**
 * 宽屏版式里画布旁边的那一块（宽屏增补设计）：样式圆钮、框列表、导出那一行。
 *
 * 竖着拿（[atSide] 为 false）在画布下面：左边样式、右边列表，导出那一行横在最底下，和手机底栏的导出一个位置。
 * 横着拿（[atSide] 为 true）挪到画布右边：样式在上、列表在下，导出在最底下。
 *
 * 样式的调节面板弹出来时占列表的位置，收起后列表回来。竖着拿时面板比列表那一块高，这一块就跟着长高，
 * 面板不至于挤成一条缝；画布跟着变矮，和手机上面板把画布顶上去是一回事。
 *
 * @param list 框列表，传进来的 Modifier 定好了它的位置和大小
 * @param actionRow 导出那一行
 */
@Composable
fun WideEditorPanel(
    atSide: Boolean,
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    list: @Composable (Modifier) -> Unit,
    actionRow: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val open = styleBar.panelOpen
    if (atSide) {
        Column(
            modifier
                .wideSidePanelWidth()
                .fillMaxHeight()
                .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        ) {
            StyleGrid(styleBar, styleActions, Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (open) StylePanel(styleBar, styleActions) else list(Modifier.fillMaxSize())
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
            Row(Modifier.weight(1f, fill = !open).fillMaxWidth()) {
                StyleGrid(styleBar, styleActions)
                Spacer(Modifier.width(16.dp))
                if (open) StylePanel(styleBar, styleActions, Modifier.weight(1f))
                else list(Modifier.weight(1f).fillMaxHeight())
            }
            actionRow(Modifier.padding(top = 8.dp))
        }
    }
}

/**
 * 横着拿时右边那一栏的宽度：画布能用的宽度的三成，夹在 320～400 dp 之间。
 * 320 放得下用途水印面板顶上那一排按钮；再宽列表也只是空着，不如留给画布。
 * 用途水印面板在这一栏里时也用它，两者一样宽，切过去不跳。
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
