package moe.flinty.yomark.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskState
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.EraseRenderer
import moe.flinty.yomark.render.MaskStyleInfo
import moe.flinty.yomark.ui.ColorTarget
import kotlin.math.roundToInt

/** 样式栏要显示的东西。 */
@Immutable
data class StyleBarModel(
    /** 样式栏上的那一份：选中了手动框时是那个框的样子，否则是画笔（见 EditorUiState.barLook）。 */
    val look: MaskLook,
    val panelOpen: Boolean = false,
    /** 选中了手动框：面板上的改动也落在它身上。 */
    val editingSelection: Boolean = false,
    /** 吸管正在等着取色的那一项。 */
    val colorPick: ColorTarget? = null,
    /** 「应用到全部」会改动的已打码块数。0 时按钮不可点。 */
    val applicable: Int = 0,
    /** 抹除在这张图上降级成色块时的说明。 */
    val degradeNote: String? = null,
)

/** 样式栏上的动作。都有空实现，测试只接自己关心的。 */
interface StyleBarActions {
    fun onStyleClick(style: MaskStyle) {}

    /** @param inProgress 滑条还没松手，松手时另有 [onLookChangeFinished]。 */
    fun onLookChange(look: MaskLook, inProgress: Boolean) {}
    fun onLookChangeFinished() {}
    fun onReset() {}
    fun onApplyToAll() {}
    fun onCollapse() {}

    /** 开吸管（[target] 非 null）或收起吸管（null）。 */
    fun onPickColor(target: ColorTarget?) {}
}

/**
 * 样式栏（spec §7.5 修订）：一排六种样式，点一种就在下面弹出它的调节面板。
 *
 * 样式栏是「画笔」：换了样式、调了颜色，从下一次打码起生效——新画的框、点了打码的虚线框都用它，
 * 已经打好的码不变；选中了手动框时，改动也落在那个框上。面板上的「应用到全部」把这张图上打好的码全换成这样。
 *
 * 安全性说明与降级说明贴在样式下面，面板收起时也在——用户切到模糊的那一刻就该知道模糊不是安全手段，
 * 而不是导出之后才发现。
 */
@Composable
fun StyleBar(
    model: StyleBarModel,
    actions: StyleBarActions,
    modifier: Modifier = Modifier,
) {
    val look = model.look
    Column(modifier.fillMaxWidth()) {
        StylePalette(look, panelOpen = model.panelOpen, onClick = actions::onStyleClick)
        val note = MaskStyleInfo.note(look.style) ?: model.degradeNote
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        AnimatedVisibility(
            visible = model.panelOpen,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            StylePanel(model, actions, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), phonePanelMaxHeight())
        }
    }
}

// ---------- 样式那一排 ----------

/**
 * 六种样式排成一排，每一项上面是一个小样子、下面是名字，平分整行宽度，不用横着滑。
 * 小样子跟着参数走：色块是当前的颜色，表情是当前的表情——不点开面板也看得出下一笔是什么样。
 * 选中的那一项名字后面有个小箭头：再点它一下，面板就在下面展开（或收起）。
 */
