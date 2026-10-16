package moe.flinty.yomark.ui.onboarding

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.flow.first
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.flinty.yomark.R
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.components.contentFoldSpec
import moe.flinty.yomark.ui.components.contentFoldTween
import moe.flinty.yomark.ui.components.foldTween
import moe.flinty.yomark.ui.settings.FooterText
import moe.flinty.yomark.ui.settings.SectionHeader
import moe.flinty.yomark.ui.settings.SettingsGroup
import moe.flinty.yomark.ui.settings.SwitchRow
import kotlin.math.max
import kotlin.math.min

/**
 * 第一次打开时的三步引导，随时可以跳过：
 * 1. 自动打码：样图上演一遍编辑器的识别动效，码落下来（码是现成的，不现场识别，见 [TourSample]）；
 * 2. 点文字打码：框出一段没遮住的链接，点一下就抹上码；
 * 3. 设置里还有更多：实验室里「识别聊天记录」的真开关，打开了图上的标题、头像跟着遮住。
 *
 * 三步是左右排开的三页，点「下一步」、左右划都是整页平移；页里从上到下一层比一层走得快（视差），
 * 图走得最慢、最底下的走得最快，翻页时拉开、停下时对齐。三页看的是同一张图上的同一份码：
 * 前一页点上的、点掉的，翻过去还在。返回键回上一页，第一页时交给系统。
 *
 * @param chatOn 实验室里的「识别聊天记录」眼下开着没有（设置里存的那一份）
 * @param onChatChange 第三步的开关拨了：写进设置
 * @param interactive false 时正在退场淡出：不接触摸、读屏看不见它、返回键不归它
 * @param animate false 时（系统关了动画）不演识别动效，一进来码就在图上
 */
