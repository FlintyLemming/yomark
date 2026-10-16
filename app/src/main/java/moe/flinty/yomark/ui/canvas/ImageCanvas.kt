package moe.flinty.yomark.ui.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Color as AndroidColor
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.core.geometry.Quad
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.Ink
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.export.PurposeWatermarkDrawer
import moe.flinty.yomark.render.MaskOrder
import moe.flinty.yomark.render.PixelMaskRenderer
import moe.flinty.yomark.render.RendererRegistry
import moe.flinty.yomark.render.TextStamp
import moe.flinty.yomark.ui.EditorUiState
import moe.flinty.yomark.ui.TextPlacement
import moe.flinty.yomark.ui.components.DETENT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/**
 * 画布：图像 + 遮罩 + 叠层 + 手势。
 *
 * 遮罩用与导出**同一套** MaskRenderer 绘制，保证所见即所得。
 * 手势分工：一指画框、两指缩放平移、点手动框进选中态（框上方浮出「删除」）、
 * 点码拿掉、点没打码的字打码那一行（见 EditorViewModel.onTap）。缩放只用两指，没有双击放大（2026-10-15 去掉）。
 * 马克笔、画笔选了手画时，一指画的是一道线而不是框（见 [onInk]）；画笔选了箭头时，从按下的地方拉出一支箭头。
 * 拿着文本点图上一处是在那儿写字（见 EditorViewModel.onTap）：字还没打时那儿立着一道光标。
 * 按在字上不动、过了长按的时间，打开大爆炸（[onLongPress]），手指不抬接着划是接着选字；大爆炸开着时一指点字、划字都是选字，
 * 选中的字连成的块画在图上（见 ui/bigbang）。
 *
 * @param scanStyle 识别动效的样式（设置里选的）。识别中跟着它走；结果到达那一刻定下来，落码 / 散开那一遍不再变
 * @param showOriginal 按住「对比」时为 true：只画原图，缩放与位置不变，前后比的是同一块
 * @param onTap 点了一下：点在哪儿（图像坐标），点字时手指偏出一段字多远还算点中它（图像像素）
 * @param onInk 手画完一笔：手指走过的点，图像坐标。箭头只有起点和松手的那一点
 * @param onEditSelected 选中的是文本时，框上方多一个「编辑」：改它写的字
 * @param plainSelection 选中框只描一圈线，不画手柄、不浮工具条：手机上写文本时面板开着，点画布只是收起面板，
 *   手柄和工具条点不到，倒把缩小了的图上正在打的字盖住了
 * @param textBoxes 开发者里打开「显示识别框」时，文字识别认出的每一段字的框（点字打码打的就是这些框）；空的就不画
 * @param onLongPress 按着不动过了长按的时间：按在哪儿（图像坐标）、离一个字多远还算按中它（图像像素）。
 *   返回打开了大爆炸没有；没有的话这一下照旧，松手算点一下，拖开是画框
 * @param onBigBangSweepStart 大爆炸开着时一指划字，第一个经过的字（Glyph.id）：它定这一划是选还是取消
 * @param onBigBangSweep 这一划接着经过的字，按经过的先后
 * @param onBigBangRelease 长按打开了大爆炸的那根手指抬起来了（见 BigBangState.holding）
 * @param onBigBangRestore 一指划着字、第二根手指落下来变成了缩放平移：选中的字回到这一划起手前的样子
 */
