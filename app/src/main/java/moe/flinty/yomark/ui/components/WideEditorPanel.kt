package moe.flinty.yomark.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import moe.flinty.yomark.R
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskStyleInfo
import moe.flinty.yomark.ui.AiReview
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 宽屏版式里画布旁边的那一块（宽屏增补设计，2026-10-16 改）：所有功能平铺成一块块圆角方块，下面是框列表。
 *
 * 方块是手机底栏里的全部功能，一样不少，只是不收进「打码」「编辑」「工具」几组里：宽屏放得下，都平铺出来，一眼看得到。
 * 三个一行：色块、马赛克、模糊，马克笔、画笔、文本，水印、AI 增强识别（用得了 AI 时）、大爆炸，按住对比。
 * 每一块和手机底栏的圆钮一个样子：一圈细边、很淡的投影（见 floatingSurface）；选中的那一块垫一层主题色、
 * 小样子和名字着主题色、名字加粗，和手机上展开后选中的那一项一样，换选中的也是那 250 ms。
 * 导出在顶栏右上角，和手机一样；原先这一块最底下「AI 增强识别」「导出」那一行没有了，批量时那儿只剩胶片条。
 *
 * 和手机的样式栏是同一份状态、同一套动作：点一种换样式，再点当前那一种才长出或收起调节面板；
 * 点水印长出用途水印的面板，再点一下收起；点大爆炸长出它的面板，面板右上角「完成」收起。
 *
 * 面板从点的那一块方块长出来，收起时缩回那一块，和手机上面板从胶囊长出来、缩回去是同一套动画：
 * 横着走 foldSpec，竖着走 panelFoldSpec（收回去竖着先落回来，角走一道弧），面板上的东西走 contentFoldSpec，先淡掉再缩。
 * 不像手机那样从一条共用的胶囊长出来：每一块是自己的一块。
 * - 样式、水印的面板落在列表的位置：面板从那一块方块底下钻出来（那一块画在它上面），走过别的方块时从它们上面盖过去，
 *   落定后不碰方块，方块都看得到，开着面板也能换样式；
 *   手机横着拿没有列表（还有列表那一截矮得落不下面板的时候），面板落满整一栏，盖住方块，换样式要先收起；
 * - 大爆炸的面板落满整块，方块和列表都盖住：选字时要地方，开着时也不该再去点别的功能（和原先顶替整块一样）。
 * - 盖住方块的那几种从方块上面长出来，那一块上的小样子和名字原地淡掉，和手机上点开一组时那一组淡掉一样；
 *   盖住的就盖满，不在底下留一截空着。
 * 面板落到的那一块（列表，或者方块）跟着淡掉，开着时点不着，读屏也读不到。
 * 开着一个面板又要开另一个：停稳了、又落在同一个地方的（样式、水印两种面板都落在列表的位置）就地换；
 * 别的（换成大爆炸，或者还在半路上）先缩回原来那一块，缩到底再从新的那一块长出来，不从半路上跳过去。
 * 图在旁边看得见，所以和手机不一样：点面板外面不收起，吸管取色时面板不缩回去，打字时面板不收成只剩输入框。
 *
 * 竖着拿（[atSide] 为 false）在画布下面：左半边方块（铺满那一半的高）、右半边列表，样式面板落在右半边；
 * 面板比这一块高时这一块一点点往上长，画布跟着变矮，方块停在原来的高度。
 * 横着拿（[atSide] 为 true）挪到画布右边：方块在上、列表在下。
 *
 * @param list 框列表，传进来的 Modifier 定好了它的位置和大小。手机横着拿时为 null
 * @param aiOffered 这台设备上 AI 增强识别用得了（见 EditorUiState.aiOffered）：留着 AI 那一块，这张图还没识别完时它灰着，
 *   方块的行数不跟着识别一会儿多一会儿少
 * @param bigBang 开着的大爆炸；不开着（或者长按的手指还没抬，见 BigBangState.holding）是 null
 * @param compare 「按住对比」的按压状态：按住时画布显示打码前的原图（见 EditorScreen）。null 时没有这一块
 * @param canCompare 有前后可比：打了码，或者设了用途水印。没有时「按住对比」灰掉
 * @param filmstrip 批量时的胶片条，在这一块最底下。单张时为 null
 */
