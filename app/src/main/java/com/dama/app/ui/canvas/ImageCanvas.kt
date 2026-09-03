package com.dama.app.ui.canvas

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import com.dama.app.core.geometry.Quad
import com.dama.app.core.model.MaskState
import com.dama.app.render.RendererRegistry
import com.dama.app.ui.EditorUiState

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
    onDoubleTap: (PointF, Int, Int) -> Unit,
    onLongPress: (PointF) -> Unit,
    onManualBox: (Quad) -> Unit,
    modifier: Modifier = Modifier,
) {
    val image = state.image ?: return
    val density = LocalDensity.current
    val touchSlopPx = LocalViewConfiguration.current.touchSlop
    val minBoxPx = with(density) { GestureRules.MIN_BOX_DP.dp.toPx() }

    var viewSize by remember { mutableStateOf(0 to 0) }
    var viewport by remember(image) { mutableStateOf(Viewport(1f, 0f, 0f)) }
    var draftQuad by remember { mutableStateOf<Quad?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(image) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
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
                            if (mode == Mode.DRAW) {
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
                            if (duration >= LONG_PRESS_MS) onLongPress(p) else onTap(p)
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

            // 面积大的先画，小的盖在上面 —— 与 GestureRules.hitTest 的「取最小」互为对应
            val sorted = state.plan.items.sortedByDescending { it.quad.area() }
            sorted.filter { it.state == MaskState.MASKED }.forEach {
                registry[state.plan.style].render(canvas, image.bitmap, it.quad, state.plan.options)
            }
            sorted.filter { it.state == MaskState.OUTLINED }.forEach {
                canvas.drawPath(it.quad.toPath(), outlinePaint(viewport.scale))
            }
            state.selectedManualId?.let { id ->
                state.plan.find(id)?.let { canvas.drawPath(it.quad.toPath(), selectionPaint(viewport.scale)) }
            }
            draftQuad?.let { canvas.drawPath(it.toPath(), draftPaint(viewport.scale)) }

            canvas.restoreToCount(save)
        }
    }
}

private enum class Mode { UNDECIDED, DRAW, NAVIGATE }
private const val LONG_PRESS_MS = 450L

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