@Composable
fun ImageCanvas(
    state: EditorUiState,
    registry: RendererRegistry,
    onTap: (PointF, Float) -> Unit,
    onManualBox: (Quad) -> Unit,
    onResize: (Quad) -> Unit,
    onCommitDrag: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
    scanStyle: ScanStyle = ScanStyle.FROST,
    showOriginal: Boolean = false,
    onInk: (List<PointF>) -> Unit = {},
    onEditSelected: () -> Unit = {},
    plainSelection: Boolean = false,
    textBoxes: List<Quad> = emptyList(),
    onLongPress: (PointF, Float) -> Boolean = { _, _ -> false },
    onBigBangSweepStart: (Int) -> Unit = {},
    onBigBangSweep: (List<Int>) -> Unit = {},
    onBigBangRelease: () -> Unit = {},
    onBigBangRestore: (Set<Int>) -> Unit = {},
) {
    val image = state.image ?: return
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val minBoxPx = with(density) { GestureRules.MIN_BOX_DP.dp.toPx() }
    val haptics = LocalHapticFeedback.current

    var viewSize by remember { mutableStateOf(0 to 0) }
    var viewport by remember(image) { mutableStateOf(Viewport(1f, 0f, 0f)) }

    // 识别中的扫光 / 磨砂，与结果上屏的落码 / 散开（见 ScanEffect、FrostEffect）。时间轴只在绘制里读，
    // 走起来只触发重绘、不触发重组；识别中和收尾那一遍之外它是 null，一帧都不占。
    val scanning = state.analyzing
    var scanTime by remember(image) { mutableStateOf<ScanTime?>(null) }
    // 收尾那一遍按结果到达那一刻的样式走，中途不变。在重组里定，结果到达的那一帧就已经是它：
    // 留到协程里定的话，那一帧画在协程改它之前，还是上一次的样式，磨砂会换成扫光闪一下
    val settling = remember(image, scanning) { scanStyle }
    var revealOrigin by remember(image) { mutableStateOf<PointF?>(null) }

    // 磨砂要用的模糊图与光点（见 ScanLook.Frost），各自在识别开始后多久备好；识别开始时就有的记 0，每遍收尾后清回 0
    var frostBlur by remember(image) { mutableStateOf<Bitmap?>(null) }
    var frostLayer by remember(image) { mutableStateOf<FrostLayer?>(null) }
    var frostBlurAt by remember(image) { mutableLongStateOf(0L) }
    var frostLayerAt by remember(image) { mutableLongStateOf(0L) }
    val frostCover = remember(image) { lazy { FrostLayer.averageColor(image.bitmap) } }

    // 一遍动效从头到尾都在这一个协程里逐帧走：识别中，结果到了接着收尾，走完停下等下一遍。结果到没到在每一帧里看，
    // 发现到了的那一帧就已经在收尾。按识别状态重启协程的话，要等新协程跑起来才接得上，中间那一两帧画面不动，出结果前顿一下
    val scanningNow by rememberUpdatedState(scanning)
    val settlingNow by rememberUpdatedState(settling)
    LaunchedEffect(image) {
        while (true) {
            snapshotFlow { scanningNow }.first { it }
            val start = withFrameMillis { it }
            var last = start                // 识别中的最后一帧
            var resultAt = -1L              // 收尾从哪一帧数起；还在识别时为 -1
            var settle = 0L
            do {
                val going = withFrameMillis { frame ->
                    when {
                        resultAt < 0 && scanningNow -> {
                            last = frame
                            scanTime = ScanTime(frame - start, null)
                            true
                        }
                        // 收尾到一半又开始识别（换了方案）：这一遍作罢，从头再来
                        scanningNow -> false
                        else -> {
                            if (resultAt < 0) {
                                // 结果在识别中的最后一帧之后到的，从那一帧接着数
                                resultAt = last
                                settle = when (settlingNow) {
                                    ScanStyle.SWEEP -> ScanEffect.settleMs(last - start)
                                    ScanStyle.FROST -> FrostEffect.SETTLE_MS
                                }
                                // 磨砂从等待图标所在的位置散开，即画布中心；换算成图像坐标，用户此后拖动画面，洞也跟着图走
                                revealOrigin = viewport.screenToImage(PointF(viewSize.first / 2f, viewSize.second / 2f))
                            }
                            val since = frame - resultAt
                            scanTime = if (since < settle) ScanTime(frame - start, since) else null
                            since < settle
                        }
                    }
                }
            } while (going)
            frostBlurAt = 0L
            frostLayerAt = 0L
        }
    }
    val scanPaints = remember { ScanPaints() }
    val preparedMasks = remember(image) { PreparedMasks() }

    // 选的是磨砂、动效又在走（识别中或收尾那一遍）时在后台准备。模糊图几十毫秒就好，先拿去盖住原图；
    // 光点要逐格算噪声，慢得多，好了再淡入。识别快到结果先到，也接着准备完，散开那一遍用得上。
    // 「动效在走」用 derivedStateOf 读：时间轴每帧都在变，直接读会每帧重组一次。
    val animating by remember(image) { derivedStateOf { scanTime != null } }
    val wantsFrost = (scanning || animating) && (if (scanning) scanStyle else settling) == ScanStyle.FROST
    LaunchedEffect(image, wantsFrost) {
        if (!wantsFrost || frostLayer != null) return@LaunchedEffect
        // 按头一回量到的画布折算：之后底栏、输入法把画布挤高挤矮，差的那点 dp 看不出来，不为它把算到一半的扔掉重来
        val (w, h) = snapshotFlow { viewSize }.first { (w, h) -> w > 0 && h > 0 }
        val pxPerDp = density.density / Viewport.fit(image.width, image.height, w, h).scale
        if (frostBlur == null) {
            val blurred = withContext(Dispatchers.Default) { FrostLayer.blur(image.bitmap, FrostEffect.BLUR_DP * pxPerDp) }
            frostBlurAt = scanTime?.sinceScan ?: 0L
            frostBlur = blurred
        }
        val layer = withContext(Dispatchers.Default) { FrostLayer.prepare(image.bitmap, pxPerDp) }
        frostLayerAt = scanTime?.sinceScan ?: 0L
        frostLayer = layer
    }
    // 动效每帧都要画码，模糊、马赛克逐块现算太慢：动效在走时有读像素的码，就在后台把码合成一张（见 ComposedMasks）。
    // 渲染器里有跨调用复用的画笔，后台另用一套，不和界面上正在画的抢
    var animationMasks by remember(image) { mutableStateOf<ComposedMasks?>(null) }
    val sortedItems = remember(state.plan.items) { MaskOrder.forDrawing(state.plan.items) }
    LaunchedEffect(image, sortedItems, scanning || animating) {
        if (!(scanning || animating) || sortedItems.none { MaskOrder.readsPixels(it.look) }) {
            animationMasks = null
            return@LaunchedEffect
        }
        val composed = withContext(Dispatchers.Default) { ComposedMasks.compose(image, sortedItems, RendererRegistry.default()) }
        // 合成时算好的各块交给静止时用的那份：动效停下来的第一帧只是贴一下
        preparedMasks.adopt(image, composed)
        animationMasks = composed
    }
    val purposeWatermark = remember { PurposeWatermarkDrawer() }

    // 点一下打码、取消打码的那一下（见 MaskToggles）。plan 一变就在重组里比一比，这样新码在它上屏的第一帧之前就登记好了；
    // 时钟只在绘制里读，走起来只重画
    val toggles = remember(image) { MaskToggles() }
    val togglePaints = remember { TogglePaints() }
    var toggleClock by remember(image) { mutableLongStateOf(0L) }
    remember(toggles, state.plan.items) { toggles.sync(state.plan.items, animate = !scanning && !animating) }
    LaunchedEffect(toggles, state.plan.items) {
        while (toggles.active) withFrameMillis {
            toggles.advance(it)
            toggleClock = it
        }
    }

    // pointerInput 的 block 只在 key 变化时重启，捕获的是重启那一刻的 state。
    // 手势循环里必须读这个「永远最新」的引用，否则点选手动框后立刻拖手柄，
    // 循环里看到的 selectedManualId 还是 null，于是拖出来一个新框而不是改选中框。
    val latest by rememberUpdatedState(state)
    val latestShowOriginal by rememberUpdatedState(showOriginal)

    // viewport 是按哪个画布尺寸摆的。用途水印面板顶替底栏、输入法弹起时画布会变矮，
    // 这时要重新摆，否则图的下半截就被挤出画布，调水印时看不到效果。
    var laidOutFor by remember(image) { mutableStateOf(0 to 0) }
    var draftQuad by remember { mutableStateOf<Quad?>(null) }
    // 正在手画的一笔，图像坐标。点一多每帧复制一遍不划算：点存在普通的表里，加了点改一下 inkTick 让画布重画
    val draftInk = remember { InkDraft() }
    var inkTick by remember { mutableIntStateOf(0) }
    // 正在拖选中框的手柄或框体。拖的时候收起工具条，免得它跟着框一路晃
    var editingSelection by remember(image) { mutableStateOf(false) }
    // 草稿框每一帧都在变；工具条只关心「是不是正在画」，不跟着每帧重组
    val drafting by remember { derivedStateOf { draftQuad != null || inkTick > 0 } }

    // 大爆炸里选中的字连成的块：码已经打上了，这里描出哪几块是这一次打的
    val bigBangRuns = remember(state.bigBang) { state.bigBang?.let { it.page.runQuads(it.selected) }.orEmpty() }

    // 选中框、草稿框与手柄用主题色，与应用其余部分一致
    val colorScheme = MaterialTheme.colorScheme
    val selectionColors = remember(colorScheme.primary, colorScheme.surface) {
        SelectionColors(colorScheme.primary.toArgb(), colorScheme.surface.toArgb())
    }

    Box(modifier.fillMaxSize().clipToBounds()) {     // 放大后图像会溢出画布区，压到顶栏上
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .pointerInput(image) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // 一指按着「对比」看原图时，另一指在画布上只能缩放平移，不改码：改了也看不见
                        val editable = !latestShowOriginal
                        // 大爆炸开着时一指点的、划的是字（见 Mode.SWEEP），不画框、不拖框
                        val bigBangOpen = latest.bigBang != null

                        // 选中态优先：手柄 > 框体，都不中才轮到画框 / 点击。
                        // 删除在框上方的工具条里，那是叠在画布上的另一个控件，按它的手势到不了这里
                        val selected = latest.selectedManualId?.takeIf { editable && !bigBangOpen }?.let { latest.plan.find(it) }
                        val downImage = viewport.screenToImage(PointF(down.position.x, down.position.y))
                        var grabbedHandle: HandleCorner? = null
                        var grabOffset = PointF(0f, 0f)
                        var grabbingBody = false
                        if (selected != null) {
                            val inside = selected.quad.contains(downImage)
                            // 文本的框常常比四个角的命中区还小，整块都落在命中区里：框里面只认画出来的那颗手柄，
                            // 别处算按住框体——点一下改字，拖是挪。框外面照旧放宽
                            val hitSize = (if (inside && selected.look.style == MaskStyle.TEXT) HANDLE_DP / 1.5f else HANDLE_HIT_DP) *
                                density.density / viewport.scale
                            grabbedHandle = SelectionHandles.hitHandle(selected.quad, downImage, hitSize)
                            grabbingBody = grabbedHandle == null && inside
                            // 按下的地方离角有多远：拖起来角跟着手指平移，不会一动就跳到手指底下
                            grabbedHandle?.let {
                                val corner = SelectionHandles.cornerPoint(selected.quad, it)
                                grabOffset = PointF(corner.x - downImage.x, corner.y - downImage.y)
                            }
                        }

                        // 画笔照手画、拉框还是拉箭头，按下去那一刻定下来
                        val brush = latest.brush
                        val shape = brush.options.shapeFor(brush.style)
                        val start = down.position
                        var last = down.position
                        var pointerCount = 1
                        var mode = Mode.UNDECIDED
                        // 点字时手指偏出一个字多远还算点中它（图像像素），长按、划字也用它
                        val reach = { GestureRules.LINE_REACH_DP * density.density / viewport.scale }

                        // 长按：按下去之后一指不动，等到长按的时间。按在手柄、框体上的是要拖框，不等
                        var longPressArmed = editable && !bigBangOpen && grabbedHandle == null && !grabbingBody
                        val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
                        var lastEventAt = down.uptimeMillis
                        // 划字：上一次看到哪儿了（图像坐标），这一划起手了没有（第一个经过的字定这一划是选还是取消）、已经经过的字
                        var sweepFrom: PointF? = null
                        var sweepStarted = false
                        val swept = HashSet<Int>()
                        // 长按选上的那个字在哪儿：这一下 latest 里还没有大爆炸（要等下一次重组），等划起来时再记进 swept
                        var pressedGlyphAt: PointF? = null
                        // 这一下长按打开了大爆炸：手指抬起来时说一声，面板才出来
                        var openedBigBang = false
                        // 大爆炸开着时划字之前选中的是哪几个：第二根手指落下来变成缩放平移，这一划作罢，回到它
                        var sweepBefore: Set<Int>? = null
                        // 这一划看过了起点没有：接着上一段划的不再看起点
                        var sweepFromSeen = false

                        while (true) {
                            val event = if (longPressArmed && mode == Mode.UNDECIDED && pointerCount == 1) {
                                withTimeoutOrNull((longPressAt - lastEventAt).coerceAtLeast(0L)) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                }
                            } else {
                                awaitPointerEvent(PointerEventPass.Main)
                            }
                            if (event == null) {
                                // 按住不动到了时候：按在字上就打开大爆炸，按着的字已经选上了，接着划是接着选
                                longPressArmed = false
                                val p = viewport.screenToImage(PointF(last.x, last.y))
                                if (onLongPress(p, reach())) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    mode = Mode.SWEEP
                                    sweepFrom = p
                                    sweepStarted = true
                                    pressedGlyphAt = p
                                    openedBigBang = true
                                }
                                continue
                            }
                            lastEventAt = event.changes.maxOf { it.uptimeMillis }
                            val pressed = event.changes.filter { it.pressed }
                            pointerCount = maxOf(pointerCount, pressed.size)

                            if (pressed.size >= 2) {
                                // 两指：导航。已经开始画的草稿作废——用户改主意了。
                                // 此后这一手势剩下的单指部分不再改框，也不画框
                                mode = Mode.NAVIGATE
                                draftQuad = null
                                if (draftInk.points != null) {
                                    draftInk.points = null
                                    inkTick = 0
                                }
                                sweepBefore?.let(onBigBangRestore)
                                sweepBefore = null
                                val a = pressed[0]; val b = pressed[1]
                                val prevCentroid = Offset(
                                    (a.previousPosition.x + b.previousPosition.x) / 2f,
                                    (a.previousPosition.y + b.previousPosition.y) / 2f,
                                )
                                val centroid = Offset(
                                    (a.position.x + b.position.x) / 2f,
                                    (a.position.y + b.position.y) / 2f,
                                )
                                val prevSpan = (a.previousPosition - b.previousPosition).getDistance()
                                val span = (a.position - b.position).getDistance()
                                val zoom = if (prevSpan > 1f) span / prevSpan else 1f
                                val fit = Viewport.fit(image.width, image.height, viewSize.first, viewSize.second)
                                viewport = viewport
                                    .zoomAround(PointF(centroid.x, centroid.y), zoom)
                                    .pan(centroid.x - prevCentroid.x, centroid.y - prevCentroid.y)
                                    .clamped(image.width, image.height, viewSize.first, viewSize.second, fit.scale)
                                event.changes.forEach { it.consume() }
                            } else if (pressed.size == 1) {
                                val c = pressed.first()
                                if (mode == Mode.UNDECIDED &&
                                    GestureRules.isDrag(c.position.x - start.x, c.position.y - start.y, touchSlopPx)
                                ) {
                                    if (bigBangOpen && editable) {
                                        mode = Mode.SWEEP
                                        // 起手那一段 touch slop 也算：从手指按下的那个字划起
                                        sweepFrom = viewport.screenToImage(PointF(start.x, start.y))
                                        sweepBefore = latest.bigBang?.selected
                                    } else {
                                        mode = Mode.DRAG
                                    }
                                }
                                if (mode == Mode.SWEEP) {
                                    val page = latest.bigBang?.page
                                    if (page != null) {
                                        pressedGlyphAt?.let { at -> page.glyphAt(at, reach())?.let { swept += it.id } }
                                        pressedGlyphAt = null
                                        val step = SWEEP_STEP_DP * density.density / viewport.scale
                                        val points = c.historical.map { it.position } + c.position
                                        for (pos in points) {
                                            val to = viewport.screenToImage(PointF(pos.x, pos.y))
                                            val hit = page.glyphsAlong(sweepFrom ?: to, to, reach(), step, withStart = !sweepFromSeen).map { it.id }
                                            sweepFrom = to
                                            sweepFromSeen = true
                                            if (hit.isEmpty()) continue
                                            if (!sweepStarted) {
                                                sweepStarted = true
                                                onBigBangSweepStart(hit.first())
                                            }
                                            onBigBangSweep(hit)
                                            // 每经过一个新的字轻轻震一下，和胶片条上划过一张一样
                                            if (swept.addAll(hit)) haptics.performHapticFeedback(DETENT)
                                        }
                                    }
                                    if (c.positionChanged()) c.consume()
                                }
                                // 过了 touch slop 才动手，没动的话这一下是点击。
                                // 改框一律按起手那一刻的框加手指的总位移算：框紧跟手指，不丢 slop 那一段
                                if (mode == Mode.DRAG && editable) {
                                    val now = viewport.screenToImage(PointF(c.position.x, c.position.y))
                                    val handle = grabbedHandle
                                    if (selected != null && handle != null) {
                                        editingSelection = true
                                        val to = PointF(now.x + grabOffset.x, now.y + grabOffset.y)
                                        onResize(SelectionHandles.resize(selected.quad, handle, to, minBoxPx / viewport.scale))
                                    } else if (selected != null && grabbingBody) {
                                        editingSelection = true
                                        onResize(SelectionHandles.move(selected.quad, now.x - downImage.x, now.y - downImage.y))
                                    } else if (shape == StrokeShape.FREEHAND) {
                                        // 起手那一段 touch slop 也算进去：线从手指按下的地方开始
                                        val points = draftInk.points
                                            ?: arrayListOf(viewport.screenToImage(PointF(start.x, start.y))).also { draftInk.points = it }
                                        c.historical.forEach { h ->
                                            points.addIfApart(viewport.screenToImage(PointF(h.position.x, h.position.y)), INK_STEP_PX / viewport.scale)
                                        }
                                        points.addIfApart(now, INK_STEP_PX / viewport.scale)
                                        inkTick++
                                    } else if (shape == StrokeShape.ARROW) {
                                        // 箭头只看头尾：尾在手指按下的地方，箭尖跟着手指走
                                        draftInk.points = arrayListOf(viewport.screenToImage(PointF(start.x, start.y)), now)
                                        inkTick++
                                    } else {
                                        draftQuad = GestureRules.quadFromDrag(
                                            viewport.screenToImage(PointF(start.x, start.y)), now,
                                        )
                                    }
                                    if (c.positionChanged()) c.consume()
                                }
                                last = c.position
                            }

                            if (event.changes.all { !it.pressed }) break
                        }

                        editingSelection = false
                        if (openedBigBang) onBigBangRelease()
                        // 整段拖动只压一个快照。没真拖起来的话 ViewModel 那边什么也没记，这一下什么都不做
                        if (grabbedHandle != null || grabbingBody) onCommitDrag()

                        when {
                            mode == Mode.DRAG && grabbedHandle == null && !grabbingBody -> {
                                val q = draftQuad
                                draftQuad = null
                                val ink = draftInk.points
                                draftInk.points = null
                                inkTick = 0
                                val minInImage = minBoxPx / viewport.scale
                                if (q != null && GestureRules.acceptsBox(q, minInImage)) onManualBox(q)
                                // 和框一样，太短的一笔是手抖
                                if (ink != null && Ink.length(ink) >= minInImage) onInk(ink.toList())
                            }
                            // 点了一下手柄没拖：什么都不做。手柄的命中区探出框外，当成点空白会把选中态点没了。
                            // 按得久的一下：按在字上的打开了大爆炸（Mode.SWEEP），到不了这里；没按在字上的照旧是点击。
                            // 大爆炸开着时点一下是点字（见 EditorViewModel.onTap）
                            mode == Mode.UNDECIDED && pointerCount == 1 && grabbedHandle == null && editable -> {
                                val p = viewport.screenToImage(PointF(last.x, last.y))
                                onTap(p, reach())
                            }
                        }
                    }
                }
        ) {
            viewSize = size.width.toInt() to size.height.toInt()
            if (viewSize != laidOutFor) {
                val (w, h) = viewSize
                val fit = Viewport.fit(image.width, image.height, w, h)
                val (oldW, oldH) = laidOutFor
                // 原本就是整图适配（或第一次摆）就按新尺寸重新适配；用户放大过的保留缩放，只收回边界
                val wasFit = oldW == 0 || viewport == Viewport.fit(image.width, image.height, oldW, oldH)
                viewport = if (wasFit) fit else viewport.clamped(image.width, image.height, w, h, fit.scale)
                // 大爆炸的面板出来、画布变矮了：放大着看的图上选中的那几块可能落到了画布下面，挪上来
                if (!wasFit && bigBangRuns.isNotEmpty()) {
                    val area = android.graphics.RectF(bigBangRuns.first().bounds()).apply { bigBangRuns.forEach { union(it.bounds()) } }
                    viewport = viewport.revealing(area, w, h, REVEAL_MARGIN_DP * density.density)
                        .clamped(image.width, image.height, w, h, fit.scale)
                }
                laidOutFor = viewSize
            }

            drawIntoCanvas { compose ->
                val canvas = compose.nativeCanvas
                val save = canvas.save()
                canvas.concat(viewport.matrix())

                if (showOriginal) {
                    // 打码、用途水印、选中框和识别动效一概不画
                    canvas.drawBitmap(image.bitmap, 0f, 0f, null)
                    canvas.restoreToCount(save)
                    return@drawIntoCanvas
                }

                // 识别中，时间轴的第一帧还没到（或手里还是上一遍收尾的帧）也按「识别中」画：
                // 换方案重跑时旧结果不该闪一下
                val time = if (scanning) scanTime?.takeIf { it.sinceResult == null } ?: ScanTime.START else scanTime
                val look = when (if (scanning) scanStyle else settling) {
                    ScanStyle.SWEEP -> ScanLook.Sweep
                    ScanStyle.FROST -> ScanLook.Frost(
                        frostBlur, frostLayer, revealOrigin,
                        cover = if (frostBlur == null) frostCover.value else 0,
                        blurAt = frostBlurAt, layerAt = frostLayerAt,
                    )
                }
                val toggle = if (toggles.active) toggles.frame(toggleClock) else null
                drawScene(
                    canvas, image, state.plan, registry, viewport.scale, density.density, time, scanPaints, look,
                    toggle, togglePaints, animationMasks, preparedMasks,
                )
                // 用途水印与导出用同一个 drawer，尺寸按短边比例算，画在分析图上与导出图上观感一致。
                // 不预览的话，设好文案后画面毫无变化，看起来就像这个功能没生效。
                state.purposeText?.let {
                    purposeWatermark.draw(canvas, image.width, image.height, it, state.purposeStyle)
                }
                val dp = density.density / viewport.scale
                if (textBoxes.isNotEmpty()) drawTextBoxes(canvas, textBoxes, dp)
                bigBangRuns.forEach { drawBigBangRun(canvas, it, selectionColors, dp) }
                state.selectedManualId?.let { id ->
                    state.plan.find(id)?.let { drawSelection(canvas, it.quad, selectionColors, dp, handles = !plainSelection) }
                }
                draftQuad?.let { drawDraft(canvas, it, selectionColors, dp) }
                state.textPlacement?.let { drawCaret(canvas, it, state.brush, image, selectionColors, dp) }
                // 手画的这一笔照画好之后的样子画：用的是同一个渲染器
                if (inkTick > 0) draftInk.points?.let { drawInkDraft(canvas, it, state.brush, image, registry) }

                canvas.restoreToCount(save)

                // 磨砂中间的等待图标跟屏幕走：总在画布中心、大小恒定，不随图缩放平移
                if (time != null && look is ScanLook.Frost) {
                    drawLoader(canvas, size.width / 2f, size.height / 2f, density.density, time, scanPaints.frost)
                }
            }
        }

        // 选中框上方的「删除」。淡出的那一下（删掉了、开始拖了）框可能已经不在 plan 里，按它最后的位置摆
        val selectedItem = state.selectedManualId?.let { state.plan.find(it) }
        val selectedQuad = selectedItem?.quad
        val lastSelectedQuad = remember { QuadHolder() }
        if (selectedQuad != null) lastSelectedQuad.quad = selectedQuad
        // 淡出的那一下框已经没了，按钮照着它最后的样子留着，不在半路上少一个
        if (selectedItem != null) lastSelectedQuad.text = selectedItem.look.style == MaskStyle.TEXT
        AnimatedVisibility(
            visible = selectedQuad != null && !editingSelection && !drafting && !showOriginal && !plainSelection,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            SelectionToolbar(
                quad = selectedQuad ?: lastSelectedQuad.quad,
                viewport = { viewport },
                onDelete = onDeleteSelected,
                onEdit = if (lastSelectedQuad.text) onEditSelected else null,
            )
        }
    }
}