@Composable
fun WideEditorPanel(
    atSide: Boolean,
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    list: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
    purpose: PurposePanelModel = PurposePanelModel(),
    purposeActions: PurposePanelActions = object : PurposePanelActions {},
    aiReview: AiReview = AiReview.Hidden,
    aiOffered: Boolean = false,
    onAiReview: () -> Unit = {},
    bigBangReady: Boolean = false,
    onBigBang: () -> Unit = {},
    bigBang: BigBangPanelModel? = null,
    bigBangActions: BigBangActions = object : BigBangActions {},
    compare: MutableInteractionSource? = null,
    canCompare: Boolean = false,
    filmstrip: (@Composable (Modifier) -> Unit)? = null,
) {
    // 要开的是哪一个面板、从哪一块长出来
    val request = when {
        bigBang != null -> WidePanelRequest(WidePanelKind.BIG_BANG, WideTileId.BigBang)
        purpose.open -> WidePanelRequest(WidePanelKind.PURPOSE, WideTileId.Watermark)
        styleBar.panelOpen -> WidePanelRequest(WidePanelKind.STYLE, WideTileId.Style(styleBar.look.style))
        else -> null
    }
    // 眼下画着的那一个：收回去的半路上几样都已经关了，它还要接着画完、缩回它那一块
    val last = remember { LastWidePanel(request) }
    bigBang?.let { last.bang = it }
    // 和手机的面板一样：横着、竖着各走各的，面板上的东西收回去时先淡掉（见 EditorBottomBar）。开着面板转屏时停在开着的样子
    val across = remember { Animatable(if (request != null) 1f else 0f) }
    val down = remember { Animatable(if (request != null) 1f else 0f) }
    val contentIn = remember { Animatable(if (request != null) 1f else 0f) }
    val shut by remember { derivedStateOf { across.value == 0f && down.value == 0f } }
    val standing by remember { derivedStateOf { across.value == 1f && down.value == 1f } }
    val shown = last.request
    if (request != null && request != shown) {
        val inPlace = shown != null && standing && shown.kind != WidePanelKind.BIG_BANG && request.kind != WidePanelKind.BIG_BANG
        if (shown == null || shut || inPlace) last.request = request
    }
    val wanted = request != null && request == last.request
    LaunchedEffect(wanted) {
        val target = if (wanted) 1f else 0f
        launch { across.animateTo(target, foldSpec(wanted)) }
        launch { down.animateTo(target, panelFoldSpec(wanted)) }
        launch { contentIn.animateTo(target, contentFoldSpec(wanted)) }
    }
    val panelShown = wanted || !shut
    val kind = last.request?.kind ?: WidePanelKind.STYLE
    val origin = last.request?.origin
    // 列表那一截矮得落不下面板时（横着拿的阔折叠、平板上弹着输入法）面板也落满整一栏。量的时候才知道，量完写回来
    val landsOver = remember { mutableStateOf(false) }
    // 落满整一栏、整块的面板盖住方块：大爆炸，和手机横着拿（没有列表）时的样式、水印面板
    val coversTiles = kind == WidePanelKind.BIG_BANG || list == null || landsOver.value
    val tiles = wideTiles(
        styleBar, styleActions, purpose, purposeActions, aiReview, aiOffered, onAiReview,
        bigBangReady, onBigBang, bigBangOpen = bigBang != null, compare, canCompare,
    )
    // 换选中的那 250 ms 放在外面：盖住方块的面板长出来时，原地淡掉的那一份和那一块走的是同一份
    val looks = tiles.associate { tile -> tile.id to key(tile.id) { rememberTileLook(tile.selected) } }
    val container = MaterialTheme.colorScheme.surfaceContainer
    val edge = MaterialTheme.colorScheme.outlineVariant.copy(alpha = EDGE_ALPHA)
    val corner = remember { MorphCorner() }

    Layout(
        content = {
            tiles.forEach { tile ->
                key(tile.id) {
                    TileButton(
                        tile,
                        looks.getValue(tile.id),
                        Modifier.layoutId(tile.id).then(if (wanted && coversTiles) Modifier.inert() else Modifier),
                    )
                }
            }
            list?.invoke(Modifier.layoutId(WidePart.LIST).then(if (wanted) Modifier.inert() else Modifier))
            filmstrip?.invoke(Modifier.layoutId(WidePart.FILM))
            if (panelShown) {
                // 面板上没有控件的地方也接住手指：落不到底下的列表、方块上去
                Box(
                    Modifier
                        .layoutId(WidePart.SURFACE)
                        .floatingSurface(container, edge) { corner.px }
                        .pointerInput(Unit) { swallowTouches() },
                )
                val bang = last.bang
                if (kind == WidePanelKind.BIG_BANG && bang != null) {
                    // 字块自己滚动，名字那一行不跟着滚，不放进 PanelColumn
                    BigBangContent(bang.bang, bang.covered, bigBangActions, Modifier.layoutId(WidePart.CONTENT))
                } else {
                    // 两种面板换着开时外面这一列不换，跟着里面的东西一点点变高变矮
                    PanelColumn(Modifier.layoutId(WidePart.CONTENT)) {
                        if (kind == WidePanelKind.PURPOSE) PurposePanelContent(purpose, purposeActions)
                        else StylePanelContent(styleBar, styleActions)
                    }
                }
                if (coversTiles) {
                    tiles.firstOrNull { it.id == origin }?.let { TileGhost(it, looks.getValue(it.id), Modifier.layoutId(WidePart.GHOST)) }
                }
            }
        },
        modifier = modifier.then(if (atSide) Modifier.wideSidePanelWidth() else Modifier),
    ) { measurables, constraints ->
        val tileParts = measurables.filter { it.layoutId is WideTileId }
        val part = { id: WidePart -> measurables.firstOrNull { it.layoutId == id } }
        val count = tileParts.size
        val gap = GAP.roundToPx()
        val floor = TILE_FLOOR.roundToPx()
        val width = constraints.maxWidth
        val filmGap = FILM_GAP.roundToPx()
        fun rowsOf(columns: Int) = (count + columns - 1) / columns
        fun gridHeight(rows: Int, tileH: Int) = rows * tileH + (rows - 1).coerceAtLeast(0) * gap

        // 方块、列表、面板落到的那一块，按这一块自己的坐标
        val geometry = if (atSide) {
            val height = constraints.maxHeight
            val left = SIDE_PAD_START.roundToPx()
            val right = width - SIDE_PAD_END.roundToPx()
            val top = SIDE_PAD_V.roundToPx()
            val innerW = (right - left).coerceAtLeast(0)
            val film = part(WidePart.FILM)?.measure(Constraints.fixedWidth(innerW))
            val bottom = height - SIDE_PAD_V.roundToPx() - (film?.let { it.height + filmGap } ?: 0)
            val room = (bottom - top).coerceAtLeast(0)
            val listGap = LIST_GAP.roundToPx()
            val minLanding = MIN_LANDING.roundToPx()
            // 没有列表（手机横着拿）时这一栏矮：三个一行压到 TILE_FLOOR 还放不下，就四个一行
            val columns = if (list == null && gridHeight(rowsOf(COLUMNS), floor) > room) WIDE_COLUMNS else COLUMNS
            val rows = rowsOf(columns)
            val tileW = ((innerW - gap * (columns - 1)) / columns).coerceAtLeast(0)
            val natural = (tileW * SIDE_ASPECT).roundToInt().coerceIn(TILE_MIN.roundToPx(), TILE_MAX.roundToPx())
            fun fit(space: Int) = ((space - gap * (rows - 1)) / rows.coerceAtLeast(1)).coerceAtLeast(0)
            var tileH = if (list == null) min(natural, max(fit(room), floor)) else {
                // 有列表时给面板留出 MIN_LANDING 落脚：方块压一压就留得出就压，压到 TILE_FLOOR 也留不出就照原样，面板落满整一栏
                val squeezed = fit(room - listGap - minLanding)
                when {
                    room - listGap - gridHeight(rows, natural) >= minLanding -> natural
                    squeezed >= floor -> squeezed
                    else -> natural
                }
            }
            // 怎么也不出这一栏，不压到胶片条底下
            tileH = min(tileH, fit(room))
            val gridBottom = top + gridHeight(rows, tileH)
            val listArea = IntRect(left, min(gridBottom + listGap, bottom), right, bottom)
            val whole = IntRect(left, top, right, bottom)
            val over = list != null && listArea.height < minLanding
            WideGeometry(
                width = width,
                restHeight = height,
                openHeight = height,
                tiles = gridRects(count, columns, left, top, tileW, tileH, gap),
                list = listArea,
                styleArea = if (list != null && !over) listArea else whole,
                bangArea = whole,
                landsOver = over,
                coveredBottom = bottom,
                film = film,
                filmY = bottom + filmGap,
                filmX = left,
            )
        } else {
            val available = if (constraints.hasBoundedHeight) constraints.maxHeight else Constraints.Infinity
            val padH = BOTTOM_PAD_H.roundToPx()
            val padV = BOTTOM_PAD_V.roundToPx()
            val innerW = (width - padH * 2).coerceAtLeast(0)
            val film = part(WidePart.FILM)?.measure(Constraints.fixedWidth(innerW))
            val filmPart = film?.let { it.height + filmGap } ?: 0
            val rows = rowsOf(COLUMNS)
            // 闲着时这一块至少放得下 TILE_FLOOR 高的方块（阔折叠竖着拿、批量时），只要画布还留得够
            val rest = if (available == Constraints.Infinity) REST_MIN.roundToPx() else {
                val base = wideBottomRest(available)
                max(base, padV * 2 + filmPart + gridHeight(rows, floor))
                    .coerceAtMost((available - MIN_CANVAS.roundToPx()).coerceAtLeast(base))
            }
            val open = if (available == Constraints.Infinity) rest else max(rest, wideBottomOpen(available))
            val halfGap = HALF_GAP.roundToPx()
            val half = ((innerW - halfGap) / 2).coerceAtLeast(0)
            val tileW = ((half - gap * (COLUMNS - 1)) / COLUMNS).coerceAtLeast(0)
            // 方块铺满左半边的高（按闲着时这一块的高，面板长高时停在原处），但不比宽高出 MAX_ASPECT 倍
            val gridRoom = rest - padV * 2 - filmPart
            val tileH = min(
                ((gridRoom - gap * (rows - 1)) / rows.coerceAtLeast(1)).coerceAtLeast(0),
                (tileW * MAX_ASPECT).roundToInt(),
            )
            val top = padV
            val rightLeft = padH + half + halfGap
            WideGeometry(
                width = width,
                restHeight = rest,
                openHeight = open,
                tiles = gridRects(count, COLUMNS, padH, top, tileW, tileH, gap),
                list = IntRect(rightLeft, top, width - padH, rest - padV - filmPart),
                styleArea = IntRect(rightLeft, top, width - padH, open - padV - filmPart),
                bangArea = IntRect(padH, top, width - padH, open - padV - filmPart),
                landsOver = false,
                coveredBottom = rest - padV - filmPart,
                film = film,
                filmY = -1,
                filmX = padH,
                bottomPad = padV,
            )
        }
        if (landsOver.value != geometry.landsOver) landsOver.value = geometry.landsOver
        val covers = kind == WidePanelKind.BIG_BANG || list == null || geometry.landsOver

        val tilePlaceables = tileParts.mapIndexed { i, m ->
            val r = geometry.tiles[i]
            m.measure(Constraints.fixed(r.width, r.height))
        }
        val area = if (kind == WidePanelKind.BIG_BANG) geometry.bangArea else geometry.styleArea
        // 盖住方块的盖满它盖住的那一块（横着拿是整一栏，竖着拿是闲着时的整块），不在底下留一截空着
        val minContent = if (covers) (geometry.coveredBottom - area.top).coerceIn(0, area.height.coerceAtLeast(0)) else 0
        val content = part(WidePart.CONTENT)?.measure(
            Constraints(
                minWidth = area.width.coerceAtLeast(0),
                maxWidth = area.width.coerceAtLeast(0),
                minHeight = minContent,
                maxHeight = area.height.coerceAtLeast(minContent),
            ),
        )
        // 竖着拿时这一块跟着面板长高：面板（连上下的边）比闲着时高才长，长到 openHeight 为止
        val filmPart = geometry.film?.let { it.height + filmGap } ?: 0
        val height = if (atSide) geometry.restHeight else {
            val needed = (content?.height ?: 0) + area.top + geometry.bottomPad + filmPart
            lerp(geometry.restHeight, needed.coerceIn(geometry.restHeight, geometry.openHeight), down.value)
        }
        val listArea = if (atSide) geometry.list else geometry.list.copy(bottom = height - geometry.bottomPad - filmPart)
        val listed = part(WidePart.LIST)?.measure(Constraints.fixed(listArea.width.coerceAtLeast(0), listArea.height.coerceAtLeast(0)))

        val originIndex = tileParts.indexOfFirst { it.layoutId == origin }
        val from = geometry.tiles.getOrNull(originIndex) ?: IntRect(area.left, area.top, area.right, area.top)
        val to = IntRect(area.left, area.top, area.right, area.top + (content?.height ?: 0))
        val px = across.value
        val py = down.value
        val surfaceRect = morphRect(from, to, px, py)
        val tileCorner = min(CORNER_MAX.toPx(), min(from.width, from.height) * CORNER_FRACTION)
        corner.px = lerp(tileCorner, PANEL_CORNER.toPx(), px)
        val surface = part(WidePart.SURFACE)?.measure(
            Constraints.fixed(surfaceRect.width.coerceAtLeast(0), surfaceRect.height.coerceAtLeast(0)),
        )
        val ghost = part(WidePart.GHOST)?.measure(Constraints.fixed(from.width.coerceAtLeast(0), from.height.coerceAtLeast(0)))

        layout(geometry.width, height) {
            // 面板落到的那一块跟着淡掉；开着停稳了就不摆出来
            val fade = if (panelShown) contentIn.value else 0f
            val gone = wanted && fade == 1f
            fun placeTile(i: Int, covered: Boolean) {
                if (covered && gone) return
                val p = tilePlaceables[i]
                val r = geometry.tiles[i]
                // 投影画在方块外面：默认的淡出先画进一块和方块一样大的缓冲，出了边的投影就被切掉，所以逐笔乘上 alpha（同手机的圆钮）
                if (covered) {
                    p.placeWithLayer(r.left, r.top) {
                        alpha = 1f - fade
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    }
                } else {
                    p.place(r.left, r.top)
                }
            }
            fun placePanel() {
                surface?.place(surfaceRect.left, surfaceRect.top)
                content?.placeWithLayer(to.left, to.top) {
                    alpha = span(contentIn.value, CONTENT_IN_START, 1f)
                    clip = true
                    shape = MorphClip(
                        Rect(
                            (surfaceRect.left - to.left).toFloat(),
                            (surfaceRect.top - to.top).toFloat(),
                            (surfaceRect.right - to.left).toFloat(),
                            (surfaceRect.bottom - to.top).toFloat(),
                        ),
                        corner.px,
                    )
                }
            }

            if (!gone) listed?.placeWithLayer(listArea.left, listArea.top) { alpha = 1f - fade }
            if (covers) {
                // 面板从那一块上面长出来，盖住方块；那一块本身让给面板和原地淡掉的那一份，免得两层投影叠在一起
                tilePlaceables.indices.forEach { if (!(panelShown && it == originIndex)) placeTile(it, covered = true) }
                placePanel()
                ghost?.placeWithLayer(from.left, from.top) { alpha = 1f - span(px, 0f, FADE_OUT) }
            } else {
                // 面板从点的那一块底下钻出来（它画在面板上面），走过别的方块时盖住它们，落在列表的位置；
                // 落定后不碰方块，方块都看得到
                tilePlaceables.indices.forEach { if (it != originIndex) placeTile(it, covered = false) }
                placePanel()
                if (originIndex >= 0) placeTile(originIndex, covered = false)
            }
            geometry.film?.let { f ->
                val y = if (geometry.filmY >= 0) geometry.filmY else height - geometry.bottomPad - f.height
                f.place(geometry.filmX, y)
            }
        }
    }
}

