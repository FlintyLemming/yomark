package moe.flinty.yomark.ui.components

import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import moe.flinty.yomark.ui.batch.BatchThumbnails
import moe.flinty.yomark.ui.batch.FilmstripGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/**
 * 批量里在各张图之间挑的胶片条，仿 iOS「照片」看大图时底下那一条（排法见 [FilmstripGeometry]）。
 *
 * 要能一张一张挑得准，不只是扫得快（画布上单指拖是画框，不能像看图那样左右划着翻）：
 * - 点哪张就换哪张。窄条不到 48 dp，点击区域由 Compose 补到 48 dp，缝隙里点下去算离得近的那张；
 * - 轻划一下正好走一张，用力甩才连跳几张（见 [SnapFling]）；按住拖着可以一路扫过去，慢慢松手停在最近的那张；
 * - 手指拖着、甩出去还在滑时，每经过一张轻震一下，像旋钮的档位，手上数得清走了几张。
 *
 * 停在哪张，画布上就换成哪张——甩出去的那一下就知道会停在哪，不等它滑完。
 * 滑动时格子里的图比格子走得慢一点（视差），停在中间的那张露出完整的样子。
 *
 * @param uris 批量里的各张图，按顺序
 * @param selected 画布上是第几张。外面换了（如「去看看」跳到没看过的那张），条带跟着滑过去
 * @param onSelect 停在了第几张
 */
