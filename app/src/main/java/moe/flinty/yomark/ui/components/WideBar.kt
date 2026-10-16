package moe.flinty.yomark.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 底栏（EditorBottomBar）铺在哪儿。2026-10-16 起宽屏、手机横着拿也是手机的底栏，原先是一块样式方块平铺、底下一行 AI 和导出：
 *
 * - [PHONE]：手机竖着拿，铺满画布那一块，面板往上长盖在图上（见 PanelTop）；
 * - [SIDE]：画布右边那一栏（宽屏横着拿、手机横着拿），框列表在上、工具那一行在最底下，面板从这一行往上长，盖住列表，不碰图；
 * - [BOTTOM]：画布下面那一块（宽屏竖着拿），排法同 [SIDE]，面板比这一块高时这一块跟着长高，画布跟着变矮，图不被盖住。
 *
 * 宽屏上工具那一行不共用一条胶囊：「打码」「编辑」「工具」「对比」各是一块圆角矩形，点哪一块工具栏就从哪一块撑开（见 EditorToolbar 的 tiles）。
 */
enum class BarPlacement {
    PHONE, SIDE, BOTTOM;

    /** 每一样各是一块圆角矩形（宽屏的样子）。 */
    val tiles get() = this != PHONE
}

/**
 * 横着拿时右边那一栏的宽度：画布能用的宽度的三成，夹在 360～400 dp 之间。
 * 至少和常见最窄的手机竖着拿（360 dp）一样宽，手机上那一行放得下的这里也放得下：展开「编辑」时选中的那一项名字写得全，
 * 收着时四块的名字都写得全。中文连名字后面的小箭头也摆得下；英文的名字长，先去掉小箭头（见 EditorToolbar）。
 * 原先是 320（放样式方块时滑条有一百多 dp 能拖就够），换成工具那一行以后，320 上选中的马克笔只剩「马…」（2026-10-16）。
 * 再宽列表也只是空着，不如留给画布。手机横着拿时三成不到 360，就是 360（窗口不够宽的不挪到右边，见 EditorLayouts）。
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

/**
 * 竖着拿的宽屏，画布下面那一块（列表和工具那一行）闲着时多高（像素）：[available] 是画布和它一起能用的高度，
 * 占三成多，夹在 260～380 dp，画布总留至少 [MIN_CANVAS]。
 */
internal fun Density.wideBottomRest(available: Int): Int = height(available, REST_FRACTION, REST_MIN, REST_MAX)

/**
 * 同一块开着面板时最高长到多高（像素）：一半上下，340～520 dp，画布总留至少 [MIN_CANVAS]；面板再高在里面滚动。
 * 面板短（马赛克、模糊只有一根滑条）盖得住列表就不长。
 */
internal fun Density.wideBottomOpen(available: Int): Int = height(available, OPEN_FRACTION, OPEN_MIN, OPEN_MAX)

private fun Density.height(available: Int, fraction: Float, min: Dp, max: Dp): Int =
    (available * fraction).roundToInt()
        .coerceIn(min.roundToPx(), max.roundToPx())
        .coerceAtMost((available - MIN_CANVAS.roundToPx()).coerceAtLeast(0))

/**
 * 竖着拿的宽屏上这一块（列表、胶片条、工具那一行）最宽这么多，在正中：撑开后几样工具摊在八百多 dp 上空得慌，
 * 从它长出来的面板也太宽。列表和它一样宽，面板长上去正好盖住列表，两边不露出半截。
 */
internal val BAR_MAX_WIDTH = 560.dp

private const val SIDE_FRACTION = 0.3f
private val SIDE_MIN = 360.dp
private val SIDE_MAX = 400.dp

private const val REST_FRACTION = 0.34f
private val REST_MIN = 260.dp
private val REST_MAX = 380.dp
private const val OPEN_FRACTION = 0.48f
private val OPEN_MIN = 340.dp
private val OPEN_MAX = 520.dp
private val MIN_CANVAS = 200.dp