/**
 * 面板眼下在哪儿：从那一块方块（[from]）的四条边走到落定的（[to]），左右两条边跟着 [across]、上下两条边跟着 [down]。
 * 打开时两样一条曲线，面板斜着直线长出去；收回去时上下先落回来（见 panelFoldSpec），角走一道弧，和手机上一样。
 */
internal fun morphRect(from: IntRect, to: IntRect, across: Float, down: Float) = IntRect(
    lerp(from.left, to.left, across),
    lerp(from.top, to.top, down),
    lerp(from.right, to.right, across),
    lerp(from.bottom, to.bottom, down),
)

/** 一格格从左往右、从上往下排，[columns] 个一行。 */
private fun gridRects(count: Int, columns: Int, left: Int, top: Int, tileW: Int, tileH: Int, gap: Int) = List(count) { i ->
    val x = left + (i % columns) * (tileW + gap)
    val y = top + (i / columns) * (tileH + gap)
    IntRect(x, y, x + tileW, y + tileH)
}

/**
 * 量好的这一块：方块各在哪儿，列表、面板落到的那一块，胶片条。[restHeight] 是闲着时这一块多高，[openHeight] 是面板最高能撑到多高
 * （横着拿时两样都是这一栏的高）。[landsOver] 是列表那一截落不下样式、水印的面板，它们也落满整一栏；
 * [coveredBottom] 是盖住方块的面板至少盖到哪儿。竖着拿时胶片条贴着这一块的底边（[filmY] 为 -1），跟着这一块长高往下挪。
 */