/**
 * 选中手动框时浮在框上方的工具条。与应用里其余的按钮一样是 Material 组件——主题色、字体、
 * 按下时的水波纹都一致，不在画布上自己画。只有「删除」一个操作：移动、调大小直接在框上拖。
 * 选中的是文本时前面多一个「编辑」（[onEdit]）：改它写的字。
 *
 * 这一层铺满画布，但只有工具条本身接触摸；别处的手势照常落到底下的画布上。
 */
@Composable
private fun SelectionToolbar(quad: Quad?, viewport: () -> Viewport, onDelete: () -> Unit, onEdit: (() -> Unit)? = null) {
    Layout(
        content = {
            // 与 Material 的菜单、文字选择工具条同一种容器：surfaceContainer 底色加一层浅投影
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
                Row {
                    if (onEdit != null) {
                        TextButton(onClick = onEdit) {
                            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(R.string.edit_text))
                        }
                    }
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.delete))
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val bar = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        layout(constraints.maxWidth, constraints.maxHeight) {
            // viewport 在摆放这一步才读：缩放、平移时工具条只重新摆，不重组
            val box = quad?.let { viewport().imageToScreen(it.bounds()) } ?: return@layout
            SelectionHandles.toolbarPosition(
                box, bar.width, bar.height, constraints.maxWidth, constraints.maxHeight,
                gap = TOOLBAR_GAP_DP.dp.toPx(), margin = TOOLBAR_MARGIN_DP.dp.toPx(),
            )?.let { bar.place(it.x, it.y) }
        }
    }
}

