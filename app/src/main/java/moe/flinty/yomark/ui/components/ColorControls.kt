package moe.flinty.yomark.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 选颜色：一排色板，后面是「自定义」和吸管，排不下就折到第二行——不横着滑，吸管不会藏在屏幕外面。
 *
 * 自定义点开是色相、饱和度、亮度三条滑条；吸管点一下，再点图上任意一处，就取那里的颜色。
 * 当前颜色不在色板上时，「自定义」那一格是选中的，里面画着这个颜色。
 *
 * 色块的色板最前面还有一颗「近似色」（[near]），隔一道竖线：它不是一种颜色，是每一块各取四周的颜色。
 * 它选中时色板上哪一格都不算选中；点了色板、自定义或吸管，就换回固定的颜色。
 *
 * @param onColor 选了颜色。inProgress = 自定义的滑条还没松手，松手时另有 [onColorFinished]。
 * @param near 只有色块有；null 时没有这一颗
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
    near: NearChoice? = null,
    onNear: () -> Unit = {},
) {
    val nearOn = near?.selected == true
    val custom = !nearOn && palette.none { it.argb == color }
    var customOpen by remember { mutableStateOf(false) }
    FlowRow(
        Modifier.fillMaxWidth().selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (near != null) {
            NearColorSwatch(colors = near.colors, selected = nearOn, onClick = onNear)
            SwatchDivider()
        }
        palette.forEach { c ->
            ColorSwatch(
                color = Color(c.argb),
                name = c.name,
                selected = !nearOn && c.argb == color,
                onClick = { onColor(c.argb, false) },
            )
        }
        CustomColorSwatch(color = Color(color), selected = custom, onClick = { customOpen = !customOpen })
        EyedropperButton(active = picking, onClick = onEyedropper)
    }
    AnimatedVisibility(visible = nearOn) {
        Text(
            "每一块取它四周的颜色填上，纯色背景上几乎看不出打过码",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
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

/** 色板上的「近似色」：选中与否，以及这张图上取到的颜色（最多三种，多的排前面，见 nearColorsOf）。 */
@Immutable
internal data class NearChoice(val selected: Boolean, val colors: List<Int>)

/**
 * 「近似色」那一颗：一颗带字的胶囊，和色板上的圆一样高、一样的选中圈。
 *
 * 写出字来，是因为它不是一种固定的颜色：截图多半是白底，取到的近似色多半就是白色，
 * 只画一个圆的话和色板上的白色分不开。左边的小圆画的是这张图上真取到的颜色，跟着图变（见 [cycledNearColor]）。
 */
@Composable
private fun NearColorSwatch(colors: List<Int>, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dot = cycledNearColor(colors)
    Row(
        Modifier
            .height(SWATCH)
            .clip(CircleShape)
            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(start = 6.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(NEAR_DOT)
                .drawBehind { drawCircle(dot()) }
                .border(1.dp, scheme.outlineVariant, CircleShape),
        )
        Spacer(Modifier.width(6.dp))
        Text("近似色", style = MaterialTheme.typography.labelLarge, color = scheme.onSurface)
    }
}

/**
 * 近似色那颗小圆此刻画的颜色。图上取到几种就轮着换：一种停一会儿，再渐变到下一种，
 * 一圈一圈地转——每一块各有各的颜色，这颗圆就一块一块地给你看。只有一种就停在它上面。
 * 取到的颜色变了（选中了别的框、框拖到了别的底色上）也是渐变过去，不一下跳过去。
 *
 * 返回的是读颜色的函数：渐变的每一帧只在绘制里读，只重绘那颗圆，不重组整排色板。
 */
@Composable
private fun cycledNearColor(colors: List<Int>): () -> Color {
    var index by remember(colors) { mutableIntStateOf(0) }
    LaunchedEffect(colors) {
        if (colors.size < 2) return@LaunchedEffect
        while (true) {
            delay(NEAR_HOLD_MS + NEAR_FADE_MS)
            index = (index + 1) % colors.size
        }
    }
    val target = colors.getOrNull(index)?.let(::Color) ?: NEAR_PLACEHOLDER
    val color = animateColorAsState(target, tween(NEAR_FADE_MS), label = "nearColor")
    return { color.value }
}

/** 近似色和固定的颜色之间的一道竖线：前面那一颗不是一种颜色。 */
@Composable
private fun SwatchDivider() {
    Box(Modifier.height(SWATCH), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 1.dp, height = 20.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
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
private val NEAR_DOT = 22.dp
private val RAINBOW_RING = 4.dp

/** 近似色那颗小圆轮着换颜色时，每种颜色停多久、渐变到下一种用多久。 */
private const val NEAR_HOLD_MS = 1400L
private const val NEAR_FADE_MS = 600

/** 还没有图、什么也取不到时，那颗小圆和色块的小样子画的颜色。 */
internal val NEAR_PLACEHOLDER = Color.White
private val THUMB_RADIUS = 11.dp
private val TRACK_HEIGHT = 12.dp

private val RAINBOW = listOf(
    Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red,
)