private class WideGeometry(
    val width: Int,
    val restHeight: Int,
    val openHeight: Int,
    val tiles: List<IntRect>,
    val list: IntRect,
    val styleArea: IntRect,
    val bangArea: IntRect,
    val landsOver: Boolean,
    val coveredBottom: Int,
    val film: Placeable?,
    val filmY: Int,
    val filmX: Int,
    val bottomPad: Int = 0,
)

private fun IntRect.copy(bottom: Int) = IntRect(left, top, right, bottom)

/** 面板眼下的圆角（像素）：从那一块方块的圆角走到面板的。只在量的时候写、画的时候读，不触发重组。 */
private class MorphCorner {
    var px = 0f
}

/** 一个面板：哪一种，从哪一块长出来。 */
private data class WidePanelRequest(val kind: WidePanelKind, val origin: WideTileId)

/**
 * 眼下画着的是哪一个面板（[request]，从没开过是 null）、大爆炸的面板最近一次的样子。
 * 只在组合时写；换面板的时机见 WideEditorPanel。
 */
private class LastWidePanel(var request: WidePanelRequest?) {
    var bang: BigBangPanelModel? = null
}

private enum class WidePanelKind { STYLE, PURPOSE, BIG_BANG }

private enum class WidePart { LIST, FILM, SURFACE, CONTENT, GHOST }