@Composable
fun BatchFilmstrip(
    uris: List<Uri>,
    selected: Int,
    onSelect: (Int) -> Unit,
    thumbnails: BatchThumbnails,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val geometry = remember(density) {
        with(density) { FilmstripGeometry(COLLAPSED.toPx(), GAP.toPx(), MARGIN.toPx()) }
    }
    val state = remember(uris) { FilmstripState(selected, uris.size, geometry.pitch) }
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestSelected by rememberUpdatedState(selected)
    val decay = rememberSplineBasedDecay<Float>()
    val nudge = with(density) { NUDGE_VELOCITY.toPx() }
    val throwAt = with(density) { THROW_VELOCITY.toPx() }
    val fling = remember(state, decay, nudge, throwAt) {
        SnapFling(state, decay, nudge, throwAt, start = { latestSelected }) { latestOnSelect(it) }
    }
    val scope = rememberCoroutineScope()
    val drags = remember { MutableInteractionSource() }
    val haptics = LocalHapticFeedback.current

    // 外面换了画布上那一张，条带滑过去。正在滑的时候不抢：甩出去那一下已经把落点告诉了外面，换的就是它
    LaunchedEffect(state, selected) {
        if (!state.scrollable.isScrollInProgress && state.nearest != selected) state.animateTo(selected)
    }
    // 档位：手指拖着、或者甩出去还在滑的时候，每经过一张轻震一下。编辑器自己滑过去（「去看看」、点了一张）时不震
    LaunchedEffect(state, drags) {
        drags.interactions.collect { if (it is DragInteraction.Start) state.touched = true }
    }
    LaunchedEffect(state, haptics) {
        snapshotFlow { state.nearest }
            .drop(1)
            .collect { if (state.touched) haptics.performHapticFeedback(DETENT) }
    }
    // 兜底：不论怎么停下来的，停住时要对齐到一张上，中间那张就是画布上那张
    LaunchedEffect(state) {
        snapshotFlow { state.scrollable.isScrollInProgress }
            .filter { !it }
            .collect {
                state.touched = false
                val nearest = state.nearest
                if (abs(state.position - nearest) > 0.01f) state.animateTo(nearest)
                if (nearest != latestSelected) latestOnSelect(nearest)
            }
    }

    val heightPx = with(density) { HEIGHT.roundToPx() }
    val parallaxPx = with(density) { PARALLAX.toPx() }
    fun expandedWidth(i: Int): Float {
        val aspect = state.aspects[i] ?: DEFAULT_ASPECT
        return with(density) { (heightPx * aspect).coerceIn(EXPANDED_MIN.toPx(), EXPANDED_MAX.toPx()) }
    }
    val placeholder = MaterialTheme.colorScheme.surfaceVariant

    Layout(
        modifier = modifier
            .height(HEIGHT)
            .clipToBounds()
            .scrollable(state.scrollable, Orientation.Horizontal, flingBehavior = fling, interactionSource = drags)
            .fadingEdges(with(density) { FADE.toPx() }),
        content = {
            uris.forEachIndexed { i, uri ->
                val bitmap by produceState(thumbnails.cached(uri), uri) {
                    if (value == null) value = thumbnails.load(uri)
                }
                LaunchedEffect(bitmap) {
                    bitmap?.let { state.aspects[i] = it.width.toFloat() / it.height }
                }
                val current = i == selected
                val description = stringResource(R.string.filmstrip_item, i + 1, uris.size)
                Box(
                    Modifier
                        .clip(RoundedCornerShape(CORNER))
                        .background(placeholder)
                        .semantics {
                            contentDescription = description
                            this.selected = current
                        }
                        .clickable {
                            // 点了就换，不等条带滑完：外面换了，条带自己会滑过去（见上）
                            if (i != latestSelected) latestOnSelect(i)
                            else scope.launch { state.animateTo(i) }
                        }
                        .drawBehind {
                            val image = bitmap ?: return@drawBehind
                            val position = state.position           // 在绘制里读：滑动时只重画，不重组
                            val t = geometry.expansion(i, position)
                            val half = state.viewportPx / 2f
                            val offset = geometry.centerOffset(i, position, uris.size, ::expandedWidth)
                            val window = FilmstripGeometry.window(
                                imageWidth = image.width,
                                imageHeight = image.height,
                                cellWidth = size.width,
                                cellHeight = size.height,
                                parallax = parallaxPx * (1f - t),
                                shift = if (half > 0f) offset / half else 0f,
                            )
                            val srcW = window.width.roundToInt().coerceIn(1, image.width)
                            val srcH = window.height.roundToInt().coerceIn(1, image.height)
                            drawImage(
                                image,
                                srcOffset = IntOffset(
                                    window.left.roundToInt().coerceIn(0, image.width - srcW),
                                    window.top.roundToInt().coerceIn(0, image.height - srcH),
                                ),
                                srcSize = IntSize(srcW, srcH),
                                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            )
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val position = state.position                              // 在量的时候读：滑动时只重新摆，不重组
        state.viewportPx = width.toFloat()
        val center = width / 2f
        val reach = center + EXPANDED_MAX.toPx()
        val placed = measurables.mapIndexedNotNull { i, measurable ->
            val offset = geometry.centerOffset(i, position, measurables.size, ::expandedWidth)
            if (abs(offset) > reach) return@mapIndexedNotNull null   // 远在条带外的不量不摆，也就不画
            val w = geometry.width(i, position, expandedWidth(i)).roundToInt()
            measurable.measure(Constraints.fixed(w, heightPx)) to (center + offset - w / 2f).roundToInt()
        }
        layout(width, heightPx) {
            placed.forEach { (placeable, x) -> placeable.place(x, 0) }
        }
    }
}

/**
 * 条带滑到了哪：[position] 是停在中线上的是第几张，可以是小数。拖动按窄条的步长换算，
 * 手指挪过一个窄条加一道缝，就挪过一张。
 */
@Stable
private class FilmstripState(initial: Int, val count: Int, val pitchPx: Float) {
    var position by mutableFloatStateOf(initial.coerceIn(0, (count - 1).coerceAtLeast(0)).toFloat())
        private set

    /**
     * 条带有多宽，量的时候记下来，画视差时用。窗口变宽变窄（转屏、分屏）时格子不一定跟着重新量，靠读它重画。
     */
    var viewportPx by mutableFloatStateOf(0f)

    /** 各张图的宽高比，缩略图解出来后才知道。没解出来的按手机截图的比例算。 */
    val aspects = mutableStateMapOf<Int, Float>()

    val nearest: Int get() = position.roundToInt()

    /** 这一回滑动是手指起的头（拖、甩），不是编辑器自己滑过去的。只有这时经过一张才震。 */
    var touched = false

    val scrollable = ScrollableState { delta ->
        val before = position
        position = (position - delta / pitchPx).coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat())
        (before - position) * pitchPx
    }

    suspend fun animateTo(index: Int) {
        scrollable.scroll { settle(index, initialVelocity = 0f) }
    }

    /**
     * 从眼下的位置滑到第 [index] 张停住。用不回弹的弹簧接住甩出去的速度；滑到两头就地停下，最后正好对齐到那一张上
     * （不留零点几的误差：下一次轻划要从这张起算）。
     */
    suspend fun ScrollScope.settle(index: Int, initialVelocity: Float) {
        val distance = (position - index) * pitchPx
        var last = 0f
        val anim = AnimationState(initialValue = 0f, initialVelocity = initialVelocity)
        anim.animateTo(distance, spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)) {
            val delta = value - last
            val consumed = scrollBy(delta)
            last = value
            if (abs(consumed - delta) > 0.5f) cancelAnimation()
        }
        position = index.toFloat()
    }
}

/**
 * 松手后停在哪一张。一定下来就告诉外面，画布不必等条带滑完。
 *
 * - 慢慢松手（慢于 [nudge]）：停在离得最近的那张，拖到哪就是哪；
 * - 轻划一下：从起手那张（[start]，画布上那张）往划的方向正好走一张，手指多划出去一点也不多走——
 *   要的是「下一张」，不是看手指划了几毫米。拖过 [SHORT_DRAG] 张以上才松手的，算是在扫，停在拖到的那里；
 * - 用力甩（快于 [throwAt]）：才连跳几张，超出 [throwAt] 的那部分速度按惯性滑，甩得越用力走得越远。
 *   从「走一张」到「连跳几张」是接上的，不会刚过门槛就一下飞出去十几张。
 *
 * 速度为正是手指往右，往前面的图走（position 变小）。
 */
private class SnapFling(
    private val state: FilmstripState,
    private val decay: DecayAnimationSpec<Float>,
    private val nudge: Float,
    private val throwAt: Float,
    private val start: () -> Int,
    private val onTarget: (Int) -> Unit,
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val target = target(initialVelocity).coerceIn(0, (state.count - 1).coerceAtLeast(0))
        onTarget(target)
        with(state) { settle(target, initialVelocity) }
        return 0f
    }

    private fun target(velocity: Float): Int {
        val here = state.position
        val speed = abs(velocity)
        if (speed < nudge) return here.roundToInt()
        val dir = if (velocity < 0f) 1 else -1
        val from = start()
        val step = when {
            abs(here - from) >= SHORT_DRAG -> here.roundToInt()
            (here - from) * dir < -0.5f -> from                     // 拖过去又往回甩：回到起手那张
            else -> from + dir
        }
        if (speed < throwAt) return step
        val travel = decay.calculateTargetValue(0f, sign(velocity) * (speed - throwAt))
        val far = (here - travel / state.pitchPx).roundToInt()
        return if (dir > 0) max(step, far) else min(step, far)
    }
}

/** 两头淡出，不一刀切齐：条带是滑出去的，不是到头了。 */
private fun Modifier.fadingEdges(fadePx: Float): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (size.width <= 2 * fadePx) return@drawWithContent
        val edge = fadePx / size.width
        drawRect(
            Brush.horizontalGradient(
                0f to Color.Transparent,
                edge to Color.Black,
                1f - edge to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

private val HEIGHT = 40.dp
private val COLLAPSED = 28.dp
private val GAP = 2.dp
private val MARGIN = 6.dp
private val EXPANDED_MIN = 40.dp
private val EXPANDED_MAX = 72.dp
private val PARALLAX = 12.dp
private val CORNER = 4.dp
private val FADE = 12.dp

/** 每秒。松手时比它慢，就停在离得最近的那一张。 */
private val NUDGE_VELOCITY = 400.dp

/** 每秒。比它快才连跳几张；介于两者之间是轻划，正好走一张。 */
private val THROW_VELOCITY = 2000.dp

/** 手指拖过不到这么多张就松手算「轻划」，从起手那张走一张；拖得更远算是在扫，停在拖到的地方。 */
private const val SHORT_DRAG = 2f

/**
 * 经过一张时的那一下。Android 14 起有专为「在一长串选项里挪动」准备的轻震，连着来也不扎手；
 * 之前的系统不认它，用拖文字光标的那一下代替，同样很轻。大爆炸里划过一个字也是这一下。
 */
internal val DETENT: HapticFeedbackType =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackType.SegmentFrequentTick
    else HapticFeedbackType.TextHandleMove

/** 缩略图还没解出来时，按这个宽高比展开（常见的手机截图）。 */
private const val DEFAULT_ASPECT = 9f / 19.5f
