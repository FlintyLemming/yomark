package moe.flinty.yomark.ui.onboarding

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import moe.flinty.yomark.R
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.components.contentFoldSpec
import moe.flinty.yomark.ui.components.contentFoldTween
import moe.flinty.yomark.ui.components.foldTween
import moe.flinty.yomark.ui.settings.FooterText
import moe.flinty.yomark.ui.settings.RadioRow
import moe.flinty.yomark.ui.settings.SectionHeader
import moe.flinty.yomark.ui.settings.SettingsGroup
import moe.flinty.yomark.ui.settings.SwitchRow
import moe.flinty.yomark.ui.settings.textEngineLabel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 第一次打开时的引导（见 [TourPage]），随时可以跳过：
 * 1. 欢迎：应用图标、一句话说清是干什么的、两条承诺；
 * 2. 自动打码：样图上演一遍编辑器的识别动效，码落下来（码是现成的，不现场识别，见 [TourSample]）；
 * 3. 点文字打码：框出一段没遮住的链接，点一下就抹上码；
 * 4. 识别引擎：按后台给 PP-OCR 测的速度（见 [EngineProbeModel]）推荐一个、替人选好，说为什么，可以改；
 * 5. 设置里还有更多：实验室里「识别聊天记录」的真开关，打开了图上的标题、头像跟着遮住。
 * 设置里「帮助」重放的没有第 4 页（[engineAdvice] 为 null）。
 *
 * 各页左右排开，点「下一步」、左右划都是整页平移；页里从上到下一层比一层走得快（视差），
 * 图走得最慢、最底下的走得最快，翻页时拉开、停下时对齐。欢迎页以外看的是同一张图上的同一份码：
 * 前一页点上的、点掉的，翻过去还在。返回键回上一页，第一页时交给系统。
 *
 * @param sample 样图（TourSample.load）
 * @param boxes 这张样图上量好的框（TourSample.of），和图出自同一套资源
 * @param chatOn 实验室里的「识别聊天记录」眼下开着没有（设置里存的那一份）
 * @param onChatChange 最后一页的开关拨了：写进设置
 * @param engineAdvice 测速走到哪儿了；null 时没有「识别引擎」那一页
 * @param engine 设置里眼下用的识别引擎
 * @param onEngineChange 「识别引擎」那一页选了一个（替人选好的，或者点的）：写进设置
 * @param interactive false 时正在退场淡出：不接触摸、读屏看不见它、返回键不归它
 * @param animate false 时（系统关了动画）不演识别动效，一进来码就在图上
 */
