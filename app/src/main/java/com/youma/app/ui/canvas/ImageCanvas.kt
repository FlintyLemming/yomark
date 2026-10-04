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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
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
import com.youma.app.render.RendererRegistry
import com.youma.app.ui.EditorUiState
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 画布：图像 + 遮罩 + 叠层 + 手势。
 *
 * 遮罩用与导出**同一套** MaskRenderer 绘制，保证所见即所得。
 * 手势分工：一指画框、两指缩放平移、双击切换适配/放大、长按手动框进选中态。
 */
@Composable
fun ImageCanvas(
    state: EditorUiState,
    registry: RendererRegistry,
    onTap: (PointF) -> Unit,
    onLongPress: (PointF) -> Unit,
    onManualBox: (Quad) -> Unit,
    onResize: (Quad) -> Unit,
    onCommitDrag: () -> Unit,
    onDeleteSelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = state.image ?: return
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val minBoxPx = with(density) { GestureRules.MIN_BOX_DP.dp.toPx() }

    // 识别中的扫光与结果上屏的落码（见 ScanEffect）。时间轴只在绘制里读，走起来只触发重绘、
    // 不触发重组；识别中和落码那一遍之外它是 null，一帧都不占。
    val scanning = state.analyzing
    var scanTime by remember(image) { mutableStateOf<ScanTime?>(null) }
    LaunchedEffect(image, scanning) {
        if (scanning) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { scanTime = ScanTime(it - start, null) }
        } else {
            // 没扫过就没有落码这一遍：从重建里恢复的 plan 直接显示，不再演一遍
            val scanned = scanTime ?: return@LaunchedEffect
            val resultAt = withFrameMillis { it }
            while (true) {
                val since = withFrameMillis { it - resultAt }
                if (since >= ScanEffect.REVEAL_MS) break
                scanTime = ScanTime(scanned.sinceScan + since, since)
            }
            scanTime = null
        }
    }
    val scanPaints = remember { ScanPaints() }

    // pointerInput 的 block 只在 key 变化时重启，捕获的是重启那一刻的 state。
    // 手势循环里必须读这个「永远最新」的引用，否则长按选中后立刻拖手柄，
    // 循环里看到的 selectedManualId 还是 null，于是拖出来一个新框而不是改选中框。
    val latest by rememberUpdatedState(state)

    var viewSize by remember { mutableStateOf(0 to 0) }
    var viewport by remember(image) { mutableStateOf(Viewport(1f, 0f, 0f)) }
    var draftQuad by remember { mutableStateOf<Quad?>(null) }
    // 双击自己数时间戳，不叠第二层 detectTapGestures：
    // 两层 pointerInput 里第二层根本收不到本层已在处理的手势（实机验证过），
    // 而把单击延后 300ms 去等第二击又会让「点遮罩」明显发滞。
    var lastTapAt by remember { mutableLongStateOf(0L) }
    var lastTapAtScreen by remember { mutableStateOf(PointF(0f, 0f)) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()          // 放大后图像会溢出画布区，压到顶栏上
            .pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // 选中态优先：删除按钮 > 手柄 > 框体，都不中才轮到画框/点击
                    val selected = latest.selectedManualId?.let { latest.plan.find(it) }
                    val handleSize = HANDLE_DP_PX / viewport.scale
                    val downImage = viewport.screenToImage(PointF(down.position.x, down.position.y))
                    var grabbedHandle: HandleCorner? = null
                    var grabbingBody = false
                    if (selected != null) {
                        if (SelectionHandles.deleteButtonRect(selected.quad, handleSize * 1.4f)
                                .contains(downImage.x, downImage.y)
                        ) {
                            onDeleteSelected()
                            return@awaitEachGesture
                        }
                        grabbedHandle = SelectionHandles.hitHandle(selected.quad, downImage, handleSize)
                        grabbingBody = grabbedHandle == null && selected.quad.contains(downImage)
                    }

                    val start = down.position
                    var last = down.position
                    var totalDx: Float
                    var totalDy: Float
                    var pointerCount = 1
                    var mode = Mode.UNDECIDED
                    val downTime = System.currentTimeMillis()

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val pressed = event.changes.filter { it.pressed }
                        pointerCount = maxOf(pointerCount, pressed.size)

                        if (pressed.size >= 2) {
                            // 两指：导航。已经开始画的草稿作废——用户改主意了。
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
                            totalDx = c.position.x - start.x
                            totalDy = c.position.y - start.y
                            if (mode == Mode.UNDECIDED && GestureRules.isDrag(totalDx, totalDy, touchSlopPx)) {
                                mode = Mode.DRAW
                            }
                            if (grabbedHandle != null && selected != null) {
                                val to = viewport.screenToImage(PointF(c.position.x, c.position.y))
                                onResize(
                                    SelectionHandles.resize(
                                        latest.plan.find(selected.candidateId)?.quad ?: selected.quad,
                                        grabbedHandle, to, minBoxPx / viewport.scale,
                                    )
                                )
                                if (c.positionChanged()) c.consume()
                            } else if (grabbingBody && selected != null) {
                                val prev = viewport.screenToImage(PointF(c.previousPosition.x, c.previousPosition.y))
                                val now = viewport.screenToImage(PointF(c.position.x, c.position.y))
                                onResize(
                                    SelectionHandles.move(
                                        latest.plan.find(selected.candidateId)?.quad ?: selected.quad,
                                        now.x - prev.x, now.y - prev.y,
                                    )
                                )
                                if (c.positionChanged()) c.consume()
                            } else if (mode == Mode.DRAW) {
                                draftQuad = GestureRules.quadFromDrag(
                                    viewport.screenToImage(PointF(start.x, start.y)),
                                    viewport.screenToImage(PointF(c.position.x, c.position.y)),
                                )
                                if (c.positionChanged()) c.consume()
                            }
                            last = c.position
                        }

                        if (event.changes.all { !it.pressed }) break
                    }

                    if (grabbedHandle != null || grabbingBody) {
                        onCommitDrag()               // 整段拖动只压一个快照
                        return@awaitEachGesture
                    }

                    val duration = System.currentTimeMillis() - downTime
                    when {
                        mode == Mode.DRAW -> {
                            val q = draftQuad
                            draftQuad = null
                            val minInImage = minBoxPx / viewport.scale
                            if (q != null && GestureRules.acceptsBox(q, minInImage)) onManualBox(q)
                        }
                        mode == Mode.UNDECIDED && pointerCount == 1 -> {
                            val p = viewport.screenToImage(PointF(last.x, last.y))
                            if (duration >= LONG_PRESS_MS) {
                                onLongPress(p)
                                lastTapAt = 0L
                            } else {
                                val now = System.currentTimeMillis()
                                val isSecond = now - lastTapAt <= DOUBLE_TAP_MS &&
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
                                    lastTapAt = now
                                    lastTapAtScreen = PointF(last.x, last.y)
                                }
                            }
                        }
                    }
                }
            }
    ) {
        viewSize = size.width.toInt() to size.height.toInt()
        if (viewport.scale == 1f && viewport.offsetX == 0f && viewport.offsetY == 0f) {
            viewport = Viewport.fit(image.width, image.height, viewSize.first, viewSize.second)
        }

        drawIntoCanvas { compose ->
            val canvas = compose.nativeCanvas
            val save = canvas.save()
            canvas.concat(viewport.matrix())

            drawScene(
                canvas, image, state.plan, registry, viewport.scale, density.density,
                // 识别中，时间轴的第一帧还没到（或手里还是上一遍落码的帧）也按「识别中」画：
                // 换方案重跑时旧结果不该闪一下
                time = if (scanning) scanTime?.takeIf { it.sinceResult == null } ?: ScanTime.START else scanTime,
                paints = scanPaints,
            )
            state.selectedManualId?.let { id ->
                state.plan.find(id)?.let { item ->
                    canvas.drawPath(item.quad.toPath(), selectionPaint(viewport.scale))
                    val size = HANDLE_DP_PX / viewport.scale
                    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.FILL; color = AndroidColor.WHITE
                    }
                    val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE; color = AndroidColor.BLACK
                        strokeWidth = 2f / viewport.scale
                    }
                    SelectionHandles.handleRects(item.quad, size).values.forEach { r ->
                        canvas.drawRect(r, fill)
                        canvas.drawRect(r, edge)
                    }
                    // 删除按钮：白底红叉
                    val del = SelectionHandles.deleteButtonRect(item.quad, size * 1.4f)
                    canvas.drawOval(del, fill)
                    canvas.drawOval(del, edge)
                    val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = AndroidColor.rgb(0xD3, 0x2F, 0x2F)
                        strokeWidth = 3f / viewport.scale
                    }
                    val pad = del.width() * 0.28f
                    canvas.drawLine(del.left + pad, del.top + pad, del.right - pad, del.bottom - pad, cross)
                    canvas.drawLine(del.right - pad, del.top + pad, del.left + pad, del.bottom - pad, cross)
                }
            }
            draftQuad?.let { canvas.drawPath(it.toPath(), draftPaint(viewport.scale)) }

            canvas.restoreToCount(save)
        }
    }
}