/** 一块方块是哪一样功能。 */
private sealed interface WideTileId {
    data class Style(val style: MaskStyle) : WideTileId
    data object Watermark : WideTileId
    data object Ai : WideTileId
    data object BigBang : WideTileId
    data object Compare : WideTileId
}

/** 面板上的东西只露出面板眼下盖着的那一截：[area] 按它自己的坐标，角和面板一样圆。 */
private class MorphClip(private val area: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = CornerRadius(radius)
        return Outline.Rounded(RoundRect(area, r, r, r, r))
    }
}

/**
 * 面板盖住、淡掉的那一块（列表，或者方块）：开着面板时点不着，读屏也读不到。在最外面一层就把手指接住，里面的行、方块都收不到。
 */
private fun Modifier.inert(): Modifier = clearAndSetSemantics {}.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial).consume()
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

// ---------- 方块 ----------

/**
 * 一块方块要画的东西：方块本身（[TileButton]）和盖住方块的面板长出来时原地淡掉的那一份（[TileGhost]）都用它。
 * @param chevron 名字后面的小箭头：再点一下长出（或收起）面板。只有选中的露出来，[panelOpen] 时朝下
 * @param press 「按住对比」的按压状态，有它时按住才算数（见 [TileButton]）
 */
private class WideTile(
    val id: WideTileId,
    val label: String,
    val selected: Boolean,
    val onClick: () -> Unit,
    val role: Role = Role.RadioButton,
    val chevron: Boolean = false,
    val panelOpen: Boolean = false,
    val enabled: Boolean = true,
    val dimmed: Boolean = !enabled,
    val stateText: String? = null,
    val description: String? = null,
    val press: MutableInteractionSource? = null,
    val glyph: @Composable (ink: Color, scale: Float) -> Unit,
)