@Composable
internal fun OnboardingTour(
    sample: SourceImage,
    scanStyle: ScanStyle,
    chatOn: Boolean,
    onChatChange: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onPickImage: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    animate: Boolean = remember { ValueAnimator.areAnimatorsEnabled() },
) {
    val state = rememberTourState(chatOn, animate)
    val pager = rememberPagerState(initialPage = state.progress.step) { TourState.STEPS }
    val scope = rememberCoroutineScope()
    // 翻到哪一页记进进度（转屏、进程被杀后接着来）
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { state.goTo(it) } }
    // 第一页一动（点了「下一步」或手指划开）识别动效就收尾、码当场落下：下一页的图上不再从头演一遍
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage != 0 || pager.currentPageOffsetFraction != 0f }.first { it }
        state.reveal()
    }
    // 翻到一半转了屏：页码和翻过的那一截都存着，回来时停在两页中间，接着翻完
    LaunchedEffect(pager) {
        if (!pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f) {
            pager.animateScrollToPage(pager.currentPage, animationSpec = tween(PAGE_MS, easing = PAGE_EASING))
        }
    }
    val target = pager.targetPage
    val last = target == TourState.LAST_STEP
    val turn = { page: Int ->
        if (page != target) {
            if (page > 0) state.reveal()
            scope.launch { pager.animateScrollToPage(page, animationSpec = tween(PAGE_MS, easing = PAGE_EASING)) }
        }
    }
    // 最后一页的按钮要等这一页停稳才算数：快点两下「下一步」，第二下落在正滑进来的最后一页上，不能当成「选择图片」走掉
    val landed = { pager.settledPage == TourState.LAST_STEP && !pager.isScrollInProgress }

    // 入场：卡片淡入、从略小长到原大，说明和按钮慢一拍往上浮出来。只在要演识别动效时演，转屏回来不再演
    val entering = remember { animate && !state.progress.revealed }
    val stageIn = remember { Animatable(if (entering) 0f else 1f) }
    val panelIn = remember { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!entering) return@LaunchedEffect
        launch { stageIn.animateTo(1f, foldTween(true)) }
        panelIn.animateTo(1f, foldTween(true, delayMillis = PANEL_DELAY_MS))
    }

    // 识别动效：和编辑器打开一张图一样，一进来就在识别（码已经备好、藏在磨砂底下），到点落下来，
    // 落码那一遍演完才点得动。翻页翻得早，码当场落下
    LaunchedEffect(state) {
        if (state.settled) return@LaunchedEffect
        var scanned = 0L
        if (!state.progress.revealed) {
            val start = withFrameMillis { it }
            while (!state.progress.revealed && scanned < SCAN_MS) scanned = withFrameMillis { it } - start
            state.reveal()
        }
        awaitMillis(settleMs(scanStyle, scanned) + SETTLE_MARGIN_MS)
        state.settled = true
    }

    // 聊天的码等这一页停稳再一处处抹上：翻页途中抹，抹到一半就滑走了
    LaunchedEffect(chatOn) { state.syncChat(chatOn, animate) { !pager.isScrollInProgress } }

    BackHandler(enabled = interactive && target > 0) { turn(target - 1) }

    val titles = listOf(
        stringResource(R.string.onboarding_auto_title),
        stringResource(R.string.onboarding_tap_title),
        stringResource(R.string.onboarding_more_title),
    )
    val bodies = listOf(
        stringResource(R.string.onboarding_auto_body),
        stringResource(R.string.onboarding_tap_body),
        stringResource(R.string.onboarding_more_body),
    )
    val risen = Modifier.graphicsLayer {
        alpha = panelIn.value
        translationY = (1f - panelIn.value) * ENTER_RISE.toPx()
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // 不透明：退场淡出时是溶进底下的首页，不是两屏叠在一起
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .then(if (interactive) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        val side = sideBySide(maxWidth, maxHeight)
        val geometry = if (side) SideGeometry.of(maxWidth, maxHeight) else null
        // 屏幕很矮（分屏、小外屏）时说明至少留这么高，图再小也让它
        val panelCap = max((maxHeight - TOP_BAR_HEIGHT - BOTTOM_BAR_HEIGHT - MIN_STAGE - STAGE_PAD_V * 2).value, MIN_PANEL.value).dp
        val pageWidth = constraints.maxWidth.toFloat()
        // 从右往左排的语言里下一页在左边：多走的那一截也要反过来
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = interactive,
            key = { it },
        ) { page ->
            // 这一层比整页多走多少（见 PARALLAX）：离开时往外多走，进来时落在后面，停下时为 0
            val layer = { depth: Int ->
                Modifier.graphicsLayer { translationX = direction * pageOffset(pager, page) * pageWidth * PARALLAX[depth] }
            }
            val stage = @Composable { m: Modifier ->
                TourStage(
                    state, sample, scanStyle,
                    page = page,
                    showRing = page == 1 && pager.settledPage == 1 && state.settled && !state.progress.tapped,
                    animate = animate,
                    enter = if (page == 0) ({ stageIn.value }) else ({ 1f }),
                    modifier = m.then(layer(0)),
                )
            }
            val panel = @Composable { m: Modifier ->
                Box(m.then(if (page == 0) risen else Modifier)) {
                    // 前两页的说明占一样高（垫一份看不见的另一页），图在两页上一样大、一样高，平移时不上下跳
                    if (page < 2) {
                        Panel(titles[1 - page], bodies[1 - page], { Modifier }, Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {}) {
                            if (page == 0) TapHint(tapped = false)
                        }
                    }
                    Panel(titles[page], bodies[page], layer) {
                        when (page) {
                            1 -> TapHint(state.progress.tapped)
                            2 -> LabsCard(chatOn, onChatChange)
                        }
                    }
                }
            }
            if (geometry != null) {
                // 横屏、平板横着、折叠屏展开：图在左，说明在右；顶上一行、底下按钮叠在说明那一栏上下，不跟着翻
                Row(Modifier.fillMaxSize().padding(start = geometry.start)) {
                    Box(Modifier.width(geometry.stage).fillMaxHeight().padding(vertical = SIDE_PAD), Alignment.Center) {
                        stage(Modifier)
                    }
                    Spacer(Modifier.width(SIDE_GAP))
                    Column(Modifier.width(geometry.text).fillMaxHeight()) {
                        Spacer(Modifier.height(TOP_BAR_HEIGHT))
                        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.CenterStart) {
                            panel(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp))
                        }
                        Spacer(Modifier.height(BOTTOM_BAR_HEIGHT))
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(TOP_BAR_HEIGHT))
                    Box(
                        Modifier.weight(1f).fillMaxWidth().padding(horizontal = STAGE_PAD_H, vertical = STAGE_PAD_V),
                        Alignment.Center,
                    ) {
                        stage(Modifier)
                    }
                    // 字放得很大、屏幕又矮时，图至少留这么高，说明自己滚
                    panel(
                        Modifier
                            .widthIn(max = COLUMN_TEXT_MAX)
                            .fillMaxWidth()
                            .heightIn(max = panelCap)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Spacer(Modifier.height(BOTTOM_BAR_HEIGHT))
                }
            }
        }

        // 并排时叠在说明那一栏上，竖排时占满一行（按钮那一行和说明一样最宽 COLUMN_TEXT_MAX、居中）
        val textColumn = geometry?.let { Modifier.offset(x = it.start + it.stage + SIDE_GAP).width(it.text) }
        TopBar(
            target,
            titles[target],
            onSkip,
            (textColumn?.let { Modifier.align(Alignment.TopStart).then(it) } ?: Modifier.align(Alignment.TopCenter).fillMaxWidth())
                .padding(start = 24.dp),
        )
        BottomBar(
            last,
            onNext = { turn(target + 1) },
            onPickImage = { if (landed()) onPickImage() },
            onOpenSettings = { if (landed()) onOpenSettings() },
            modifier = (textColumn?.let { Modifier.align(Alignment.BottomStart).then(it) }
                ?: Modifier.align(Alignment.BottomCenter).widthIn(max = COLUMN_TEXT_MAX).fillMaxWidth())
                .then(risen),
        )
        if (!interactive) Box(Modifier.matchParentSize().pointerInput(Unit) { swallowTouches() })
    }
}