/** 记住最后一个选中框的位置、是不是文本，供工具条淡出时用。普通字段，改它不触发重组。 */
private class QuadHolder {
    var quad: Quad? = null
    var text = false
}

/** 正在手画的一笔。普通字段，改它不触发重组（重画靠 ImageCanvas 的 inkTick）。 */
private class InkDraft { var points: ArrayList<PointF>? = null }

/** 手指走得比 [step] 远了才记一个点：停着不动时不攒一堆重合的点。 */
private fun ArrayList<PointF>.addIfApart(p: PointF, step: Float) {
    val tail = lastOrNull()
    if (tail == null || hypot(p.x - tail.x, p.y - tail.y) >= step) add(p)
}

/** 手画时隔多远记一个点（屏幕像素）。 */
private const val INK_STEP_PX = 3f

/** 正在手画的一笔（或拉着的箭头），按画笔的样子画，和画完落进 plan 的那一笔一个样。 */
private fun drawInkDraft(
    canvas: android.graphics.Canvas,
    points: List<PointF>,
    brush: MaskLook,
    image: SourceImage,
    registry: RendererRegistry,
) {
    val options = brush.options.copy(renderScale = image.scale)
    val half = options.halfWidth(brush.style, min(image.width, image.height).toFloat())
    val arrow = options.shapeFor(brush.style) == StrokeShape.ARROW
    val (quad, ink) = Ink.fit(points, Ink.margin(half, arrow), arrow)
    registry[brush.style].render(canvas, image.bitmap, quad, options, ink)
}

