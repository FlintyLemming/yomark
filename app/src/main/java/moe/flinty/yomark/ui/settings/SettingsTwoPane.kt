package moe.flinty.yomark.ui.settings

import android.content.res.Configuration
import android.os.Parcelable
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.EditorLayouts

/**
 * 设置分不分左右两栏（2026-10-17 起）：宽屏（平板、折叠屏展开、阔折叠，和编辑器同一套认法，见 EditorLayouts.isWide），
 * 窗口还得宽到放得下两栏。阔折叠竖着拿只有五百来 dp，分成两栏两边都太挤，照旧一页一页点进去。
 * 手机横着拿也不分：宽是够了，可上下太矮，两个大标题栏一占，列表剩不下几行。
 */
object SettingsLayouts {

    /** 左栏最窄 280 dp（见 [ListMin]），右栏至少还剩 320 dp，和最窄的手机一样宽。 */
    const val TWO_PANE_MIN_WIDTH_DP = 600

    /** 参数同 EditorLayouts.choose，都取自 Configuration。 */
    fun twoPane(widthDp: Int, heightDp: Int, smallestWidthDp: Int): Boolean =
        widthDp >= TWO_PANE_MIN_WIDTH_DP && EditorLayouts.isWide(widthDp, heightDp, smallestWidthDp)

    fun twoPane(config: Configuration): Boolean =
        twoPane(config.screenWidthDp, config.screenHeightDp, config.smallestScreenWidthDp)
}

/** 设置里的一页：哪一页，文字里的一类另带规则 id。 */
@Parcelize
data class SettingsDestination(val page: SettingsPage, val ruleId: String? = null) : Parcelable {

    /** 打得开：文字里的一类得是现有的规则，改名前留下的 id 就认不出了。 */
    val valid: Boolean get() = page != SettingsPage.TEXT_RULE || RuleCatalog.all.any { it.id == ruleId }

    /** 从一级页上那一项点到这一页，一路经过的页（含这一页）。分栏时它们叠在右栏里。 */
    val chain: List<SettingsDestination>
        get() = page.parent?.let { SettingsDestination(it).chain }.orEmpty() + this

    /** 每一页记着的状态（滚到哪儿了）按它存，见 [SettingsHost]。 */
    internal val key: String get() = if (ruleId == null) page.name else "${page.name}/$ruleId"
}

/** 从哪一页点进来的；一级页上的项是 null。 */
val SettingsPage.parent: SettingsPage?
    get() = when (this) {
        SettingsPage.TEXT_RULE -> SettingsPage.TEXT
        SettingsPage.TEXT_ENGINE -> SettingsPage.AI
        SettingsPage.DEBUG_TRACE -> SettingsPage.DEVELOPER
        else -> null
    }

/**
 * 一级页和点开的页怎么摆（2026-10-17 起）。[opened] 是点开的那一页连同它下面的几页，最后一项在最上面。
 *
 * - 分栏（[twoPane]，见 SettingsLayouts）：左栏是一级页，右栏是点开的那一页，还没点过（[opened] 为 null）就放「文字」。
 *   左栏里右栏正开着的那一项换了底色。右栏里再点进下一页（文字里的一类、识别引擎、调试记录）叠在右栏里，
 *   标题栏左边多一个返回；右栏最底下那一页没有返回，系统返回直接退出设置，跨 Activity 的预见式返回照旧。
 *   左栏点另一项，右栏换成那一页；点正开着的那一项，右栏退回那一项最上面的一页。
 * - 不分栏、点开过页：分栏时点开的页在窗口变窄（折叠屏合上、分屏拖窄）后原地占满整屏，
 *   返回一页一页退回一级页，和手机上一样，只是不再是一个个 Activity。
 * - 不分栏、没点开过：只有一级页，点一项由 [onLaunch] 开一个 SettingsPageActivity，手机上一直是这样。
 *
 * 换页：往深处点、往回退是横向的共享轴（新的一页从后面滑进来，旧的淡出），左栏换一项是先淡出再淡入。
 * 往回退跟着返回手势走（预见式返回）。退掉、换掉的页连同它记着的状态（滚到哪儿了）一起丢掉，
 * 再点开是新的一页，和重开一个 Activity 一样。
 *
 * @param directory 一级页：右栏开着哪一项（不分栏时是 null），点了哪一项。
 * @param page 一页：点进下一页，标题栏左边的返回（null 就不放返回）。
 */