/** 第 [page] 页离停着的位置几页远：右边为正。正停在这一页时是 0。 */
private fun pageOffset(pager: PagerState, page: Int): Float =
    (page - pager.currentPage) - pager.currentPageOffsetFraction

/**
 * 图并排放（横着）还是上下放：哪样图大用哪样。图的比例是定的，要比的只有它能多高。
 * 近乎方的折叠屏展开时按宽高比判断会摆错，按图能多大判断就不会。
 */
private fun sideBySide(width: Dp, height: Dp): Boolean {
    val column = min(
        (height - TOP_BAR_HEIGHT - BOTTOM_BAR_HEIGHT - PANEL_ESTIMATE - STAGE_PAD_V * 2).value,
        (width - STAGE_PAD_H * 2).value / RATIO,
    )
    val room = (width - SIDE_PAD * 2 - SIDE_GAP - SIDE_TEXT_MIN).value
    if (room <= 0f) return false
    val side = min((height - SIDE_PAD * 2).value, room / RATIO)
    return side > column
}

/** 并排时各块多宽、整体从哪儿起（居中）。顶上一行、底下按钮按它叠到说明那一栏上。 */
private class SideGeometry(val start: Dp, val stage: Dp, val text: Dp) {
    companion object {
        fun of(width: Dp, height: Dp): SideGeometry {
            val stage = min(((height - SIDE_PAD * 2).value * RATIO), (width - SIDE_PAD * 2 - SIDE_GAP - SIDE_TEXT_MIN).value)
                .coerceAtLeast(0f).dp
            val text = min(SIDE_TEXT_MAX.value, (width - SIDE_PAD * 2 - SIDE_GAP - stage).value).dp
            return SideGeometry(start = (width - stage - SIDE_GAP - text) / 2, stage = stage, text = text)
        }
    }
}