/** 一手势走的是哪一种：还没定（点击）、一指拖（画框、手画、拖框）、两指导航、划字（大爆炸）。 */
private enum class Mode { UNDECIDED, DRAG, NAVIGATE, SWEEP }

/** 划字时沿着手指走过的路每隔多远（dp）看一次经过了哪个字：比最小的字窄，快划也不漏字。 */
private const val SWEEP_STEP_DP = 3f

/** 画布变矮后把选中的块挪进来时，离画布边至少多远（dp）。 */
private const val REVEAL_MARGIN_DP = 16f

/** 手柄直径（dp），含外面那一圈。画的时候按缩放反算到图像坐标，视觉大小恒定。 */
private const val HANDLE_DP = 16f

/** 手柄外圈的宽度（dp）。 */
private const val HANDLE_RING_DP = 2.5f

/** 手柄的命中边长（dp）。SelectionHandles.hitHandle 还会再放宽 1.5 倍，约 48dp，与触控目标的推荐尺寸一致。 */
private const val HANDLE_HIT_DP = 32f

/** 选中框、草稿框的线宽（dp）。 */
private const val SELECTION_LINE_DP = 2f

/** 线两侧垫的浅色细边各多宽（dp）。 */
private const val HALO_DP = 1f

/** 工具条与框之间的距离（dp），让开角上的手柄。 */
private const val TOOLBAR_GAP_DP = 12f