@Composable
internal fun SettingsHost(
    twoPane: Boolean,
    opened: List<SettingsDestination>?,
    onOpenedChange: (List<SettingsDestination>?) -> Unit,
    onLaunch: (SettingsPage) -> Unit,
    directory: @Composable (selected: SettingsPage?, onOpen: (SettingsPage) -> Unit) -> Unit,
    page: @Composable (destination: SettingsDestination, onOpen: (SettingsDestination) -> Unit, onNavigateUp: (() -> Unit)?) -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    if (twoPane) {
        val shown = opened ?: listOf(SettingsDestination(SettingsPage.TEXT))
        val go = { next: List<SettingsDestination> ->
            holder.forget(shown, next)
            onOpenedChange(next)
        }
        TwoPanes(
            list = {
                directory(shown.first().page) { picked ->
                    val next = listOf(SettingsDestination(picked))
                    if (next != opened) go(next)
                }
            },
            detail = {
                PageStack(shown, bottom = 1, go, holder) { stack, onOpen, onNavigateUp ->
                    page(stack.last(), onOpen, onNavigateUp)
                }
            },
        )
    } else {
        // 一级页也放进来（空着的那一层），退回它时同样是共享轴，返回手势拖着也看得到它
        val shown = opened.orEmpty()
        val go = { next: List<SettingsDestination> ->
            holder.forget(shown, next)
            onOpenedChange(next.ifEmpty { null })
        }
        PageStack(shown, bottom = 0, go, holder) { stack, onOpen, onNavigateUp ->
            if (stack.isEmpty()) directory(null, onLaunch) else page(stack.last(), onOpen, onNavigateUp)
        }
    }
}

/** 不分栏时一级页记着的状态存在这个键下；各页的键是 [SettingsDestination.key]，不会撞上。 */
private const val DIRECTORY_KEY = ""

/** 从 [from] 换到 [to] 时，不在 [to] 里的页记着的状态丢掉。 */
private fun SaveableStateHolder.forget(from: List<SettingsDestination>, to: List<SettingsDestination>) {
    from.filterNot { it in to }.forEach { removeState(it.key) }
}

/**
 * 叠起来的几页，只露出最上面那一页。往回退到 [bottom] 层为止：分栏时右栏最底下那一页（1），
 * 不分栏时是一级页（0）。退到底了不拦系统返回，交给 Activity 自己 finish。
 */