/**
 * 一块方块换选中时走的那 250 ms，照手机上展开后的那一项（见 ToolItem）：底色按 easeOut 变，小样子和名字的颜色、粗细起步快。
 * 方块本身和原地淡掉的那一份读的是同一份。
 */
private class TileLook(private val pick: State<Float>, private val tintState: State<Float>) {
    val tint get() = tintState.value

    /** 着上主题色的程度（0～1）。 */
    fun inked(selected: Boolean) = if (selected) FLUTTER_EASE_OUT_CIRC.transform(pick.value) else FLUTTER_EASE_IN_EXPO.transform(pick.value)
}

@Composable
private fun rememberTileLook(selected: Boolean): TileLook {
    val pick = animateFloatAsState(if (selected) 1f else 0f, tween(PICK_MS, easing = LinearEasing), label = "tilePick")
    val tint = animateFloatAsState(if (selected) SELECTED_TINT else 0f, tween(PICK_MS, easing = FLUTTER_EASE_OUT), label = "tileTint")
    return remember(pick, tint) { TileLook(pick, tint) }
}

/**
 * 所有功能，和手机底栏里的一样多，按手机上几组的先后排：打码的三种、编辑的四样、工具里的两样，最后是对比。
 * 选中、灰掉的规矩都和手机上的那一项一样（见 EditorToolbar 的 redactTools、editTools、toolboxTools）。
 */
@Composable
private fun wideTiles(
    styleBar: StyleBarModel,
    styleActions: StyleBarActions,
    purpose: PurposePanelModel,
    purposeActions: PurposePanelActions,
    aiReview: AiReview,
    aiOffered: Boolean,
    onAiReview: () -> Unit,
    bigBangReady: Boolean,
    onBigBang: () -> Unit,
    bigBangOpen: Boolean,
    compare: MutableInteractionSource?,
    canCompare: Boolean,
): List<WideTile> {
    val colors = MaterialTheme.colorScheme
    val look = styleBar.look
    val tiles = ArrayList<WideTile>()
    MaskStyleInfo.ORDER.forEach { style ->
        tiles += WideTile(
            id = WideTileId.Style(style),
            label = stringResource(MaskStyleInfo.label(style)),
            // 用途水印、大爆炸的面板开着时选中的是它们，样式都不算选中
            selected = style == look.style && !purpose.open && !bigBangOpen,
            onClick = { styleActions.onStyleClick(style) },
            chevron = true,
            panelOpen = styleBar.panelOpen,
        ) { ink, scale -> StyleGlyph(style, look.options, styleBar.nearColors, scale, ink) }
    }
    // 设没设只有读屏念（「已开启」）：图上有就看得见。选中就是面板开着
    tiles += WideTile(
        id = WideTileId.Watermark,
        label = stringResource(R.string.tool_watermark),
        selected = purpose.open,
        onClick = purposeActions::onToggle,
        role = Role.Button,
        chevron = true,
        panelOpen = true,
        stateText = if (purpose.on && purpose.text.isNotBlank()) stringResource(R.string.tool_on) else null,
    ) { ink, scale -> Icon(PurposeWatermarkIcon, contentDescription = null, modifier = Modifier.size(GLYPH * scale), tint = ink) }
    if (aiReview != AiReview.Hidden || aiOffered) {
        val failed = aiReview == AiReview.Failed
        // 跑着、跑完时点不动，也不灰掉：结果就写在这一块上，要看得清；没跑成还能点。这张图还没识别完时灰着
        tiles += WideTile(
            id = WideTileId.Ai,
            label = aiReviewLabel(aiReview),
            selected = false,
            onClick = onAiReview,
            role = Role.Button,
            enabled = aiReview == AiReview.Ready || failed,
            dimmed = aiReview == AiReview.Hidden,
        ) { ink, scale ->
            if (aiReview is AiReview.Done) {
                Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(GLYPH * scale), tint = ink)
            } else {
                SparkleIcon(
                    animating = aiReview == AiReview.Running,
                    modifier = Modifier.size(GLYPH * scale),
                    tint = if (failed) colors.error else colors.primary,
                )
            }
        }
    }
    // 还没识别完、没认出字时灰掉
    tiles += WideTile(
        id = WideTileId.BigBang,
        label = stringResource(R.string.big_bang_title),
        selected = bigBangOpen,
        onClick = onBigBang,
        role = Role.Button,
        chevron = true,
        panelOpen = true,
        enabled = bigBangReady || bigBangOpen,
    ) { ink, scale -> Icon(BigBangIcon, contentDescription = null, modifier = Modifier.size(GLYPH * scale), tint = ink) }
    if (compare != null) {
        tiles += WideTile(
            id = WideTileId.Compare,
            label = stringResource(R.string.tool_compare),
            selected = false,
            onClick = {},
            role = Role.Button,
            enabled = canCompare,
            description = stringResource(R.string.compare_hold),
            press = compare,
        ) { ink, scale -> Icon(Icons.Filled.Compare, contentDescription = null, modifier = Modifier.size(GLYPH * scale), tint = ink) }
    }
    return tiles
}

