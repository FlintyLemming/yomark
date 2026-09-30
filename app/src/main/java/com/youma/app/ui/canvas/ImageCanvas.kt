package com.youma.app.ui.canvas

import android.graphics.Color as AndroidColor
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
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
import com.youma.app.core.model.MaskItem
import com.youma.app.core.model.MaskState
import com.youma.app.core.model.DetectorSource
import com.youma.app.core.model.SensitiveKindLabels
import com.youma.app.render.RendererRegistry
import com.youma.app.ui.EditorUiState
import kotlin.math.hypot

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

            canvas.drawBitmap(image.bitmap, 0f, 0f, null)

            // 预览画在分析图上；渲染器里的绝对像素常数是原图口径，按 image.scale 折算，
            // 否则大图上预览会比导出更糊（见 MaskOptions.renderScale）。
            val options = state.plan.options.copy(renderScale = image.scale)

            // 面积大的先画，小的盖在上面 —— 与 GestureRules.hitTest 的「取最小」互为对应
            val sorted = state.plan.items.sortedByDescending { it.quad.area() }
            sorted.filter { it.state == MaskState.MASKED }.forEach {
                registry[state.plan.style].render(canvas, image.bitmap, it.quad, options)
            }
            sorted.filter { it.state == MaskState.OUTLINED }.forEach { item ->
                canvas.drawPath(item.quad.toPath(), outlinePaint(viewport.scale))
                // 类型小标签只在缩放比 ≥ 0.5 时绘制，避免密集截图上标签糊成一片
                if (viewport.scale >= GestureRules.LABEL_MIN_SCALE) {
                    drawKindLabel(canvas, item, viewport.scale)
                }
            }
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

/** 琥珀色小标签，贴在圈出框的左上角外侧。字号按缩放反算，视觉大小恒定。 */
private fun drawKindLabel(canvas: android.graphics.Canvas, item: MaskItem, scale: Float) {
    // 模型猜的与规则命中区分开（spec §3）：Gemini Nano 的结果标「AI」
    val text = SensitiveKindLabels.display(item.kind) + if (item.source == DetectorSource.LLM) " · AI" else ""
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        textSize = 26f / scale
    }
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.rgb(0xFF, 0xB3, 0x00)
        style = Paint.Style.FILL
    }
    val b = item.quad.bounds()
    val padding = 6f / scale
    val w = textPaint.measureText(text) + padding * 2
    val h = textPaint.textSize + padding * 2
    val top = (b.top - h - 2f / scale).coerceAtLeast(0f)
    val rect = android.graphics.RectF(b.left, top, b.left + w, top + h)
    canvas.drawRoundRect(rect, 4f / scale, 4f / scale, bg)
    canvas.drawText(text, rect.left + padding, rect.bottom - padding - textPaint.descent() * 0.5f, textPaint)
}