/** 工具条离画布边缘至少多远（dp）。 */
private const val TOOLBAR_MARGIN_DP = 8f

/** 草稿框内那层主题色的不透明度，约 12%，与 Material 的状态层一个量级。 */
private const val DRAFT_FILL_ALPHA = 0x1F

/** 选中框、草稿框与手柄的颜色，取自主题（见 YomarkTheme）。 */
private class SelectionColors(val primary: Int, val surface: Int)

/**
 * 线底下垫的那道浅色细边（surface 色）。主题色的线落在白底截图上很清楚，落在深色或同色的图上就看不清了，
 * 垫一道浅色的边哪里都看得见。[dp] 是 1dp 在图像坐标下的长度。
 */
private fun haloPaint(colors: SelectionColors, dp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = colors.surface
    strokeWidth = (SELECTION_LINE_DP + HALO_DP * 2f) * dp
}

private fun linePaint(colors: SelectionColors, dp: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = colors.primary
    strokeWidth = SELECTION_LINE_DP * dp
}

/** 选中框：主题色实线，四角是可以拖的手柄（[handles] 为 false 时只描线）。 */
private fun drawSelection(canvas: android.graphics.Canvas, quad: Quad, colors: SelectionColors, dp: Float, handles: Boolean = true) {
    val path = quad.toPath()
    canvas.drawPath(path, haloPaint(colors, dp))
    canvas.drawPath(path, linePaint(colors, dp))
    if (!handles) return
    // 手柄：主题色圆点套一圈浅色，再带一点投影——Material 滑块上那个拖动点的样子
    val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colors.surface
        setShadowLayer(2f * dp, 0f, 1f * dp, AndroidColor.argb(0x4D, 0, 0, 0))
    }
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colors.primary
    }
    HandleCorner.entries.forEach { corner ->
        val p = SelectionHandles.cornerPoint(quad, corner)
        canvas.drawCircle(p.x, p.y, HANDLE_DP / 2f * dp, ring)
        canvas.drawCircle(p.x, p.y, (HANDLE_DP / 2f - HANDLE_RING_DP) * dp, dot)
    }
}

/**
 * 要写字的那一处还没有字：立一道光标，和输入框里的光标一个意思。主题色、垫一道浅色的边，和选中框一样哪里都看得见；
 * 一行字多高它就多高，摆在第一个字将来落下的地方（见 TextStamp.boxAt）。
 */
private fun drawCaret(
    canvas: android.graphics.Canvas,
    at: TextPlacement,
    brush: MaskLook,
    image: SourceImage,
    colors: SelectionColors,
    dp: Float,
) {
    val look = at.look ?: brush
    val size = look.options.textSize * min(image.width, image.height)
    val box = TextStamp.boxAt("", size, at.center, image.width, image.height).bounds()
    val x = box.centerX()
    val inset = size * TextStamp.PAD_Y
    val round = { p: Paint -> p.apply { strokeCap = Paint.Cap.ROUND } }
    canvas.drawLine(x, box.top + inset, x, box.bottom - inset, round(haloPaint(colors, dp)))
    canvas.drawLine(x, box.top + inset, x, box.bottom - inset, round(linePaint(colors, dp)))
}

/**
 * 开发者里的「显示识别框」：识别出的每一段字一个框，盖在码上面，打了码的那几段也看得到。
 * 用固定的品红，不跟主题色：要和选中框、草稿框分得开，在哪种图上都扎眼。
 */
private fun drawTextBoxes(canvas: android.graphics.Canvas, boxes: List<Quad>, dp: Float) {
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = TEXT_BOX_COLOR
        strokeWidth = TEXT_BOX_LINE_DP * dp
    }
    boxes.forEach { canvas.drawPath(it.toPath(), paint) }
}

private const val TEXT_BOX_COLOR = 0xFFFF00FF.toInt()
private const val TEXT_BOX_LINE_DP = 1f

/**
 * 大爆炸里选中的一块字：码已经打在图上了（点字就是打码），这里只描一圈主题色的实线，
 * 看得出哪几块是这一次大爆炸打的、连着的字是一整块。
 */
private fun drawBigBangRun(canvas: android.graphics.Canvas, quad: Quad, colors: SelectionColors, dp: Float) {
    val path = quad.toPath()
    canvas.drawPath(path, haloPaint(colors, dp))
    canvas.drawPath(path, linePaint(colors, dp))
}

/** 草稿框：主题色虚线，框内铺一层很淡的主题色，拖的时候一眼看出圈住了哪一块。 */
private fun drawDraft(canvas: android.graphics.Canvas, quad: Quad, colors: SelectionColors, dp: Float) {
    val path = quad.toPath()
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = (colors.primary and 0x00FFFFFF) or (DRAFT_FILL_ALPHA shl 24)
    }
    canvas.drawPath(path, fill)
    canvas.drawPath(path, haloPaint(colors, dp))
    canvas.drawPath(path, linePaint(colors, dp).apply {
        pathEffect = DashPathEffect(floatArrayOf(8f * dp, 5f * dp), 0f)
    })
}