private enum class Mode { UNDECIDED, DRAW, NAVIGATE }
private const val LONG_PRESS_MS = 450L
private const val DOUBLE_TAP_MS = 300L

/** 手柄边长（图像坐标下按缩放反算，视觉上恒定）。 */
private const val HANDLE_DP_PX = 22f

/** 2dp 虚线框（琥珀色）—— 圈出未打码的外观（spec §7.2）。线宽按缩放反算，保持视觉恒定。 */
private fun outlinePaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.rgb(0xFF, 0xB3, 0x00)
    strokeWidth = 4f / scale
    pathEffect = DashPathEffect(floatArrayOf(12f / scale, 8f / scale), 0f)
}

private fun selectionPaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.WHITE
    strokeWidth = 3f / scale
}

private fun draftPaint(scale: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = AndroidColor.argb(200, 255, 255, 255)
    strokeWidth = 3f / scale
    pathEffect = DashPathEffect(floatArrayOf(10f / scale, 6f / scale), 0f)
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
 * 图像、遮罩与扫光——画布上除了选中态和草稿框之外的全部内容。拆出来是为了让像素测试
 * 能直接画出动效的任意一帧。
 *
 * 调用前画布已经 concat 了 viewport：这里一律是图像坐标，dp 尺寸按 [scale] 反算。
 *
 * @param time 扫光时间轴上的这一帧（见 ScanEffect）；null 表示静止，照常画全部遮罩
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
    val height = image.height.toFloat()
    val trail = ScanEffect.TRAIL_DP * dp
    val lead = ScanEffect.LEAD_DP * dp
    val light = LightSpec(image.width, height, trail, lead, ScanEffect.HUE_SPAN_DP * dp, ScanEffect.drift(time.sinceScan))
    val (detected, manual) = sorted.partition { it.source != DetectorSource.MANUAL }
    val feather = ScanEffect.FEATHER_DP * dp
    val front = time.sinceResult?.let { ScanEffect.revealFront(it, height, lead, trail, feather) }

    // 暗幕压在原图上、打码块底下：落码时块与原图的亮度一起自上而下回来
    drawScrim(canvas, image.width.toFloat(), height, time.sinceScan, front, feather, paints)

    // 识别中，识别出的块一律不画：换方案重跑时旧结果先退场，不与扫光混在一起。
    // 落码中，它们画进一个离屏图层，只留光已经走过的那部分。
    if (front != null) {
        val layer = canvas.saveLayer(null, null)
        drawItems(canvas, detected, plan.style, image, options, registry, scale, labels)
        keepRevealed(canvas, front, feather, image.width.toFloat(), height, paints)
        canvas.restoreToCount(layer)
        drawLight(canvas, light, front, strength = 1f, paints)
    }
    drawLight(
        canvas, light,
        ScanEffect.loopCenter(time.sinceScan, height, lead, trail),
        ScanEffect.loopStrength(time.sinceScan, time.sinceResult),
        paints,
    )
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

/** 扫光与落码的画笔，跨帧复用。 */
internal class ScanPaints {
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