@Composable
private fun PageStack(
    stack: List<SettingsDestination>,
    bottom: Int,
    go: (List<SettingsDestination>) -> Unit,
    holder: SaveableStateHolder,
    content: @Composable (stack: List<SettingsDestination>, onOpen: (SettingsDestination) -> Unit, onNavigateUp: (() -> Unit)?) -> Unit,
) {
    val transitionState = remember { SeekableTransitionState(stack) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(stack) { transitionState.animateTo(stack) }
    PredictiveBackHandler(enabled = stack.size > bottom) { progress ->
        val back = stack.dropLast(1)
        try {
            progress.collect { transitionState.seekTo(it.progress, back) }
            go(back)
        } catch (e: CancellationException) {
            // 手势放弃了：这里的协程已经取消，退回原样的动画另起一个
            scope.launch { transitionState.animateTo(stack) }
            throw e
        }
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slide = with(LocalDensity.current) { SlideDistance.roundToPx() }
    rememberTransition(transitionState).AnimatedContent(
        modifier = Modifier.fillMaxSize(),
        transitionSpec = { pageTransform(slide, rtl) },
        contentKey = { it.lastOrNull() },
    ) { shown ->
        holder.SaveableStateProvider(shown.lastOrNull()?.key ?: DIRECTORY_KEY) {
            content(
                shown,
                { next -> go(shown + next) },
                if (shown.size > bottom) ({ go(shown.dropLast(1)) }) else null,
            )
        }
    }
}

/**
 * 往深处点、往回退是共享轴；别的（左栏换了一项）先淡出再淡入。
 *
 * 不跟着内容改大小（SizeTransform 为 null）：页面一直占满这一栏，可真的页在 DataStore 读到之前第一帧是空的，
 * 量出来 0×0；默认的 SizeTransform 会把容器往 0×0 缩，缩小的那一块又被 fillMaxSize 摆在正中，
 * 旧的一页就被拖着往右下角走、边上被裁掉。
 */
private fun AnimatedContentTransitionScope<List<SettingsDestination>>.pageTransform(slide: Int, rtl: Boolean): ContentTransform {
    val from = initialState
    val to = targetState
    val transform = when {
        to.size > from.size && to.take(from.size) == from -> sharedAxis(forward = true, slide, rtl)
        from.size > to.size && from.take(to.size) == to -> sharedAxis(forward = false, slide, rtl)
        else -> fadeThrough()
    }
    return transform using null
}

/**
 * Material 的横向共享轴：新的一页从后面（往深处是右边，往回退是左边，从右往左排的语言反过来）滑进 [slide] 那么远、
 * 前 35% 的时间里旧的一页淡掉，之后新的淡入。
 */
private fun sharedAxis(forward: Boolean, slide: Int, rtl: Boolean): ContentTransform {
    val sign = (if (forward) 1 else -1) * (if (rtl) -1 else 1)
    val fadeOutMs = AXIS_MS * 35 / 100
    return (
        slideInHorizontally(tween(AXIS_MS, easing = AxisEasing)) { sign * slide } +
            fadeIn(tween(AXIS_MS - fadeOutMs, delayMillis = fadeOutMs, easing = LinearOutSlowInEasing))
        ) togetherWith (
        slideOutHorizontally(tween(AXIS_MS, easing = AxisEasing)) { -sign * slide } +
            fadeOut(tween(fadeOutMs, easing = FastOutLinearInEasing))
        )
}

/** Material 的淡出淡入（fade through）：旧的 90 ms 淡掉，新的接着从 92% 大小淡入放大。 */
private fun fadeThrough(): ContentTransform =
    (
        fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing), initialScale = 0.92f)
        ) togetherWith fadeOut(tween(90, easing = FastOutLinearInEasing))

private const val AXIS_MS = 300
private val AxisEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private val SlideDistance = 30.dp

/**
 * 左栏占窗口宽的 40%，夹在 280～360 dp：360 是 Material 列表 + 详情版式里列表那一栏的默认宽，
 * 再宽一级页的行也只是空着；280 放得下图标、标题和两行以内的小字。右栏占剩下的。
 *
 * 两栏各自让开自己那一侧的系统栏、挖孔：左栏不管右边的，右栏不管左边的（在从右往左排的语言里反过来）。
 * 右栏换页时滑动的那一点距离裁在右栏里，不压到左栏上。
 */
@Composable
private fun TwoPanes(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val listWidth = (maxWidth * LIST_FRACTION).coerceIn(ListMin, ListMax)
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .width(listWidth)
                    .fillMaxHeight()
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.End)),
            ) { list() }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .consumeWindowInsets(WindowInsets.safeDrawing.only(WindowInsetsSides.Start)),
            ) { detail() }
        }
    }
}

private const val LIST_FRACTION = 0.4f
private val ListMin = 280.dp
private val ListMax = 360.dp