@Composable
internal fun OnboardingTour(
    sample: SourceImage,
    boxes: TourSample,
    scanStyle: ScanStyle,
    chatOn: Boolean,
    onChatChange: (Boolean) -> Unit,
    onSkip: () -> Unit,
    onPickImage: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    engineAdvice: EngineAdvice? = null,
    engine: TextEngineOption = TextEngineOption.PADDLE,
    onEngineChange: (TextEngineOption) -> Unit = {},
    interactive: Boolean = true,
    animate: Boolean = remember { ValueAnimator.areAnimatorsEnabled() },
) {
    val state = rememberTourState(boxes, chatOn, animate, if (engineAdvice != null) TourState.ALL_PAGES else TourState.WITHOUT_ENGINE)
    val pages = state.pages
    val pager = rememberPagerState(initialPage = state.progress.step) { pages.size }
    val scope = rememberCoroutineScope()
    // 翻到哪一页记进进度（转屏、进程被杀后接着来）
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { state.goTo(it) } }
    // 停在「自动打码」上以后一动（点了「下一步」、返回，或手指划开）识别动效就收尾、码当场落下：下一页的图上不再从头演一遍
    LaunchedEffect(pager) {
        snapshotFlow { state.progress.revealed || pager.settledPage == state.autoStep }.first { it }
        snapshotFlow {
            state.progress.revealed || pager.currentPage != state.autoStep || pager.currentPageOffsetFraction != 0f
        }.first { it }
        state.reveal()
    }
    // 翻到一半转了屏：页码和翻过的那一截都存着，回来时停在两页中间，接着翻完
    LaunchedEffect(pager) {
        if (!pager.isScrollInProgress && pager.currentPageOffsetFraction != 0f) {
            pager.animateScrollToPage(pager.currentPage, animationSpec = tween(PAGE_MS, easing = PAGE_EASING))
        }
    }
    val target = pager.targetPage
    val last = target == state.lastStep
    val turn = { page: Int ->
        if (page != target) {
            if (page > state.autoStep) state.reveal()
            scope.launch { pager.animateScrollToPage(page, animationSpec = tween(PAGE_MS, easing = PAGE_EASING)) }
        }
    }
    // 最后一页的按钮要等这一页停稳才算数：快点两下「下一步」，第二下落在正滑进来的最后一页上，不能当成「选择图片」走掉
    val landed = { pager.settledPage == state.lastStep && !pager.isScrollInProgress }

    // 入场：欢迎页的图标淡入、从略小长到原大，说明和按钮慢一拍往上浮出来。只演一回，转屏回来不再演
    var entered by rememberSaveable { mutableStateOf(false) }
    val entering = remember { animate && !entered }
    val stageIn = remember { Animatable(if (entering) 0f else 1f) }
    val panelIn = remember { Animatable(if (entering) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (!entering) return@LaunchedEffect
        entered = true
        launch { stageIn.animateTo(1f, foldTween(true)) }
        panelIn.animateTo(1f, foldTween(true, delayMillis = PANEL_DELAY_MS))
    }

    // 识别动效：和编辑器打开一张图一样，「自动打码」一露出来就在识别（码已经备好、藏在磨砂底下），到点落下来，
    // 落码那一遍演完才点得动。翻页翻得早，码当场落下。和那一页的画布同时起头：扫光收尾要多久，看它演了多久（见 settleMs）
    LaunchedEffect(state) {
        if (state.settled) return@LaunchedEffect
        snapshotFlow { state.progress.revealed || abs(pageOffset(pager, state.autoStep)) < 1f }.first { it }
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

    // 「识别引擎」那一页停稳了、测速也有了结论，就替人选上推荐的那个（写进设置），页上同时说为什么。
    // 没停在这一页上（跳过了、没等结论就翻走了）不替人改；选过一次（自己点的也算）就不再改
    val enginePage = pages.indexOf(TourPage.ENGINE)
    val recommended = engineAdvice?.recommended
    val onEnginePage = enginePage >= 0 && pager.settledPage == enginePage && interactive
    val engineChange by rememberUpdatedState(onEngineChange)
    LaunchedEffect(recommended, onEnginePage) {
        if (recommended != null && onEnginePage && !state.progress.engineChosen) {
            state.chooseEngine()
            engineChange(recommended)
        }
    }
    val chooseEngine = { option: TextEngineOption ->
        state.chooseEngine()
        onEngineChange(option)
    }

    BackHandler(enabled = interactive && target > 0) { turn(target - 1) }

    val titles = pages.map { it.title() }
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
        // 说明最高多高：图至少留 [stage] 那么高（欢迎页的图标小，留得少）；屏幕很矮（分屏、小外屏）时说明至少留 MIN_PANEL，图再小也让它
        val panelCap = { stage: Dp ->
            max((maxHeight - TOP_BAR_HEIGHT - BOTTOM_BAR_HEIGHT - stage - STAGE_PAD_V * 2).value, MIN_PANEL.value).dp
        }
        val pageWidth = constraints.maxWidth.toFloat()
        // 从右往左排的语言里下一页在左边：多走的那一截也要反过来
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f

        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = interactive,
            key = { it },
        ) { index ->
            val page = pages[index]
            // 这一层比整页多走多少（见 PARALLAX）：离开时往外多走，进来时落在后面，停下时为 0
            val layer = { depth: Int ->
                Modifier.graphicsLayer { translationX = direction * pageOffset(pager, index) * pageWidth * PARALLAX[depth] }
            }
            val stage = @Composable { m: Modifier ->
                if (page == TourPage.WELCOME) {
                    WelcomeMark(enter = { stageIn.value }, modifier = m.then(layer(0)))
                } else {
                    TourStage(
                        state, sample, scanStyle,
                        page = page,
                        showRing = page == TourPage.TAP && pager.settledPage == index && state.settled && !state.progress.tapped,
                        animate = animate,
                        modifier = m.then(layer(0)),
                    )
                }
            }
            val panel = @Composable { m: Modifier ->
                Box(m.then(if (index == 0) risen else Modifier)) {
                    when (page) {
                        TourPage.WELCOME -> Panel(
                            stringResource(R.string.app_name),
                            stringResource(R.string.onboarding_tagline),
                            layer,
                            titleStyle = MaterialTheme.typography.displaySmall,
                        ) {
                            Promise(stringResource(R.string.onboarding_offline), Modifier.padding(top = 24.dp))
                            Promise(stringResource(R.string.onboarding_metadata), Modifier.padding(top = 12.dp))
                        }
                        TourPage.AUTO, TourPage.TAP -> {
                            // 这两页的说明占一样高（垫一份看不见的另一页），图在两页上一样大、一样高，平移时不上下跳
                            val other = if (page == TourPage.AUTO) TourPage.TAP else TourPage.AUTO
                            Panel(other.title(), other.body(), { Modifier }, Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {}) {
                                if (other == TourPage.TAP) TapHint(tapped = false)
                            }
                            Panel(page.title(), page.body(), layer) {
                                if (page == TourPage.TAP) TapHint(state.progress.tapped)
                            }
                        }
                        TourPage.ENGINE -> Panel(
                            page.title(),
                            engineBody(engineAdvice),
                            layer,
                            // 测出结论前后字数不一样：按最长的一句占住高度，换字时图不跳
                            reserve = engineBodies(),
                        ) {
                            EngineCard(engine, chooseEngine)
                        }
                        TourPage.MORE -> Panel(page.title(), page.body(), layer) {
                            LabsCard(chatOn, onChatChange)
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
                            .heightIn(max = panelCap(if (page == TourPage.WELCOME) WELCOME_ICON else MIN_STAGE))
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
            step = target,
            steps = pages.size,
            title = titles[target],
            onSkip = onSkip,
            modifier = (textColumn?.let { Modifier.align(Alignment.TopStart).then(it) } ?: Modifier.align(Alignment.TopCenter).fillMaxWidth())
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

@Composable
private fun TourPage.title(): String = stringResource(
    when (this) {
        TourPage.WELCOME -> R.string.onboarding_welcome_title
        TourPage.AUTO -> R.string.onboarding_auto_title
        TourPage.TAP -> R.string.onboarding_tap_title
        TourPage.ENGINE -> R.string.engine
        TourPage.MORE -> R.string.onboarding_more_title
    },
)

/** 说明正文。欢迎页和「识别引擎」的另有写法（见 [engineBody]），不走这里。 */
@Composable
private fun TourPage.body(): String = stringResource(
    when (this) {
        TourPage.AUTO -> R.string.onboarding_auto_body
        TourPage.TAP -> R.string.onboarding_tap_body
        TourPage.MORE -> R.string.onboarding_more_body
        TourPage.WELCOME, TourPage.ENGINE -> error("$this 的正文另有写法")
    },
)

/** 「识别引擎」那一页的正文：测着的时候说在测；有了结论说测出多久、选了哪个、为什么，不合适去哪儿改。 */
@Composable
private fun engineBody(advice: EngineAdvice?): String = when (advice) {
    null, EngineAdvice.Measuring -> stringResource(R.string.onboarding_engine_measuring)
    is EngineAdvice.Fast -> stringResource(R.string.onboarding_engine_fast, seconds(advice.ms, up = false))
    is EngineAdvice.Slow -> advice.ms?.let { stringResource(R.string.onboarding_engine_slow, seconds(it, up = true)) }
        ?: stringResource(R.string.onboarding_engine_over, (EngineProbeModel.LIMIT_MS / 1000).toInt())
    EngineAdvice.Failed -> stringResource(R.string.onboarding_engine_failed)
}

/** [engineBody] 可能说的每一句（秒数取最宽的），按最高的那句占住高度。 */
@Composable
private fun engineBodies(): List<String> =
    listOf(EngineAdvice.Measuring, EngineAdvice.Fast(2_900), EngineAdvice.Slow(10_000), EngineAdvice.Slow(null), EngineAdvice.Failed)
        .map { engineBody(it) }

/**
 * 毫秒写成秒，一位小数，至少 0.1。[up] 往上进：慢的不会写成刚好 3.0；不然往下舍：快的不会写成 3.0 以上。
 */
@Composable
private fun seconds(ms: Long, up: Boolean): String {
    val tenths = (if (up) (ms + 99) / 100 else ms / 100).coerceAtLeast(1)
    return String.format(LocalConfiguration.current.locales[0], "%.1f", tenths / 10.0)
}

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

/** 顶上一行：几步走到哪儿了，右边是「跳过」。读屏念「第 3 步，共 5 步：点文字打码」，换步时也念。 */
@Composable
private fun TopBar(step: Int, steps: Int, title: String, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(TOP_BAR_HEIGHT)
            .padding(end = 12.dp)
            // 画在翻页的上面，读屏却会按左边先排到页里的内容：让它先念第几步、「跳过」
            .semantics { isTraversalGroup = true; traversalIndex = -1f },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val label = stringResource(R.string.onboarding_step, step + 1, steps, title)
        Row(
            Modifier.clearAndSetSemantics {
                contentDescription = label
                liveRegion = LiveRegionMode.Polite
            },
            horizontalArrangement = Arrangement.spacedBy(DOT),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(steps) { i ->
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

/** 底下一行：前几步是「下一步」；最后一步换成「选择图片」，左边多一个「打开设置」。 */
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
 * 一页的说明：标题一行，下面一两句；[extra] 是这一页另外要摆的（提示、承诺、设置里那几行）。
 * 标题、正文、另外那块各是视差里的一层（[layer] 1、2、3）。
 *
 * @param reserve 正文还可能换成的那几句：按最高的占住高度，换的时候淡入淡出、上面的图不跳
 */
@Composable
private fun Panel(
    title: String,
    body: String,
    layer: (Int) -> Modifier,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.headlineSmall,
    reserve: List<String> = emptyList(),
    extra: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier) {
        Text(
            title,
            style = titleStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = layer(1).semantics { heading() },
        )
        val bodyStyle = MaterialTheme.typography.bodyLarge
        val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant
        if (reserve.isEmpty()) {
            Text(body, style = bodyStyle, color = bodyColor, modifier = layer(2).padding(top = 8.dp))
        } else {
            // 读屏念的是眼下这一句，换了也念
            Box(
                layer(2).padding(top = 8.dp).clearAndSetSemantics {
                    text = AnnotatedString(body)
                    liveRegion = LiveRegionMode.Polite
                },
            ) {
                reserve.forEach { Text(it, style = bodyStyle, modifier = Modifier.graphicsLayer { alpha = 0f }) }
                Crossfade(body, label = "panelBody") { Text(it, style = bodyStyle, color = bodyColor) }
            }
        }
        Column(layer(3)) { extra() }
    }
}

/** 「点文字打码」指着图上框出的链接；点上过一段字就淡掉（位置留着，图不跳）。 */
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

/** 欢迎页上的图：桌面上那个图标（自适应图标，系统按桌面图标的形状裁好），入场时淡入、从略小长到原大（[enter] 0 到 1）。 */
@Composable
private fun WelcomeMark(enter: () -> Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { WELCOME_ICON.roundToPx() }
    val icon = remember(px) { ContextCompat.getDrawable(context, R.mipmap.ic_launcher)!!.toBitmap(px, px).asImageBitmap() }
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Image(
            icon,
            contentDescription = null,
            // 屏幕矮得放不下时跟着缩小
            modifier = Modifier.heightIn(max = WELCOME_ICON).aspectRatio(1f, matchHeightConstraintsFirst = true).graphicsLayer {
                val t = enter()
                alpha = t
                scaleX = ENTER_SCALE + (1f - ENTER_SCALE) * t
                scaleY = scaleX
            },
        )
    }
}

/** 欢迎页的一条承诺：前面一个对勾，对着第一行的中间（折成两行时不跑到两行中间，字放大了也对得上）。 */
@Composable
private fun Promise(text: String, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.bodyLarge
    val iconTop = with(LocalDensity.current) { ((style.lineHeight.toDp() - PROMISE_ICON) / 2).coerceAtLeast(0.dp) }
    Row(modifier) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = iconTop).size(PROMISE_ICON),
        )
        Text(text, style = style, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * 设置里的一段原样搬过来：上面一行写着它在设置里的哪儿（[path]），点了、拨了就写进设置，和在设置里改一样。
 * 设置页的行画在 surfaceBright 上，要放在 surfaceContainer 的底子上才看得出来，和设置页一样。
 */
@Composable
private fun SettingsExcerpt(path: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(SETTINGS_CARD_RADIUS))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 8.dp),
    ) {
        SectionHeader(path, first = true)
        content()
    }
}

/** 「识别引擎」那一页：设置里「AI › 识别引擎」的那两项。 */
@Composable
private fun EngineCard(selected: TextEngineOption, onSelect: (TextEngineOption) -> Unit) {
    SettingsExcerpt(
        "${stringResource(R.string.settings)} › ${stringResource(R.string.settings_ai)} › ${stringResource(R.string.engine)}",
    ) {
        SettingsGroup(Modifier.selectableGroup()) {
            TextEngineOption.entries.forEach { option ->
                RadioRow(
                    title = stringResource(textEngineLabel(option)),
                    summary = stringResource(
                        when (option) {
                            TextEngineOption.PADDLE -> R.string.engine_paddle_summary
                            TextEngineOption.MLKIT -> R.string.engine_mlkit_summary
                        },
                    ),
                    selected = selected == option,
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/** 最后一页：实验室页上的那一项，开关是真的；底下的说明也是实验室页上那句。 */
@Composable
private fun LabsCard(chatOn: Boolean, onChatChange: (Boolean) -> Unit) {
    SettingsExcerpt("${stringResource(R.string.settings)} › ${stringResource(R.string.settings_labs)}") {
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

/** 「自动打码」「点文字打码」的说明大约多高：挑横竖排时用，真摆的时候按量出来的高。 */
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

private val SETTINGS_CARD_RADIUS = 28.dp

/** 欢迎页上的图标多大，竖排时那一块至少也留这么高。 */
private val WELCOME_ICON = 120.dp
private val PROMISE_ICON = 20.dp

/** 入场时图标从这么大长到原大；说明从下面浮上来 [ENTER_RISE] 这么远，比图标慢 [PANEL_DELAY_MS] 这么一拍。 */
private const val ENTER_SCALE = 0.9f
private val ENTER_RISE = 24.dp
private const val PANEL_DELAY_MS = 60
