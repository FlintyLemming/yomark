package com.yomark.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 选颜色：一排色板，后面是「自定义」和吸管，排不下就折到第二行——不横着滑，吸管不会藏在屏幕外面。
 *
 * 自定义点开是色相、饱和度、亮度三条滑条；吸管点一下，再点图上任意一处，就取那里的颜色，
 * 想让色块和底色融成一片时最好用。当前颜色不在色板上时，「自定义」那一格是选中的，里面画着这个颜色。
 *
 * @param onColor 选了颜色。inProgress = 自定义的滑条还没松手，松手时另有 [onColorFinished]。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColorPicker(
    color: Int,
    palette: List<NamedColor>,
    picking: Boolean,
    onColor: (rgb: Int, inProgress: Boolean) -> Unit,
    onColorFinished: () -> Unit,
    onEyedropper: () -> Unit,
) {
    val custom = palette.none { it.argb == color }
    var customOpen by remember { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        palette.forEach { c ->
            ColorSwatch(
                color = Color(c.argb),
                name = c.name,
                selected = c.argb == color,
                onClick = { onColor(c.argb, false) },
            )
        }
        CustomColorSwatch(color = Color(color), selected = custom, onClick = { customOpen = !customOpen })
        EyedropperButton(active = picking, onClick = onEyedropper)
    }
    AnimatedVisibility(visible = customOpen) {
        HsvSliders(
            color = color,
            onChange = { onColor(it, true) },
            onFinished = onColorFinished,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * 「自定义」那一格：一圈彩虹。当前颜色不在色板上时它是选中的，圈里填着这个颜色。
 */
@Composable
private fun CustomColorSwatch(color: Color, selected: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(
        Modifier
            .size(SWATCH)
            .semantics {
                contentDescription = "自定义颜色"
                this.selected = selected
            }
            .clickable(role = Role.Button, onClick = onClick)
            .drawBehind {
                val stroke = RAINBOW_RING.toPx()
                val r = size.minDimension / 2f
                if (selected) drawCircle(primary, radius = r)
                val ring = if (selected) r - 3.dp.toPx() else r
                drawCircle(Brush.sweepGradient(RAINBOW), radius = ring - stroke / 2f, style = Stroke(stroke))
                drawCircle(surface, radius = ring - stroke)
                if (selected) drawCircle(color, radius = ring - stroke - 2.dp.toPx())
            },
    )
}

/** 吸管：点一下进入取色，再点图上任意一处取那里的颜色。取色中垫一层主题色。 */
@Composable
private fun EyedropperButton(active: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(SWATCH)
            .border(if (active) 3.dp else 1.dp, if (active) colors.primary else colors.outlineVariant, CircleShape)
            .background(if (active) colors.primaryContainer else Color.Transparent, CircleShape)
            .semantics {
                contentDescription = "从图上取色"
                stateDescription = if (active) "正在取色" else ""
            }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Colorize,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (active) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
    }
}

/**
 * 自定义颜色的三条滑条：色相、饱和度、亮度。每条的轨道画的就是拖过去会得到的颜色。
 *
 * HSV 由这里自己记着，不从颜色反推：饱和度拖到 0 时颜色成了灰的，色相再也算不回来，
 * 从颜色反推的话色相滑块会一下子跳回红色。外面换了颜色（点了色板、吸管取了色）才重新对上；
 * 拖动中不对——拖出去的颜色绕一圈回到这里，会比手指慢一拍，对上了滑块就往回跳。
 */
@Composable
internal fun HsvSliders(
    color: Int,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var hsv by remember { mutableStateOf(Hsv.of(color)) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(color, dragging) { if (!dragging && hsv.toArgb() != color) hsv = Hsv.of(color) }
    fun set(next: Hsv) {
        dragging = true
        hsv = next
        onChange(next.toArgb())
    }
    val finish = {
        dragging = false
        onFinished()
    }
    Column(modifier) {
        GradientSlider(
            label = "色相",
            value = hsv.h / 360f,
            track = RAINBOW,
            thumb = Color(hsv.copy(s = 1f, v = 1f).toArgb()),
            onChange = { set(hsv.copy(h = it * 360f)) },
            onFinished = finish,
        )
        GradientSlider(
            label = "饱和度",
            value = hsv.s,
            track = listOf(Color(hsv.copy(s = 0f).toArgb()), Color(hsv.copy(s = 1f).toArgb())),
            thumb = Color(hsv.toArgb()),
            onChange = { set(hsv.copy(s = it)) },
            onFinished = finish,
        )
        GradientSlider(
            label = "亮度",
            value = hsv.v,
            track = listOf(Color.Black, Color(hsv.copy(v = 1f).toArgb())),
            thumb = Color(hsv.toArgb()),
            onChange = { set(hsv.copy(v = it)) },
            onFinished = finish,
        )
    }
}

/**
 * 轨道是一道渐变的滑条。Material 的 Slider 换不了轨道的画法，这里自己画；
 * 读屏照样能读出、能调进度（progressBarRangeInfo / setProgress）。
 */
@Composable
private fun GradientSlider(
    label: String,
    value: Float,
    track: List<Color>,
    thumb: Color,
    onChange: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    val finished by rememberUpdatedState(onFinished)
    val ring = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(PanelLabelWidth))
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics {
                    contentDescription = label
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(0f, 1f), 0f..1f)
                    setProgress { target ->
                        change(target.coerceIn(0f, 1f))
                        finished()
                        true
                    }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val inset = THUMB_RADIUS.toPx()
                        fun at(x: Float) = ((x - inset) / (size.width - 2f * inset)).coerceIn(0f, 1f)
                        change(at(down.position.x))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (c.positionChanged()) {
                                change(at(c.position.x))
                                c.consume()
                            }
                        }
                        finished()
                    }
                }
                .drawBehind {
                    val inset = THUMB_RADIUS.toPx()
                    val h = TRACK_HEIGHT.toPx()
                    val left = inset - h / 2f
                    val right = size.width - inset + h / 2f
                    val top = (size.height - h) / 2f
                    drawRoundRect(
                        Brush.horizontalGradient(track, startX = inset, endX = size.width - inset),
                        topLeft = Offset(left, top),
                        size = Size(right - left, h),
                        cornerRadius = CornerRadius(h / 2f),
                    )
                    val center = Offset(inset + value.coerceIn(0f, 1f) * (size.width - 2f * inset), size.height / 2f)
                    drawCircle(Color.White, radius = inset, center = center)
                    drawCircle(thumb, radius = inset - 3.dp.toPx(), center = center)
                    drawCircle(ring, radius = inset, center = center, style = Stroke(1.dp.toPx()))
                },
        )
    }
}

/**
 * 色相、饱和度、亮度，各在 [0, 360)、[0, 1]、[0, 1]。自己换算，不走 android.graphics.Color.colorToHSV：
 * 那是 native 方法，单测里跑不了；算法本身几行就够。
 */
internal data class Hsv(val h: Float, val s: Float, val v: Float) {

    /** 不透明的 ARGB。 */
    fun toArgb(): Int {
        val c = v * s
        val hp = (h.coerceIn(0f, 360f) / 60f) % 6f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r, g, b) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    companion object {
        fun of(argb: Int): Hsv {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val max = maxOf(r, g, b)
            val d = max - minOf(r, g, b)
            val h = when {
                d == 0f -> 0f
                max == r -> 60f * (((g - b) / d) % 6f)
                max == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            return Hsv(if (h < 0f) h + 360f else h, if (max == 0f) 0f else d / max, max)
        }
    }
}

private val SWATCH = 34.dp
private val RAINBOW_RING = 4.dp
private val THUMB_RADIUS = 11.dp
private val TRACK_HEIGHT = 12.dp

private val RAINBOW = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)
