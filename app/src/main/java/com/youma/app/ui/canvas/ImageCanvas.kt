package com.youma.app.ui.canvas

import android.graphics.BlendMode
import android.graphics.Color as AndroidColor
import android.graphics.ComposeShader
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.youma.app.core.geometry.Quad
import com.youma.app.core.image.SourceImage
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskOptions
import com.youma.app.core.model.MaskPlan
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.MaskStyle
import com.youma.app.core.model.SensitiveKindLabels
import com.youma.app.export.PurposeWatermarkDrawer
import com.youma.app.render.RendererRegistry
import com.youma.app.ui.EditorUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 画布：图像 + 遮罩 + 叠层 + 手势。
 *
 * 遮罩用与导出**同一套** MaskRenderer 绘制，保证所见即所得。
 * 手势分工：一指画框、两指缩放平移、双击切换适配/放大、点手动框进选中态（框上方浮出「删除」）。
 *
 * @param scanStyle 识别动效的样式（设置里选的）。识别中跟着它走；结果到达那一刻定下来，落码 / 散开那一遍不再变
 */
@Composable
fun ImageCanvas(
    state: EditorUiState,
    registry: RendererRegistry,
    onTap: (PointF) -> Unit,
    onManualBox: (Quad) -> Unit,
    onResize: (Quad) -> Unit,
    onCommitDrag: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
    scanStyle: ScanStyle = ScanStyle.SWEEP,
) {
    val image = state.image ?: return
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val minBoxPx = with(density) { GestureRules.MIN_BOX_DP.dp.toPx() }

    var viewSize by remember { mutableStateOf(0 to 0) }
    var viewport by remember(image) { mutableStateOf(Viewport(1f, 0f, 0f)) }

    // 识别中的扫光 / 磨砂，与结果上屏的落码 / 散开（见 ScanEffect、FrostEffect）。时间轴只在绘制里读，
    // 走起来只触发重绘、不触发重组；识别中和收尾那一遍之外它是 null，一帧都不占。
    val scanning = state.analyzing
    val currentStyle by rememberUpdatedState(scanStyle)
    var scanTime by remember(image) { mutableStateOf<ScanTime?>(null) }
    // 收尾那一遍按结果到达那一刻的样式与位置走，中途不变
    var settling by remember(image) { mutableStateOf(ScanStyle.SWEEP) }
    var revealOrigin by remember(image) { mutableStateOf<PointF?>(null) }
    LaunchedEffect(image, scanning) {
        if (scanning) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { scanTime = ScanTime(it - start, null) }
        } else {
            // 没扫过就没有收尾这一遍：从重建里恢复的 plan 直接显示，不再演一遍
            val scanned = scanTime ?: return@LaunchedEffect
            settling = currentStyle
            // 磨砂从等待图标所在的位置散开，即画布中心；换算成图像坐标，用户此后拖动画面，洞也跟着图走
            revealOrigin = viewport.screenToImage(PointF(viewSize.first / 2f, viewSize.second / 2f))
            val settle = when (settling) {
                ScanStyle.SWEEP -> ScanEffect.settleMs(scanned.sinceScan)
                ScanStyle.FROST -> FrostEffect.SETTLE_MS
            }
            val resultAt = withFrameMillis { it }
            while (true) {
                val since = withFrameMillis { it - resultAt }
                if (since >= settle) break
                scanTime = ScanTime(scanned.sinceScan + since, since)
            }
            scanTime = null
        }
    }
    val scanPaints = remember { ScanPaints() }

    // 磨砂要用的模糊图与光点：选的是磨砂、动效又在走（识别中或收尾那一遍）时在后台准备一次（几毫秒）。
    // 准备好之前的那几帧只压暗，淡入刚开始，看不出差别。识别快到结果先到，也接着准备完，散开那一遍用得上。
    // 「动效在走」用 derivedStateOf 读：时间轴每帧都在变，直接读会每帧重组一次。
    var frostLayer by remember(image) { mutableStateOf<FrostLayer?>(null) }
    val animating by remember(image) { derivedStateOf { scanTime != null } }
    val wantsFrost = (scanning || animating) && (if (scanning) scanStyle else settling) == ScanStyle.FROST
    LaunchedEffect(image, wantsFrost, viewSize) {
        val (w, h) = viewSize
        if (!wantsFrost || frostLayer != null || w <= 0 || h <= 0) return@LaunchedEffect
        val fit = Viewport.fit(image.width, image.height, w, h)
        frostLayer = withContext(Dispatchers.Default) { FrostLayer.prepare(image.bitmap, density.density / fit.scale) }
    }
    val purposeWatermark = remember { PurposeWatermarkDrawer() }

    // pointerInput 的 block 只在 key 变化时重启，捕获的是重启那一刻的 state。
    // 手势循环里必须读这个「永远最新」的引用，否则点选手动框后立刻拖手柄，
    // 循环里看到的 selectedManualId 还是 null，于是拖出来一个新框而不是改选中框。
    val latest by rememberUpdatedState(state)

    // viewport 是按哪个画布尺寸摆的。用途水印面板顶替底栏、输入法弹起时画布会变矮，
    // 这时要重新摆，否则图的下半截就被挤出画布，调水印时看不到效果。
    var laidOutFor by remember(image) { mutableStateOf(0 to 0) }
    var draftQuad by remember { mutableStateOf<Quad?>(null) }
    // 正在拖选中框的手柄或框体。拖的时候收起工具条，免得它跟着框一路晃
    var editingSelection by remember(image) { mutableStateOf(false) }
    // 草稿框每一帧都在变；工具条只关心「是不是正在画」，不跟着每帧重组
    val drafting by remember { derivedStateOf { draftQuad != null } }
    // 双击自己数时间戳，不叠第二层 detectTapGestures：
    // 两层 pointerInput 里第二层根本收不到本层已在处理的手势（实机验证过），
    // 而把单击延后 300ms 去等第二击又会让「点遮罩」明显发滞。
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapAtScreen by remember { mutableStateOf(PointF(0f, 0f)) }

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

                        // 选中态优先：手柄 > 框体，都不中才轮到画框 / 点击。
                        // 删除在框上方的工具条里，那是叠在画布上的另一个控件，按它的手势到不了这里
                        val selected = latest.selectedManualId?.let { latest.plan.find(it) }
                        val downImage = viewport.screenToImage(PointF(down.position.x, down.position.y))
                        var grabbedHandle: HandleCorner? = null
                        var grabOffset = PointF(0f, 0f)
                        var grabbingBody = false
                        if (selected != null) {
                            val hitSize = HANDLE_HIT_DP * density.density / viewport.scale
                            grabbedHandle = SelectionHandles.hitHandle(selected.quad, downImage, hitSize)
                            grabbingBody = grabbedHandle == null && selected.quad.contains(downImage)
                            // 按下的地方离角有多远：拖起来角跟着手指平移，不会一动就跳到手指底下
                            grabbedHandle?.let {
                                val corner = SelectionHandles.cornerPoint(selected.quad, it)
                                grabOffset = PointF(corner.x - downImage.x, corner.y - downImage.y)
                            }
                        }

                        val start = down.position
                        var last = down.position
                        var pointerCount = 1
                        var mode = Mode.UNDECIDED
                        val downTime = System.currentTimeMillis()

                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.filter { it.pressed }
                            pointerCount = maxOf(pointerCount, pressed.size)

                            if (pressed.size >= 2) {
                                // 两指：导航。已经开始画的草稿作废——用户改主意了。
                                // 此后这一手势剩下的单指部分不再改框，也不画框
                                mode = Mode.NAVIGATE
                                draftQuad = null
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
                                    mode = Mode.DRAG
                                }
                                // 过了 touch slop 才动手，没动的话这一下是点击。
                                // 改框一律按起手那一刻的框加手指的总位移算：框紧跟手指，不丢 slop 那一段
                                if (mode == Mode.DRAG) {
                                    val now = viewport.screenToImage(PointF(c.position.x, c.position.y))
                                    val handle = grabbedHandle
                                    if (selected != null && handle != null) {
                                        editingSelection = true
                                        val to = PointF(now.x + grabOffset.x, now.y + grabOffset.y)
                                        onResize(SelectionHandles.resize(selected.quad, handle, to, minBoxPx / viewport.scale))
                                    } else if (selected != null && grabbingBody) {
                                        editingSelection = true
                                        onResize(SelectionHandles.move(selected.quad, now.x - downImage.x, now.y - downImage.y))
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
                        // 整段拖动只压一个快照。没真拖起来的话 ViewModel 那边什么也没记，这一下什么都不做
                        if (grabbedHandle != null || grabbingBody) onCommitDrag()

                        val duration = System.currentTimeMillis() - downTime
                        when {
                            mode == Mode.DRAG && grabbedHandle == null && !grabbingBody -> {
                                val q = draftQuad
                                draftQuad = null
                                val minInImage = minBoxPx / viewport.scale
                                if (q != null && GestureRules.acceptsBox(q, minInImage)) onManualBox(q)
                            }
                            // 点了一下手柄没拖：什么都不做。手柄的命中区探出框外，当成点空白会把选中态点没了
                            mode == Mode.UNDECIDED && pointerCount == 1 && grabbedHandle == null -> {
                                val p = viewport.screenToImage(PointF(last.x, last.y))
                                val now = System.currentTimeMillis()
                                // 按得久的一下也是点击（Android 上没有长按动作的控件都这样），只是不拿来凑双击
                                val quick = duration < LONG_PRESS_MS
                                val isSecond = quick && now - lastTapAt <= DOUBLE_TAP_MS &&
                                    hypot(last.x - lastTapAtScreen.x, last.y - lastTapAtScreen.y) <= touchSlopPx * 2f
                                if (isSecond) {
                                    // 第二击只做缩放，不再切换遮罩状态——第一击已经切过一次了
                                    viewport = viewport.doubleTapTarget(
                                        PointF(last.x, last.y),
                                        image.width, image.height, viewSize.first, viewSize.second,
                                    )
                                    lastTapAt = 0L
                                } else {
                                    onTap(p)
                                    lastTapAt = if (quick) now else 0L
                                    lastTapAtScreen = PointF(last.x, last.y)
                                }
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
                laidOutFor = viewSize
            }

            drawIntoCanvas { compose ->
                val canvas = compose.nativeCanvas
                val save = canvas.save()
                canvas.concat(viewport.matrix())

                // 识别中，时间轴的第一帧还没到（或手里还是上一遍收尾的帧）也按「识别中」画：
                // 换方案重跑时旧结果不该闪一下
                val time = if (scanning) scanTime?.takeIf { it.sinceResult == null } ?: ScanTime.START else scanTime
                val look = when (if (scanning) scanStyle else settling) {
                    ScanStyle.SWEEP -> ScanLook.Sweep
                    ScanStyle.FROST -> ScanLook.Frost(frostLayer, revealOrigin)
                }
                drawScene(canvas, image, state.plan, registry, viewport.scale, density.density, time, scanPaints, look)
                // 用途水印与导出用同一个 drawer，尺寸按短边比例算，画在分析图上与导出图上观感一致。
                // 不预览的话，设好文案后画面毫无变化，看起来就像这个功能没生效。
                state.purposeText?.let {
                    purposeWatermark.draw(canvas, image.width, image.height, it, state.purposeStyle)
                }
                val dp = density.density / viewport.scale
                state.selectedManualId?.let { id ->
                    state.plan.find(id)?.let { drawSelection(canvas, it.quad, selectionColors, dp) }
                }
                draftQuad?.let { drawDraft(canvas, it, selectionColors, dp) }

                canvas.restoreToCount(save)

                // 磨砂中间的等待图标跟屏幕走：总在画布中心、大小恒定，不随图缩放平移
                if (time != null && look is ScanLook.Frost) {
                    drawLoader(canvas, size.width / 2f, size.height / 2f, density.density, time, scanPaints.frost)
                }
            }
        }

        // 选中框上方的「删除」。淡出的那一下（删掉了、开始拖了）框可能已经不在 plan 里，按它最后的位置摆
        val selectedQuad = state.selectedManualId?.let { state.plan.find(it) }?.quad
        val lastSelectedQuad = remember { QuadHolder() }
        if (selectedQuad != null) lastSelectedQuad.quad = selectedQuad
        AnimatedVisibility(
            visible = selectedQuad != null && !editingSelection && !drafting,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.matchParentSize(),
        ) {
            SelectionToolbar(
                quad = selectedQuad ?: lastSelectedQuad.quad,
                viewport = { viewport },
                onDelete = onDeleteSelected,
            )
        }
    }
}