@Composable
private fun StylePalette(look: MaskLook, panelOpen: Boolean, onClick: (MaskStyle) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .selectableGroup(),
    ) {
        MaskStyleInfo.ORDER.forEach { style ->
            StyleTool(
                style = style,
                options = look.options,
                selected = style == look.style,
                panelOpen = panelOpen,
                onClick = { onClick(style) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StyleTool(
    style: MaskStyle,
    options: MaskOptions,
    selected: Boolean,
    panelOpen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选中的那一项垫一颗胶囊，和 Material 3 导航栏的选中指示一个样子
        Box(
            Modifier
                .size(width = 52.dp, height = 32.dp)
                .background(if (selected) colors.secondaryContainer else Color.Transparent, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            StyleGlyph(style, options)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MaskStyleInfo.label(style),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 1,
            )
            if (selected) {
                Icon(
                    if (panelOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** 样式栏上每一项的小样子。宽屏的样式圆钮（StyleGrid）也用它。 */
@Composable
internal fun StyleGlyph(style: MaskStyle, options: MaskOptions) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    when (style) {
        MaskStyle.SOLID -> Box(
            Modifier
                .size(width = 22.dp, height = 16.dp)
                .background(Color(options.solidColor), RoundedCornerShape(4.dp))
                .border(1.dp, outline, RoundedCornerShape(4.dp)),
        )
        MaskStyle.EMOJI -> Text(options.emoji, fontSize = 18.sp)
        MaskStyle.ERASE -> Icon(Icons.Outlined.AutoFixHigh, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        MaskStyle.PIXELATE -> MosaicGlyph(ink)
        MaskStyle.BLUR -> Icon(Icons.Filled.BlurOn, contentDescription = null, modifier = Modifier.size(20.dp), tint = ink)
        MaskStyle.MARKER -> Box(
            Modifier
                .size(width = 22.dp, height = 12.dp)
                .background(Color(options.markerColor), RoundedCornerShape(3.dp)),
        )
    }
}

/** 马赛克的小样子：3×3 的格子，深浅错开。 */
@Composable
private fun MosaicGlyph(ink: Color) {
    Canvas(Modifier.size(18.dp)) {
        val cell = size.width / 3f
        for (i in 0 until 3) for (j in 0 until 3) {
            val alpha = MOSAIC_SHADES[(i + 2 * j) % MOSAIC_SHADES.size]
            drawRoundRect(
                color = ink.copy(alpha = alpha),
                topLeft = Offset(i * cell, j * cell),
                size = Size(cell - 1.dp.toPx(), cell - 1.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

private val MOSAIC_SHADES = listOf(0.9f, 0.35f, 0.6f)

// ---------- 调节面板 ----------

/** 手机上面板最多占四成高度（横屏时屏幕矮，三成），多出来的滚动，画布总有地方留着。 */
@Composable
private fun phonePanelMaxHeight(): Dp {
    val config = LocalConfiguration.current
    val landscape = config.screenWidthDp > config.screenHeightDp
    return (config.screenHeightDp * if (landscape) 0.3f else 0.4f).dp
}

/**
 * 当前样式的调节面板。手机上就在样式那一排下面展开；宽屏上占框列表的位置（见 WideEditorPanel）。
 * 画布留在旁边不被盖住，每动一下，选中的框（刚画完的框就是选中的）当场跟着变；没有选中的框时，改的是下一笔。
 *
 * @param maxHeight 再高就滚动。宽屏上不限，由放它的那一格定。
 */
@Composable
internal fun StylePanel(
    model: StyleBarModel,
    actions: StyleBarActions,
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
) {
    val look = model.look
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(
            Modifier
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .animateContentSize()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (model.editingSelection) "改的是选中的框，之后打码也用它" else "之后打码都用这个样式，打好的不变",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = actions::onCollapse) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "收起样式面板")
                }
            }
            Column(Modifier.padding(end = 12.dp)) {
                StyleControls(look, model.colorPick, actions)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::onReset, enabled = !look.options.isDefaultFor(look.style)) {
                    Text("恢复默认")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = actions::onApplyToAll, enabled = model.applicable > 0) {
                    Icon(Icons.Filled.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (model.applicable > 0) "应用到全部 ${model.applicable} 处" else "应用到全部")
                }
            }
        }
    }
}

@Composable
private fun StyleControls(look: MaskLook, colorPick: ColorTarget?, actions: StyleBarActions) {
    val options = look.options
    fun emit(next: MaskOptions, inProgress: Boolean = false) = actions.onLookChange(look.copy(options = next), inProgress)

    @Composable
    fun Colors(target: ColorTarget, palette: List<NamedColor>, label: String) {
        PanelLabel(label)
        ColorPicker(
            color = target.colorOf(options),
            palette = palette,
            picking = colorPick == target,
            onColor = { rgb, inProgress -> emit(target.withColor(options, rgb), inProgress) },
            onColorFinished = actions::onLookChangeFinished,
            onEyedropper = { actions.onPickColor(if (colorPick == target) null else target) },
        )
    }

    when (look.style) {
        MaskStyle.SOLID -> Colors(ColorTarget.SOLID, SOLID_PALETTE, "颜色")

        MaskStyle.EMOJI -> {
            PanelLabel("表情")
            EmojiPicker(options.emoji) { emit(options.copy(emoji = it)) }
            Colors(ColorTarget.EMOJI_BACKGROUND, EMOJI_BACKGROUND_PALETTE, "底色")
            LabeledRow("排列") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !options.emojiTiled,
                        onClick = { emit(options.copy(emojiTiled = false)) },
                        label = { Text("单个") },
                    )
                    FilterChip(
                        selected = options.emojiTiled,
                        onClick = { emit(options.copy(emojiTiled = true)) },
                        label = { Text("排满") },
                    )
                }
            }
        }

        MaskStyle.ERASE -> {
            Text(
                "用四周的颜色把这块填平，看不出打过码。背景太复杂的地方填不平，会改用色块。",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Colors(ColorTarget.SOLID, SOLID_PALETTE, "改用色块时的颜色")
        }

        MaskStyle.PIXELATE -> {
            // 滑条往右是更粗：divisor 越小块越大
            val finest = MaskOptions.PIXEL_DIVISOR_RANGE.last
            val coarsest = MaskOptions.PIXEL_DIVISOR_RANGE.first
            SliderRow(
                label = "颗粒",
                value = (finest - options.pixelBlockDivisor).toFloat(),
                range = 0f..(finest - coarsest).toFloat(),
                steps = finest - coarsest - 1,
                valueText = PIXEL_LEVELS.getOrElse(finest - options.pixelBlockDivisor) { "" },
                onChange = { emit(options.copy(pixelBlockDivisor = finest - it.roundToInt()), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 粗到一格比框还高时就画不出马赛克了（PixelateRenderer.willFallBack），先说一声，免得以为是出了错
            Text(
                "框太小、放不下一格时，那一块改用色块",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MaskStyle.BLUR -> {
            val range = MaskOptions.BLUR_RATIO_RANGE
            SliderRow(
                label = "强度",
                value = options.blurRadiusRatio,
                range = range,
                steps = BLUR_LEVELS.size - 2,
                valueText = BLUR_LEVELS[blurLevel(options.blurRadiusRatio)],
                onChange = { emit(options.copy(blurRadiusRatio = it), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        MaskStyle.MARKER -> {
            Colors(ColorTarget.MARKER, MARKER_PALETTE, "颜色")
            SliderRow(
                label = "浓淡",
                value = options.markerAlpha,
                range = MaskOptions.MARKER_ALPHA_RANGE,
                valueText = "${(options.markerAlpha * 100).roundToInt()}%",
                onChange = { emit(options.copy(markerColor = MaskOptions.withAlpha(options.markerColor, it)), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun PanelLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
    )
}

/** 一排表情，横着滑。选中的垫一块底色；面板打开时先滑到它附近，不让它藏在屏幕外面。 */
@Composable
private fun EmojiPicker(selected: String, onPick: (String) -> Unit) {
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (EMOJIS.indexOf(selected) - 2).coerceAtLeast(0))
    LazyRow(
        Modifier
            .fillMaxWidth()
            .selectableGroup(),
        state = list,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(EMOJIS) { emoji ->
            val isSelected = emoji == selected
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .semantics { contentDescription = emoji }
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onPick(emoji) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 24.sp)
            }
        }
    }
}

/**
 * 抹除在这张图上有几处降级成了色块（spec §8）。用户需要知道为什么，
 * 而不是导出后发现「怎么和预览不一样」。只数用抹除打的码。
 */
internal fun eraseDegradeNote(image: SourceImage?, plan: MaskPlan): String? {
    image ?: return null
    val eraser = EraseRenderer()
    val degrading = plan.items.count {
        it.state == MaskState.MASKED && it.look.style == MaskStyle.ERASE &&
            eraser.willDegrade(image.bitmap, it.quad, image.scale)
    }
    return if (degrading == 0) null else "$degrading 处背景太复杂，已改用色块"
}

// ---------- 色板与表情 ----------

/** 色板上的一个颜色：不透明的 RGB 和读屏念的名字。 */
@Immutable
internal data class NamedColor(val argb: Int, val name: String)

/** 色块：出厂的天蓝排第一，黑白灰之后是常用的几种。 */
internal val SOLID_PALETTE = listOf(
    NamedColor(MaskOptions.SKY_BLUE, "天蓝"),
    NamedColor(0xFF000000.toInt(), "黑色"),
    NamedColor(0xFF5F6368.toInt(), "灰色"),
    NamedColor(0xFFFFFFFF.toInt(), "白色"),
    NamedColor(0xFFE53935.toInt(), "红色"),
    NamedColor(0xFFFB8C00.toInt(), "橙色"),
    NamedColor(0xFFFDD835.toInt(), "黄色"),
    NamedColor(0xFF43A047.toInt(), "绿色"),
    NamedColor(0xFF1E88E5.toInt(), "蓝色"),
    NamedColor(0xFF8E24AA.toInt(), "紫色"),
)

/** 表情的底色：出厂的深灰、黑白，再是几种浅色——浅底上的表情更俏皮。 */
internal val EMOJI_BACKGROUND_PALETTE = listOf(
    NamedColor(MaskOptions.EMOJI_BACKGROUND, "深灰"),
    NamedColor(0xFF000000.toInt(), "黑色"),
    NamedColor(0xFFFFFFFF.toInt(), "白色"),
    NamedColor(MaskOptions.SKY_BLUE, "天蓝"),
    NamedColor(0xFFFFE082.toInt(), "浅黄"),
    NamedColor(0xFFF8BBD0.toInt(), "浅粉"),
    NamedColor(0xFFC5E1A5.toInt(), "浅绿"),
    NamedColor(0xFFB3E5FC.toInt(), "浅蓝"),
)

/** 马克笔：荧光笔的几种颜色。浓淡另外调。 */
internal val MARKER_PALETTE = listOf(
    NamedColor(MaskOptions.DEFAULT_MARKER_COLOR or 0xFF000000.toInt(), "黄色"),
    NamedColor(0xFFFF9800.toInt(), "橙色"),
    NamedColor(0xFFFF4081.toInt(), "粉色"),
    NamedColor(0xFFF44336.toInt(), "红色"),
    NamedColor(0xFFE040FB.toInt(), "紫色"),
    NamedColor(0xFF448AFF.toInt(), "蓝色"),
    NamedColor(0xFF18FFFF.toInt(), "青色"),
    NamedColor(0xFF76FF03.toInt(), "绿色"),
)

/** 都是 Unicode 9 以前就有的，Android 10 自带的字体全画得出来。 */
internal val EMOJIS = listOf(
    "🙂", "😀", "😊", "😎", "🤐", "🙈", "😶", "🐱", "🐶", "🐼",
    "🐰", "🦊", "🐷", "🐸", "⭐", "❤️", "🌸", "🔒", "🚫", "✋",
)

/** 马赛克颗粒的六档，按 8 - divisor 排：0 最细（出厂），5 最粗。 */
private val PIXEL_LEVELS = listOf("细", "较细", "适中", "较粗", "粗", "很粗")

/** 模糊强度的五档，滑条也只停在这五处。 */
private val BLUR_LEVELS = listOf("轻", "较轻", "适中", "较重", "重")

private fun blurLevel(ratio: Float): Int {
    val range = MaskOptions.BLUR_RATIO_RANGE
    val t = (ratio - range.start) / (range.endInclusive - range.start)
    return (t * (BLUR_LEVELS.size - 1)).roundToInt().coerceIn(0, BLUR_LEVELS.lastIndex)
}