/**
 * 一块方块：上面是这一样的小样子，下面是名字，圆角跟着方块大小走（小方块圆一些，大方块是圆角矩形）。
 * 底子是手机底栏圆钮的那一种（见 floatingSurface）；选中的垫一层主题色（主题色 20%），小样子、名字着主题色，名字加粗，
 * 后面的小箭头说明再点一下就长出（或收起）面板，和手机上展开后选中的那一项一样（见 [TileLook]）。
 * 方块大了，里面的小样子和名字跟着放大。整块都能点，按下去的水波铺满这一块。
 *
 * 「按住对比」（[WideTile.press]）按住时画布换成打码前的原图，松手回来；点一下（没比上）弹个提示说要按住，和手机上那颗圆钮一样。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileButton(tile: WideTile, look: TileLook, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val container = colors.surfaceContainer
    val edge = colors.outlineVariant.copy(alpha = EDGE_ALPHA)
    BoxWithConstraints(modifier) {
        val side = min(maxWidth, maxHeight)
        val shape = RoundedCornerShape(tileCorner(side))
        val hint = rememberTooltipState()
        val scope = rememberCoroutineScope()
        val press = tile.press
        val click = when {
            press != null -> Modifier.combinedClickable(
                interactionSource = press,
                indication = LocalIndication.current,
                enabled = tile.enabled,
                role = tile.role,
                onLongClick = {},
                hapticFeedbackEnabled = false,
                onClick = { scope.launch { hint.show() } },
            )
            tile.role == Role.RadioButton -> Modifier.selectable(selected = tile.selected, enabled = tile.enabled, role = tile.role, onClick = tile.onClick)
            else -> Modifier.clickable(enabled = tile.enabled, role = tile.role, onClick = tile.onClick)
        }
        val body = @Composable {
            Box(
                Modifier
                    .fillMaxSize()
                    .floatingSurface(container, edge) { tileCornerPx(it) }
                    .clip(shape)
                    .background(colors.primary.copy(alpha = look.tint))
                    .semantics {
                        tile.description?.let { contentDescription = it }
                        tile.stateText?.let { stateDescription = it }
                    }
                    .then(click),
                contentAlignment = Alignment.Center,
            ) {
                TileFace(tile, side, look.inked(tile.selected), Modifier.alpha(if (tile.dimmed) DISABLED_ALPHA else 1f))
            }
        }
        if (press != null) {
            // TooltipBox 把 modifier 给了它里面那一层，这一格的大小落在外面的 BoxWithConstraints 上
            TooltipBox(
                positionProvider = rememberHintPosition(),
                tooltip = { PlainTooltip { Text(stringResource(R.string.compare_original)) } },
                state = hint,
                focusable = false,
                enableUserInput = false,
            ) { body() }
        } else {
            body()
        }
    }
}

/**
 * 盖住方块的面板从那一块长出来时原地淡掉的那一份：只有选中的底色和上面的东西，没有底子、点不了（见 WideEditorPanel）。
 * 换选中和那一块读的是同一份 [TileLook]：刚点的那一下它还在变成选中的半路上，不一下跳成选中的样子。
 */
@Composable
private fun TileGhost(tile: WideTile, look: TileLook, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.clearAndSetSemantics {}) {
        val side = min(maxWidth, maxHeight)
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(tileCorner(side)))
                .background(colors.primary.copy(alpha = look.tint)),
            contentAlignment = Alignment.Center,
        ) {
            TileFace(tile, side, look.inked(tile.selected), Modifier.alpha(if (tile.dimmed) DISABLED_ALPHA else 1f))
        }
    }
}