/** 顶上一行：几步走到哪儿了，右边是「跳过」。读屏念「第 2 步，共 3 步：点文字打码」，换步时也念。 */
@Composable
private fun TopBar(step: Int, title: String, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(TOP_BAR_HEIGHT)
            .padding(end = 12.dp)
            // 画在翻页的上面，读屏却会按左边先排到页里的内容：让它先念第几步、「跳过」
            .semantics { isTraversalGroup = true; traversalIndex = -1f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(R.string.onboarding_step, step + 1, TourState.STEPS, title)
        Row(
            Modifier.clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(DOT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(TourState.STEPS) { i ->
                val on = i == step
                val width by animateDpAsState(if (on) DOT_ACTIVE else DOT, foldTween(on), label = "dotWidth")
                val color by animateColorAsState(
                    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    foldTween(on),
                    label = "dotColor",
                )
                Box(Modifier.size(width, DOT).background(color, CircleShape))
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSkip) { Text(stringResource(R.string.onboarding_skip)) }
    }
}

/** 底下一行：前两步是「下一步」；最后一步换成「选择图片」，左边多一个「打开设置」。 */
@Composable
private fun BottomBar(
    last: Boolean,
    onNext: () -> Unit,
    onPickImage: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.height(BOTTOM_BAR_HEIGHT).padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = last,
            enter = fadeIn(foldTween(true)),
            exit = fadeOut(contentFoldTween(false)),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            TextButton(onClick = onOpenSettings) {
                Text(stringResource(R.string.onboarding_open_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Button(onClick = if (last) onPickImage else onNext) {
            AnimatedContent(
                targetState = last,
                // 两个字宽不一样：从中间换，换的途中不被按钮的圆边切掉
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    fadeIn(foldTween(targetState)) togetherWith fadeOut(contentFoldTween(false)) using
                        SizeTransform(clip = false) { _, _ -> foldTween(targetState) }
                },
                label = "primaryLabel",
            ) { isLast ->
                Text(stringResource(if (isLast) R.string.pick_image else R.string.onboarding_next), maxLines = 1)
            }
        }
    }
}

/**
 * 一页的说明：标题一行，下面一两句；[extra] 是这一页另外要摆的（第二页的提示、第三页的开关）。
 * 标题、正文、另外那块各是视差里的一层（[layer] 1、2、3）。
 */
@Composable
private fun Panel(
    title: String,
    body: String,
    layer: (Int) -> Modifier,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = layer(1).semantics { heading() },
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = layer(2).padding(top = 8.dp),
        )
        Column(layer(3)) { extra() }
    }
}

/** 第二页指着图上框出的链接；点上过一段字就淡掉（位置留着，图不跳）。 */
@Composable
private fun TapHint(tapped: Boolean) {
    val alpha by animateFloatAsState(if (tapped) 0f else 1f, contentFoldSpec(false), label = "tapHint")
    Row(
        Modifier
            .padding(top = 16.dp)
            .graphicsLayer { this.alpha = alpha }
            .then(if (tapped) Modifier.clearAndSetSemantics {} else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.onboarding_tap_try),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 第三页：实验室页上的那一项原样搬过来，开关是真的，拨了就写进设置；底下的说明也是实验室页上那句。
 * 设置页的行画在 surfaceBright 上，要放在 surfaceContainer 的底子上才看得出来，和设置页一样。
 */
@Composable
private fun LabsCard(chatOn: Boolean, onChatChange: (Boolean) -> Unit) {
    Column(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(LABS_CARD_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 8.dp),
    ) {
        SectionHeader("${stringResource(R.string.settings)} › ${stringResource(R.string.settings_labs)}", first = true)
        SettingsGroup {
            SwitchRow(
                title = stringResource(R.string.settings_chat),
                checked = chatOn,
                onCheckedChange = onChatChange,
                icon = Icons.Outlined.Forum,
            )
        }
        FooterText(stringResource(R.string.chat_footer))
    }
}

/** 退场淡出时盖在最上面：这一下手指落在哪儿都不算。 */
private suspend fun PointerInputScope.swallowTouches() = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false).consume()
    do {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
    } while (event.changes.any { it.pressed })
}

/** 样图的宽高比。 */
private const val RATIO = TourSample.WIDTH / TourSample.HEIGHT.toFloat()

/**
 * 视差：页里从上到下各层比整页多走几成（图、标题、正文、另外那块）。离开时往外多走、进来时落在后面，
 * 越往下拉得越开，停下时一齐对齐。
 */
private val PARALLAX = floatArrayOf(0f, 0.18f, 0.34f, 0.5f)

/** 点「下一步」、返回键翻一页：整页宽的一段路，比面板展开（350 ms）长一点，同一条 Material 3 标准曲线。 */
private const val PAGE_MS = 450
private val PAGE_EASING = CubicBezierEasing(0.2f, 0f, 0f, 1f)

private val TOP_BAR_HEIGHT = 56.dp

/** 底下按钮那一行（按钮 40 dp，上下各留 16）。每页底下空出这么高给它。 */
private val BOTTOM_BAR_HEIGHT = 72.dp

/** 前两页的说明大约多高：挑横竖排时用，真摆的时候按量出来的高。 */
private val PANEL_ESTIMATE = 160.dp

/** 竖排时图至少留这么高。 */
private val MIN_STAGE = 200.dp

/** 屏幕再矮，说明也至少留这么高（标题一行、正文两行），超出的自己滚。 */
private val MIN_PANEL = 120.dp

private val STAGE_PAD_H = 24.dp
private val STAGE_PAD_V = 8.dp

/** 竖排（手机竖着、平板竖着）时说明最宽多少，再宽就居中。 */
private val COLUMN_TEXT_MAX = 560.dp

/** 并排时说明那一栏的宽。 */
private val SIDE_TEXT_MIN = 360.dp
private val SIDE_TEXT_MAX = 440.dp
private val SIDE_PAD = 16.dp
private val SIDE_GAP = 32.dp

private val DOT = 6.dp
private val DOT_ACTIVE = 18.dp

private val LABS_CARD_RADIUS = 28.dp

/** 入场时说明从下面浮上来这么远，比图慢这么一拍。 */
private val ENTER_RISE = 24.dp
private const val PANEL_DELAY_MS = 60