/**
 * 图像、遮罩与扫描动效——画布上除了选中态、草稿框和磨砂的等待图标之外的全部内容。拆出来是为了
 * 让像素测试能直接画出动效的任意一帧。
 *
 * 调用前画布已经 concat 了 viewport：这里一律是图像坐标，dp 尺寸按 [scale] 反算。
 *
 * @param time 动效时间轴上的这一帧（见 ScanEffect、FrostEffect）；null 表示静止，照常画全部遮罩
 * @param look 按哪种样式画这一帧
 * @param toggle 点一下打码、取消打码的这一帧（见 MaskToggles）；只在 [time] 为 null 时画
 * @param animationMasks 动效里用的合成图（见 ComposedMasks），对不上眼下的码就不用
 * @param prepared 读像素的码各自算好的样子（见 PreparedMasks）；跨帧复用才省得了重算
 */
internal fun drawScene(
    canvas: android.graphics.Canvas,
    image: SourceImage,
    plan: MaskPlan,
    registry: RendererRegistry,
    scale: Float,
    density: Float,
    time: ScanTime?,
    paints: ScanPaints,
    look: ScanLook = ScanLook.Sweep,
    toggle: ToggleFrame? = null,
    togglePaints: TogglePaints = TogglePaints(),
    animationMasks: ComposedMasks? = null,
    prepared: PreparedMasks = PreparedMasks(),
) {
    canvas.drawBitmap(image.bitmap, 0f, 0f, null)

    // 识别出的在底下，用户加的后加的盖在先加的上面——与导出同一个顺序（见 MaskOrder）
    val sorted = MaskOrder.forDrawing(plan.items)
    prepared.retain(image, sorted, toggle?.vanishing?.map { it.first }.orEmpty())
    val dp = density / scale
    if (time == null) {
        // 与导出一样一块接一块地画；读像素的码贴的是照它底下的样子算好的（见 PreparedMasks）
        val draw = { item: MaskItem -> drawItem(canvas, item, image, registry, prepared) }
        if (toggle == null) {
            sorted.forEach(draw)
            return
        }
        // 正在抹上的码在它自己那一层只留抹过的那部分，后画的码照样盖在它上面；揭开的码已经不在 plan 里，最后照它自己的样式画
        val feather = MaskToggle.FEATHER_DP * dp
        sorted.forEach { item ->
            val progress = toggle.appearing[item.candidateId]
            if (progress == null) draw(item)
            else drawWiped(canvas, item, progress, laying = true, feather, togglePaints) { draw(item) }
        }
        toggle.vanishing.forEach { (item, progress) ->
            drawWiped(canvas, item, progress, laying = false, feather, togglePaints) { draw(item) }
        }
        return
    }

    // 动效里，读像素的码从后台合成好的那张上取。还没合成好的那几帧，识别出的码先不落：它们本来就是在磨砂、暗幕底下
    // 从无到有慢慢显出来的，晚到几帧看不出来；当场逐块现算的话，这几帧一帧要几十毫秒。
    // 这几帧里用户加的码也照它自己读原图的样子画，不照着底下还没落的码去算
    val ready = animationMasks?.takeIf { it.items == sorted }?.bitmap
    val drawItems = { items: List<MaskItem> -> drawItems(canvas, items, image, registry, ready, prepared, alone = true) }
    val (manual, detected) = sorted.partition { it.byUser }
    val drawDetected = { if (ready != null || detected.none { MaskOrder.readsPixels(it.look) }) drawItems(detected) }

    if (look is ScanLook.Frost) {
        drawFrostScene(
            canvas, image.width.toFloat(), image.height.toFloat(), dp, time, look, paints.frost,
            drawDetected = drawDetected,
            drawManual = { drawItems(manual) },
        )
        return
    }

    val height = image.height.toFloat()
    val trail = ScanEffect.TRAIL_DP * dp
    val lead = ScanEffect.LEAD_DP * dp
    val light = LightSpec(image.width, height, trail, lead, ScanEffect.HUE_SPAN_DP * dp, ScanEffect.drift(time.sinceScan))
    val feather = ScanEffect.FEATHER_DP * dp
    // 结果到达后，循环光先提速走完手头这一趟，落码那一遍紧接着从顶上开始
    val front = ScanEffect.revealMs(time.sinceScan, time.sinceResult)?.let { ScanEffect.revealFront(it, height, lead, trail, feather) }

    // 暗幕压在原图上、打码块底下：落码时块与原图的亮度一起自上而下回来
    drawScrim(canvas, image.width.toFloat(), height, time.sinceScan, front, feather, paints)

    // 识别中（以及结果到了、循环光还在走完那一趟时），识别出的块一律不画：换方案重跑时旧结果先退场，不与扫光混在一起。
    // 落码中，它们画进一个离屏图层，只留光已经走过的那部分。
    if (front != null) {
        val layer = canvas.saveLayer(null, null)
        drawDetected()
        keepRevealed(canvas, front, feather, image.width.toFloat(), height, paints)
        canvas.restoreToCount(layer)
        drawLight(canvas, light, front, strength = 1f, paints)
    }
    ScanEffect.loopPhase(time.sinceScan, time.sinceResult)?.let { phase ->
        drawLight(
            canvas, light,
            ScanEffect.loopCenter(phase, height, lead, trail),
            ScanEffect.loopStrength(time.sinceScan),
            paints,
        )
    }
    // 手动框、点上的一行是用户自己加的，识别期间也一直在，清楚地浮在光上面
    drawItems(manual)
}

/**
 * 画 [items] 这几块码（已经按 MaskOrder 排好了）。有 [composite]（动效里的合成图，见 ComposedMasks）时，
 * 盖住整块的码（打码的几种）所在的地方直接从合成好的图上取；马克笔、画笔、文本只占框里的一部分，
 * 整框贴过来的话框里没画到的地方是没压暗、没磨砂的原图，照它自己的样子画（见 [drawItem]）。
 * 按先后一段段地来：连着的几块打码一次贴，中间夹着的标记照画，后面的打码照样盖在它上面。没有合成图时一块块地画。
 */
private fun drawItems(
    canvas: android.graphics.Canvas,
    items: List<MaskItem>,
    image: SourceImage,
    registry: RendererRegistry,
    composite: android.graphics.Bitmap?,
    prepared: PreparedMasks,
    alone: Boolean,
) {
    if (composite == null) {
        items.forEach { drawItem(canvas, it, image, registry, prepared, alone) }
        return
    }
    var run: android.graphics.Path? = null
    fun paste() {
        val area = run ?: return
        run = null
        val save = canvas.save()
        canvas.clipPath(area)
        canvas.drawBitmap(composite, 0f, 0f, null)
        canvas.restoreToCount(save)
    }
    items.forEach { item ->
        if (item.look.style.redacts) {
            (run ?: android.graphics.Path().also { run = it }).op(item.quad.toPath(), android.graphics.Path.Op.UNION)
        } else {
            paste()
            drawItem(canvas, item, image, registry, prepared, alone)
        }
    }
    paste()
}