/** 方块上的东西：小样子在上，名字在下，选中的名字后面跟着小箭头。[inked] 是着上主题色的程度（0～1）。 */
@Composable
private fun TileFace(tile: WideTile, side: Dp, inked: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val ink = lerp(colors.onSurfaceVariant, colors.primary, inked)
    val large = side >= LARGE_TILE
    val style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium
    val scale = (side / TILE_MIN).coerceIn(1f, GLYPH_MAX_SCALE)
    Column(modifier.padding(horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(GLYPH_BOX * scale), contentAlignment = Alignment.Center) { tile.glyph(ink, scale) }
        Spacer(Modifier.height(4.dp * scale))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 一般一行就写得下；窄的方块上「识别失败，重试」这类长的折成两行，不把「重试」截掉；方块矮得放不下两行才截断
            Text(
                tile.label,
                style = style,
                color = ink,
                fontWeight = lerp(style.fontWeight ?: FontWeight.Normal, FontWeight.Bold, inked),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (tile.chevron && tile.selected) {
                Icon(
                    if (tile.panelOpen) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                    contentDescription = null,
                    modifier = Modifier.size(if (large) 16.dp else 14.dp),
                    tint = ink,
                )
            }
        }
    }
}

/** 方块的圆角：短边的三成，最多 [CORNER_MAX]。 */
private fun tileCorner(side: Dp): Dp = min(CORNER_MAX, side * CORNER_FRACTION)

private fun Density.tileCornerPx(size: Size): Float = min(CORNER_MAX.toPx(), size.minDimension * CORNER_FRACTION)

// ---------- 尺寸 ----------

/**
 * 横着拿时右边那一栏的宽度：画布能用的宽度的三成，夹在 320～400 dp 之间。
 * 320 时调节面板里的滑条还有一百多 dp 能拖；再宽列表也只是空着，不如留给画布。
 * 手机横着拿时三成不到 320，就是 320。
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
 * 竖着拿时下面那一块闲着时多高（像素）：[available] 是画布和它一起能用的高度，占三成多，夹在 260～380 dp，画布总留至少 [MIN_CANVAS]。
 */
private fun Density.wideBottomRest(available: Int): Int = blockHeight(available, REST_FRACTION, REST_MIN, REST_MAX)

/** 同一块开着面板时最高长到多高：一半上下，340～520 dp，画布总留至少 [MIN_CANVAS]；面板再高在里面滚动。 */
private fun Density.wideBottomOpen(available: Int): Int = blockHeight(available, OPEN_FRACTION, OPEN_MIN, OPEN_MAX)

private fun Density.blockHeight(available: Int, fraction: Float, min: Dp, max: Dp): Int =
    (available * fraction).roundToInt()
        .coerceIn(min.roundToPx(), max.roundToPx())
        .coerceAtMost((available - MIN_CANVAS.roundToPx()).coerceAtLeast(0))

private const val COLUMNS = 3

/** 手机横着拿、矮得三个一行放不下时四个一行。 */
private const val WIDE_COLUMNS = 4

/** 方块之间的空。 */
private val GAP = 12.dp

/** 横着拿时方块的高取宽的四分之三，夹在 [TILE_MIN]～[TILE_MAX]。竖着拿时铺满左半边的高，不比宽高出 [MAX_ASPECT] 倍。 */
private const val SIDE_ASPECT = 0.75f
private const val MAX_ASPECT = 1.25f
private val TILE_MIN = 64.dp
private val TILE_MAX = 96.dp

/** 地方不够时方块一齐压扁，压到这么矮为止（点得中的高）。再矮宁可四个一行，或者把面板落满整一栏。 */
private val TILE_FLOOR = 48.dp

/** 横着拿时列表那一截至少这么高，样式、水印的面板才落在那儿；不够就落满整一栏。 */
private val MIN_LANDING = 200.dp

/** 短边到这么大，名字换大一号的字。 */
private val LARGE_TILE = 88.dp
private val CORNER_MAX = 24.dp
private const val CORNER_FRACTION = 0.3f
private const val GLYPH_MAX_SCALE = 1.6f
private val GLYPH_BOX = 24.dp
private val GLYPH = 20.dp

/** 面板的圆角：原先宽屏面板就是这么圆。从方块长出来时从方块的圆角走到它。 */
private val PANEL_CORNER = 20.dp

/** 方块和下面的列表之间、竖着拿时左右两半之间的空。 */
private val LIST_GAP = 16.dp
private val HALF_GAP = 16.dp
private val FILM_GAP = 8.dp

private val SIDE_PAD_START = 8.dp
private val SIDE_PAD_END = 16.dp
private val SIDE_PAD_V = 8.dp
private val BOTTOM_PAD_H = 16.dp
private val BOTTOM_PAD_V = 8.dp

private const val SIDE_FRACTION = 0.3f
private val SIDE_MIN = 320.dp
private val SIDE_MAX = 400.dp

private const val REST_FRACTION = 0.34f
private val REST_MIN = 260.dp
private val REST_MAX = 380.dp
private const val OPEN_FRACTION = 0.48f
private val OPEN_MIN = 340.dp
private val OPEN_MAX = 520.dp
private val MIN_CANVAS = 200.dp