/**
 * 选中手动框时浮在框上方的工具条。与应用里其余的按钮一样是 Material 组件——主题色、字体、
 * 按下时的水波纹都一致，不在画布上自己画。只有「删除」一个操作：移动、调大小直接在框上拖。
 *
 * 这一层铺满画布，但只有工具条本身接触摸；别处的手势照常落到底下的画布上。
 */
@Composable
private fun SelectionToolbar(quad: Quad?, viewport: () -> Viewport, onDelete: () -> Unit) {
    Layout(
        content = {
            // 与 Material 的菜单、文字选择工具条同一种容器：surfaceContainer 底色加一层浅投影
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
                TextButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("删除")
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

/** 记住最后一个选中框的位置，供工具条淡出时用。普通字段，改它不触发重组。 */
private class QuadHolder { var quad: Quad? = null }

private enum class Mode { UNDECIDED, DRAG, NAVIGATE }
private const val LONG_PRESS_MS = 450L
private const val DOUBLE_TAP_MS = 300L

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

/** 2dp 虚线框（琥珀色）—— 圈出未打码的外观（spec §7.2）。线宽按缩放反算，保持视觉恒定。 */
private fun outlinePaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.rgb(0xFF, 0xB3, 0x00)
    strokeWidth = 4f / scale
    pathEffect = DashPathEffect(floatArrayOf(12f / scale, 8f / scale), 0f)
}

/** 选中框、草稿框与手柄的颜色，取自主题（见 YoumaTheme）。 */
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

/** 选中框：主题色实线，四角是可以拖的手柄。 */
private fun drawSelection(canvas: android.graphics.Canvas, quad: Quad, colors: SelectionColors, dp: Float) {
    val path = quad.toPath()
    canvas.drawPath(path, haloPaint(colors, dp))
    canvas.drawPath(path, linePaint(colors, dp))
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

/** 类型小标签的文字：模型猜的与规则命中区分开（spec §3），Gemini Nano 的结果标「AI」。 */
private fun kindLabelText(item: MaskItem) =
    SensitiveKindLabels.display(item.kind) + if (item.source == DetectorSource.LLM) " · AI" else ""

/** 琥珀色小标签的画笔与尺寸。字号按缩放反算，视觉大小恒定。 */
private class LabelPaints(val scale: Float) {
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        textSize = 26f / scale
    }
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(0xFF, 0xB3, 0x00)
        style = Paint.Style.FILL
    }
    val padding = 6f / scale
    val height = text.textSize + padding * 2
    fun width(label: String) = text.measureText(label) + padding * 2
}

/**
 * 给所有圈出框的类型标签找位置（见 LabelLayout）：贴在框四周，避开画面上所有的框
 * 和先摆好的标签。从上到下、从左到右依次摆，同一个 plan 每帧摆出来都一样，不会跳。
 *
 * 缩放比 < 0.5 时不画标签，返回空：避免密集截图上标签糊成一片。
 */
internal fun layoutKindLabels(items: List<MaskItem>, image: SourceImage, scale: Float): Map<String, RectF> {
    if (scale < GestureRules.LABEL_MIN_SCALE) return emptyMap()
    val outlined = items.withIndex()
        .filter { it.value.state == MaskState.OUTLINED }
        .sortedWith(compareBy({ it.value.quad.bounds().top }, { it.value.quad.bounds().left }))
    if (outlined.isEmpty()) return emptyMap()
    val paints = LabelPaints(scale)
    val rects = LabelLayout.place(
        boxes = items.map { it.quad.bounds() },
        requests = outlined.map { LabelLayout.Request(it.index, paints.width(kindLabelText(it.value)), paints.height) },
        area = RectF(0f, 0f, image.width.toFloat(), image.height.toFloat()),
        gap = 2f / scale,
    )
    return outlined.zip(rects) { item, rect -> item.value.candidateId to rect }.toMap()
}

private fun drawKindLabel(canvas: android.graphics.Canvas, item: MaskItem, rect: RectF, paints: LabelPaints) {
    val scale = paints.scale
    canvas.drawRoundRect(rect, 4f / scale, 4f / scale, paints.bg)
    canvas.drawText(
        kindLabelText(item), rect.left + paints.padding,
        rect.bottom - paints.padding - paints.text.descent() * 0.5f, paints.text,
    )
}

/**
 * 图像、遮罩与扫描动效——画布上除了选中态、草稿框和磨砂的等待图标之外的全部内容。拆出来是为了
 * 让像素测试能直接画出动效的任意一帧。
 *
 * 调用前画布已经 concat 了 viewport：这里一律是图像坐标，dp 尺寸按 [scale] 反算。
 *
 * @param time 动效时间轴上的这一帧（见 ScanEffect、FrostEffect）；null 表示静止，照常画全部遮罩
 * @param look 按哪种样式画这一帧
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
) {
    canvas.drawBitmap(image.bitmap, 0f, 0f, null)

    // 预览画在分析图上；渲染器里的绝对像素常数是原图口径，按 image.scale 折算，
    // 否则大图上预览会比导出更糊（见 MaskOptions.renderScale）。
    val options = plan.options.copy(renderScale = image.scale)

    // 面积大的先画，小的盖在上面 —— 与 GestureRules.hitTest 的「取最小」互为对应
    val sorted = plan.items.sortedByDescending { it.quad.area() }
    // 标签按整张 plan 一起摆：识别中/落码时打码块分两批画，标签之间也得互相避让
    val labels = layoutKindLabels(sorted, image, scale)
    if (time == null) {
        drawItems(canvas, sorted, plan.style, image, options, registry, scale, labels)
        return
    }

    val dp = density / scale
    if (look is ScanLook.Frost) {
        val (detected, manual) = sorted.partition { it.source != DetectorSource.MANUAL }
        drawFrostScene(
            canvas, image.width.toFloat(), image.height.toFloat(), dp, time, look, paints.frost,
            drawDetected = { drawItems(canvas, detected, plan.style, image, options, registry, scale, labels) },
            drawManual = { drawItems(canvas, manual, plan.style, image, options, registry, scale, labels) },
        )
        return
    }

    val height = image.height.toFloat()
    val trail = ScanEffect.TRAIL_DP * dp
    val lead = ScanEffect.LEAD_DP * dp
    val light = LightSpec(image.width, height, trail, lead, ScanEffect.HUE_SPAN_DP * dp, ScanEffect.drift(time.sinceScan))
    val (detected, manual) = sorted.partition { it.source != DetectorSource.MANUAL }
    val feather = ScanEffect.FEATHER_DP * dp
    // 结果到达后，循环光先提速走完手头这一趟，落码那一遍紧接着从顶上开始
    val front = ScanEffect.revealMs(time.sinceScan, time.sinceResult)?.let { ScanEffect.revealFront(it, height, lead, trail, feather) }

    // 暗幕压在原图上、打码块底下：落码时块与原图的亮度一起自上而下回来
    drawScrim(canvas, image.width.toFloat(), height, time.sinceScan, front, feather, paints)

    // 识别中（以及结果到了、循环光还在走完那一趟时），识别出的块一律不画：换方案重跑时旧结果先退场，不与扫光混在一起。
    // 落码中，它们画进一个离屏图层，只留光已经走过的那部分。
    if (front != null) {
        val layer = canvas.saveLayer(null, null)
        drawItems(canvas, detected, plan.style, image, options, registry, scale, labels)
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
    // 手动框是用户自己画的，识别期间也一直在，清楚地浮在光上面
    drawItems(canvas, manual, plan.style, image, options, registry, scale, labels)
}

/** 先画打码块，再画圈出框：圈出框（还没打码的）永远在打码块之上，不会被盖住。 */
private fun drawItems(
    canvas: android.graphics.Canvas,
    items: List<MaskItem>,
    style: MaskStyle,
    image: SourceImage,
    options: MaskOptions,
    registry: RendererRegistry,
    scale: Float,
    labels: Map<String, RectF>,
) {
    items.filter { it.state == MaskState.MASKED }.forEach {
        registry[style].render(canvas, image.bitmap, it.quad, options)
    }
    items.filter { it.state == MaskState.OUTLINED }.forEach { item ->
        canvas.drawPath(item.quad.toPath(), outlinePaint(scale))
    }
    // 标签在全部描边之后画：贴在别的框外侧的标签不会被后画的描边划过
    val labelPaints = LabelPaints(scale)
    items.filter { it.state == MaskState.OUTLINED }.forEach { item ->
        labels[item.candidateId]?.let { drawKindLabel(canvas, item, it, labelPaints) }
    }
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
 * 中间按 ScanEffect.coverage 渐变。蒙版的范围比图像大出一圈，探出图像边的标签与描边也一样处理。
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