/**
 * 按 [item] 自己的样式画它：读像素的码贴 [prepared] 里算好的样子，别的当场画。
 * [alone] 时读像素的码不管底下先画了什么，照原图算（见 PreparedMasks.draw）。
 */
private fun drawItem(
    canvas: android.graphics.Canvas,
    item: MaskItem,
    image: SourceImage,
    registry: RendererRegistry,
    prepared: PreparedMasks,
    alone: Boolean = false,
) {
    val renderer = registry[item.look.style]
    if (renderer is PixelMaskRenderer) {
        prepared.draw(canvas, image, item, registry, alone)
        return
    }
    // 预览画在分析图上；渲染器里的绝对像素常数是原图口径，按 image.scale 折算，
    // 否则大图上预览会比导出更糊（见 MaskOptions.renderScale）。
    val options = item.look.options.copy(renderScale = image.scale)
    renderer.render(canvas, image.bitmap, item.quad, options, item.ink, item.text)
}

/** 扫描动效的画笔，跨帧复用。 */
internal class ScanPaints {
    /** 磨砂那一套。 */
    val frost = FrostPaints()

    /** 滤色：把光下的字染上颜色；白底滤色之后还是白，看不出来。 */
    val screen = Paint().apply { blendMode = BlendMode.SCREEN }

    /** 正常叠加的一层淡光晕，光扫过空白处时也看得见。 */
    val tint = Paint()

    /** 落码图层的蒙版：只留光已经走过的部分。 */
    val keep = Paint().apply { blendMode = BlendMode.DST_IN }

    /** 识别期间压在图上的暗幕。 */
    val scrim = Paint()
}

/** 一道光在这一帧里不变的部分：图像范围与按缩放反算好的尺寸（图像坐标）。 */
private class LightSpec(
    val width: Int,
    val height: Float,
    val trail: Float,
    val lead: Float,
    val hueSpan: Float,
    val drift: Float,
)

/** 光形竖直方向的取样数。鼓包靠线性插值逼近，24 段肉眼已看不出折线。 */
private const val LIGHT_STOPS = 24

/**
 * 一道光：竖直方向的亮度按 ScanEffect.glow 取样，水平方向是缓缓流动的天蓝—长春花蓝—淡紫。
 * 先滤色把光下的字染亮，再正常叠一层很淡的光晕。只画在图像范围内，不碰画布的留白。
 */
private fun drawLight(canvas: android.graphics.Canvas, spec: LightSpec, center: Float, strength: Float, paints: ScanPaints) {
    if (strength <= 0f) return
    val top = center - spec.trail
    val bottom = center + spec.lead
    val drawTop = top.coerceAtLeast(0f)
    val drawBottom = bottom.coerceAtMost(spec.height)
    if (drawBottom <= drawTop) return

    // 渐变的各个色标只用 alpha，RGB 一律取 0：DST_IN 只看 alpha
    val shape = LinearGradient(
        0f, top, 0f, bottom,
        IntArray(LIGHT_STOPS + 1) { i ->
            val y = top + (bottom - top) * i / LIGHT_STOPS
            AndroidColor.argb((ScanEffect.glow(y, center, spec.trail, spec.lead) * 255).roundToInt(), 0, 0, 0)
        },
        null, Shader.TileMode.CLAMP,
    )
    // 镜像平铺的颜色一个周期是两倍 hueSpan，按相位整体平移，流动起来没有接缝
    val x0 = -spec.drift * 2f * spec.hueSpan
    val hues = LinearGradient(x0, 0f, x0 + spec.hueSpan, 0f, ScanEffect.HUES, null, Shader.TileMode.MIRROR)
    val shader = ComposeShader(hues, shape, PorterDuff.Mode.DST_IN)

    val right = spec.width.toFloat()
    paints.screen.shader = shader
    paints.screen.alpha = (ScanEffect.SCREEN_ALPHA * strength * 255).roundToInt()
    canvas.drawRect(0f, drawTop, right, drawBottom, paints.screen)
    paints.tint.shader = shader
    paints.tint.alpha = (ScanEffect.TINT_ALPHA * strength * 255).roundToInt()
    canvas.drawRect(0f, drawTop, right, drawBottom, paints.tint)
}

/** 落码蒙版的取样数。 */
private const val KEEP_STOPS = 8

/**
 * 落码图层上只留光已经走过的部分：光的中心往上 [feather] 处以上原样保留，中心以下抹掉，
 * 中间按 ScanEffect.coverage 渐变。蒙版的范围比图像大出一圈，探出图像边的码也一样处理。
 */
private fun keepRevealed(
    canvas: android.graphics.Canvas,
    front: Float,
    feather: Float,
    width: Float,
    height: Float,
    paints: ScanPaints,
) {
    val top = front - feather
    paints.keep.shader = LinearGradient(
        0f, top, 0f, front,
        IntArray(KEEP_STOPS + 1) { i ->
            val y = top + feather * i / KEEP_STOPS
            AndroidColor.argb((ScanEffect.coverage(y, front, feather) * 255).roundToInt(), 0, 0, 0)
        },
        null, Shader.TileMode.CLAMP,
    )
    canvas.drawRect(-width, -height, width * 2f, height * 2f, paints.keep)
}

/**
 * 识别期间压在图上的暗幕，只盖图像范围。识别中是一整块均匀的暗；落码中（[front] 非 null）
 * 光走过的地方按 ScanEffect.scrim 揭开，光还没到的地方仍然压着。
 */
private fun drawScrim(
    canvas: android.graphics.Canvas,
    width: Float,
    height: Float,
    sinceScanMs: Long,
    front: Float?,
    feather: Float,
    paints: ScanPaints,
) {
    val paint = paints.scrim
    if (front == null) {
        val alpha = (ScanEffect.scrim(0f, sinceScanMs, null, feather) * 255).roundToInt()
        if (alpha <= 0) return
        paint.shader = null
        paint.color = AndroidColor.argb(alpha, 0, 0, 0)
    } else {
        if (front - feather >= height) return   // 整张图都已揭开
        val top = front - feather
        paint.color = AndroidColor.BLACK
        paint.shader = LinearGradient(
            0f, top, 0f, front,
            IntArray(KEEP_STOPS + 1) { i ->
                val y = top + feather * i / KEEP_STOPS
                AndroidColor.argb((ScanEffect.scrim(y, sinceScanMs, front, feather) * 255).roundToInt(), 0, 0, 0)
            },
            null, Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width, height, paint)
}
