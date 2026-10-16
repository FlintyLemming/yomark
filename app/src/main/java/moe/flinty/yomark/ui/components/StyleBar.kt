package moe.flinty.yomark.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.CropSquare
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.core.model.MaskItem
import moe.flinty.yomark.core.model.MaskLook
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskPlan
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.core.model.StrokeShape
import moe.flinty.yomark.render.MaskStyleInfo
import moe.flinty.yomark.render.NearColor
import moe.flinty.yomark.render.TextStamp
import moe.flinty.yomark.ui.ColorTarget
import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/** 样式栏要显示的东西。 */
@Immutable
data class StyleBarModel(
    /** 样式栏上的那一份：选中了手动框时是那个框的样子，否则是画笔（见 EditorUiState.barLook）。 */
    val look: MaskLook,
    val panelOpen: Boolean = false,
    /** 吸管正在等着取色的那一项。 */
    val colorPick: ColorTarget? = null,
    /** 这张图上取到的近似色，最多三种：面板上「近似色」那一颗、开着近似色时色块的小样子都画它们（见 [nearColorsOf]）。 */
    val nearColors: List<Int> = emptyList(),
    /** 文本面板输入框里的字：选中的文本写的字，还没写的是空的。 */
    val text: String = "",
    /** 输入框写的是哪一块文本（选中的，或还没落下的那一处）：换了一块，输入框换成那一块的字。 */
    val textKey: String? = null,
    /** 输入框该拿焦点、弹出输入法了（见 EditorUiState.textFocus）。 */
    val textFocus: Boolean = false,
)

/** 样式栏上的动作。都有空实现，测试只接自己关心的。 */
interface StyleBarActions {
    fun onStyleClick(style: MaskStyle) {}

    /** @param inProgress 滑条还没松手，松手时另有 [onLookChangeFinished]。 */
    fun onLookChange(look: MaskLook, inProgress: Boolean) {}
    fun onLookChangeFinished() {}
    fun onReset() {}
    fun onCollapse() {}

    /** 开吸管（[target] 非 null）或收起吸管（null）。 */
    fun onPickColor(target: ColorTarget?) {}

    /** 文本面板的输入框改了。 */
    fun onTextChange(text: String) {}

    /** 输入框照 [StyleBarModel.textFocus] 拿到了焦点。 */
    fun onTextFocusTaken() {}
}

// ---------- 小样子 ----------

/**
 * 每种样式的小样子：手机底栏展开后的工具（EditorToolbar）、宽屏的样式方块（StyleGrid）都用它，方块大时按 [scale] 放大。
 * 跟着参数走：色块是当前的颜色（近似色是图上取到的颜色）、马克笔是当前的颜色——不点开面板也看得出下一笔是什么样。
 *
 * @param nearColors 色块开着近似色时，小样子画的是图上取到的这几种颜色（见 [NearSolidGlyph]）
 * @param ink 马赛克、模糊没有自己的颜色，跟着旁边的字走：底栏上选中的那一项是主题色；不给就是平常的灰
 */
@Composable
internal fun StyleGlyph(
    style: MaskStyle,
    options: MaskOptions,
    nearColors: List<Int> = emptyList(),
    scale: Float = 1f,
    ink: Color = Color.Unspecified,
) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val tone = ink.takeOrElse { MaterialTheme.colorScheme.onSurfaceVariant }
    when (style) {
        MaskStyle.SOLID -> if (options.solidNear) NearSolidGlyph(nearColors, scale) else Box(
            Modifier
                .size(width = 22.dp * scale, height = 16.dp * scale)
                .background(Color(options.solidColor), RoundedCornerShape(4.dp * scale))
                .border(1.dp, outline, RoundedCornerShape(4.dp * scale)),
        )
        MaskStyle.PIXELATE -> MosaicGlyph(tone, 18.dp * scale)
        MaskStyle.BLUR -> Icon(Icons.Filled.BlurOn, contentDescription = null, modifier = Modifier.size(20.dp * scale), tint = tone)
        MaskStyle.MARKER -> if (options.markerShape == StrokeShape.FREEHAND) {
            Icon(Icons.Outlined.Gesture, contentDescription = null, modifier = Modifier.size(20.dp * scale), tint = Color(options.markerColor))
        } else Box(
            Modifier
                .size(width = 22.dp * scale, height = 12.dp * scale)
                .background(Color(options.markerColor), RoundedCornerShape(3.dp * scale)),
        )
        // 画笔：手画是一道弯线，拉框是一个空心框，箭头是一支斜着的箭头，都是画笔的颜色
        MaskStyle.PEN -> when (options.penShape) {
            StrokeShape.FREEHAND -> Icon(Icons.Outlined.Gesture, contentDescription = null, modifier = Modifier.size(20.dp * scale), tint = Color(options.penColor))
            StrokeShape.ARROW -> Icon(Icons.Outlined.NorthEast, contentDescription = null, modifier = Modifier.size(20.dp * scale), tint = Color(options.penColor))
            StrokeShape.BOX -> Box(
                Modifier
                    .size(width = 20.dp * scale, height = 14.dp * scale)
                    .border(2.dp * scale, Color(options.penColor), RoundedCornerShape(2.dp * scale)),
            )
        }
        // 文本：一个 T，是文本的颜色；垫着底色时是那个颜色的一块底，上面的 T 和图上的字一样取黑或白
        MaskStyle.TEXT -> if (options.textBackground) Box(
            Modifier
                .size(width = 22.dp * scale, height = 16.dp * scale)
                .background(Color(options.textColor), RoundedCornerShape(4.dp * scale)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Title, contentDescription = null, modifier = Modifier.size(14.dp * scale), tint = Color(TextStamp.onColor(options.textColor)))
        } else {
            Icon(Icons.Filled.Title, contentDescription = null, modifier = Modifier.size(20.dp * scale), tint = Color(options.textColor))
        }
    }
}

/**
 * 开着近似色的色块：和固定颜色的色块一样大的圆角矩形，填的是图上取到最多的那种近似色，描一圈虚线——
 * 白底页面上取到的多半是白色，看上去就是一个快要融进底色的虚线框，正是近似色的意思。
 * 不像面板上那颗圆那样轮着换颜色：样式栏一直在眼前，一直在动会分心。
 */
@Composable
private fun NearSolidGlyph(nearColors: List<Int>, scale: Float) {
    val dash = MaterialTheme.colorScheme.outline
    val fill = nearColors.firstOrNull()?.let(::Color) ?: NEAR_PLACEHOLDER
    Canvas(Modifier.size(width = 22.dp * scale, height = 16.dp * scale)) {
        val corner = CornerRadius(4.dp.toPx() * scale)
        drawRoundRect(fill, cornerRadius = corner)
        val stroke = 1.dp.toPx()
        val on = 2.dp.toPx() * scale
        drawRoundRect(
            dash,
            topLeft = Offset(stroke / 2f, stroke / 2f),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = corner,
            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(on, on * 0.75f))),
        )
    }
}

/** 马赛克的小样子：3×3 的格子，深浅错开。「打码」那一组也用它。 */
@Composable
internal fun MosaicGlyph(ink: Color, side: Dp) {
    Canvas(Modifier.size(side)) {
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

/**
 * 手机上的调节面板里的东西：顶上是当前样式的名字和收起，下面是能调的。
 * 面板本身是底栏的胶囊往上长出来的那一块（见 EditorBottomBar），盖在画布上，不把图往上顶；
 * 外面那一列（[PanelColumn]）由底栏摆，用途水印的面板也在那一列里，换着开时面板一点点变高变矮。
 * 样式和参数管的是下一笔；选中了手动框时那个框照样当场跟着变。
 * 吸管在等取色时面板先缩回去——要点的是底下的图，取完色（或取消了）再长出来，看得到取到的颜色。
 *
 * @param compactWhileTyping 文本打字时（输入框有焦点、输入法弹着）只留输入框那一格，收起输入法再摊开，和用途水印一样：
 *   面板盖在图上，输入法又占掉下半截，整个摊开的话看不到字落在图上是什么样
 * @param imeVisible 输入法弹着。测试里没有输入法，由测试给
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StylePanelContent(
    model: StyleBarModel,
    actions: StyleBarActions,
    compactWhileTyping: Boolean = false,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    PanelHeader(
        title = stringResource(MaskStyleInfo.label(model.look.style)),
        onCollapse = actions::onCollapse,
        collapseLabel = stringResource(R.string.collapse_style_panel),
    )
    StylePanelBody(model, actions, compactWhileTyping = compactWhileTyping && imeVisible)
}

/**
 * 弹出的面板在一个铺满屏幕的对话框窗口里，Material 3 按系统的深色模式给这个窗口定系统栏图标的颜色。
 * 本应用只有浅色界面（见 enableLightEdgeToEdge），深色模式下变白的图标落在浅色的面板和暗幕上看不清：扳回深色。
 */
@Composable
internal fun DarkSystemBarIcons() {
    val view = LocalView.current
    SideEffect {
        val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
    }
}

/**
 * 宽屏上的调节面板：占框列表的位置（见 WideEditorPanel），画布留在旁边不被盖住。
 * 每动一下，选中的框（刚画完的框就是选中的）当场跟着变；没有选中的框时，改的是下一笔。
 *
 * @param standalone 顶替了样式方块（手机横着拿，右边那一栏放不下两样）：方块看不见了，和弹出的面板一样
 *   顶上写出当前样式的名字，方块下面的说明也再说一遍
 */
@Composable
internal fun StylePanel(
    model: StyleBarModel,
    actions: StyleBarActions,
    modifier: Modifier = Modifier,
    standalone: Boolean = false,
) {
    WidePanel(modifier) {
        PanelHeader(
            title = if (standalone) stringResource(MaskStyleInfo.label(model.look.style)) else null,
            onCollapse = actions::onCollapse,
            collapseLabel = stringResource(R.string.collapse_style_panel),
        )
        StylePanelBody(model, actions)
    }
}

/**
 * 面板的主体：当前样式能调的，底下是「恢复默认」。文本的最上面是它写的字。
 *
 * @param compactWhileTyping 输入法弹着：文本的输入框有焦点时只留它（见 [StylePanelContent]）
 */
@Composable
private fun StylePanelBody(model: StyleBarModel, actions: StyleBarActions, compactWhileTyping: Boolean = false) {
    val look = model.look
    var typing by remember { mutableStateOf(false) }
    if (look.style == MaskStyle.TEXT) {
        Column(Modifier.padding(end = 12.dp)) { TextInput(model, actions, onFocus = { typing = it }) }
        // 收起来时面板一点点变矮（PanelColumn 跟着里面的东西变高变矮），输入法收起后再长回来
        if (compactWhileTyping && typing) return
    }
    Column(Modifier.padding(end = 12.dp)) {
        StyleControls(look, model.colorPick, model.nearColors, actions)
    }
    TextButton(onClick = actions::onReset, enabled = !look.options.isDefaultFor(look.style)) {
        Text(stringResource(R.string.reset_defaults))
    }
}

@Composable
private fun StyleControls(look: MaskLook, colorPick: ColorTarget?, nearColors: List<Int>, actions: StyleBarActions) {
    val options = look.options
    fun emit(next: MaskOptions, inProgress: Boolean = false) = actions.onLookChange(look.copy(options = next), inProgress)

    @Composable
    fun Colors(target: ColorTarget, palette: List<NamedColor>, label: String, widths: SliderWidths, near: NearChoice? = null) {
        PanelLabel(label)
        ColorPicker(
            color = target.colorOf(options),
            palette = palette,
            picking = colorPick == target,
            onColor = { rgb, inProgress -> emit(target.withColor(options, rgb), inProgress) },
            onColorFinished = actions::onLookChangeFinished,
            onEyedropper = { actions.onPickColor(if (colorPick == target) null else target) },
            sliderWidths = widths,
            near = near,
            onNear = { emit(options.copy(solidNear = true)) },
        )
    }

    // 每一样的滑条（连自定义颜色的三条）左右两格一样宽，按这一样最长的名字、最宽的值留（见 rememberSliderWidths）
    when (look.style) {
        // 近似色排在色板最前面：原先的「抹除」，并进色块之后就是色块的一种取色方式
        MaskStyle.SOLID -> Colors(
            ColorTarget.SOLID, SOLID_PALETTE, stringResource(R.string.color),
            rememberSliderWidths(hsvLabels(), values = emptyList()),
            NearChoice(options.solidNear, nearColors),
        )

        MaskStyle.PIXELATE -> {
            // 滑条往右是更粗：divisor 越小块越大
            val finest = MaskOptions.PIXEL_DIVISOR_RANGE.last
            val coarsest = MaskOptions.PIXEL_DIVISOR_RANGE.first
            val label = stringResource(R.string.grain)
            val levels = PIXEL_LEVELS.map { stringResource(it) }
            SliderRow(
                label = label,
                value = (finest - options.pixelBlockDivisor).toFloat(),
                range = 0f..(finest - coarsest).toFloat(),
                steps = finest - coarsest - 1,
                valueText = levels.getOrNull(finest - options.pixelBlockDivisor).orEmpty(),
                widths = rememberSliderWidths(listOf(label), levels),
                onChange = { emit(options.copy(pixelBlockDivisor = finest - it.roundToInt()), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        MaskStyle.BLUR -> {
            val range = MaskOptions.BLUR_RATIO_RANGE
            val label = stringResource(R.string.strength)
            val levels = BLUR_LEVELS.map { stringResource(it) }
            SliderRow(
                label = label,
                value = options.blurRadiusRatio,
                range = range,
                steps = BLUR_LEVELS.size - 2,
                valueText = levels[blurLevel(options.blurRadiusRatio)],
                widths = rememberSliderWidths(listOf(label), levels),
                onChange = { emit(options.copy(blurRadiusRatio = it), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        MaskStyle.MARKER -> {
            val label = stringResource(R.string.marker_opacity)
            // 粗细拉框时没有，也算上：换成手绘时浓淡那一条不挪
            val widths = rememberSliderWidths(
                listOf(label, stringResource(R.string.stroke_width)) + hsvLabels(),
                MaskOptions.MARKER_ALPHA_RANGE.endTexts(::percentText) + MaskOptions.MARKER_WIDTH_RANGE.endTexts(::pixelText),
            )
            ShapeChoice(options.markerShape, MARKER_SHAPES) { emit(options.copy(markerShape = it)) }
            Colors(ColorTarget.MARKER, MARKER_PALETTE, stringResource(R.string.color), widths)
            SliderRow(
                label = label,
                value = options.markerAlpha,
                range = MaskOptions.MARKER_ALPHA_RANGE,
                valueText = percentText(options.markerAlpha),
                widths = widths,
                onChange = { emit(options.copy(markerColor = MaskOptions.withAlpha(options.markerColor, it)), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 拉框是涂一整块，没有笔宽
            if (options.markerShape == StrokeShape.FREEHAND) {
                WidthSlider(options.markerWidth, MaskOptions.MARKER_WIDTH_RANGE, widths, actions) { emit(options.copy(markerWidth = it), inProgress = true) }
            }
        }

        MaskStyle.PEN -> {
            val widths = rememberSliderWidths(
                listOf(stringResource(R.string.stroke_width)) + hsvLabels(),
                MaskOptions.PEN_WIDTH_RANGE.endTexts(::pixelText),
            )
            ShapeChoice(options.penShape, PEN_SHAPES) { emit(options.copy(penShape = it)) }
            Colors(ColorTarget.PEN, PEN_PALETTE, stringResource(R.string.color), widths)
            WidthSlider(options.penWidth, MaskOptions.PEN_WIDTH_RANGE, widths, actions) { emit(options.copy(penWidth = it), inProgress = true) }
        }

        // 写的字在这一段上面（见 TextInput）。字号改了，选中的文本当场变大变小
        MaskStyle.TEXT -> {
            val label = stringResource(R.string.text_size)
            val widths = rememberSliderWidths(listOf(label) + hsvLabels(), MaskOptions.TEXT_SIZE_RANGE.endTexts(::pixelText))
            Colors(ColorTarget.TEXT, PEN_PALETTE, stringResource(R.string.color), widths)
            SliderRow(
                label = label,
                value = options.textSize,
                range = MaskOptions.TEXT_SIZE_RANGE,
                valueText = pixelText(options.textSize),
                widths = widths,
                onChange = { emit(options.copy(textSize = it), inProgress = true) },
                onValueChangeFinished = actions::onLookChangeFinished,
                modifier = Modifier.padding(top = 4.dp),
            )
            PanelSwitchRow(stringResource(R.string.text_background), options.textBackground, { emit(options.copy(textBackground = it)) })
        }
    }
}

/**
 * 文本写的字，一行。改一个字，图上跟着变（见 EditorViewModel.setText）。
 * 输入框自己持有正在编辑的字，不等 ViewModel 转一圈再回来；换了一块文本（[StyleBarModel.textKey]）就换成那一块的字，
 * 图上的字不是它刚发出去的那样（撤销了、重做了）也跟着图上的走，打着字也一样。
 * 要它拿焦点时（点了图上一处要写字、要改选中的文本）拿焦点、弹出输入法：已经有焦点、输入法被收起了的，也再弹出来。
 * 光标在最后：改字多半是接着打或者删掉重打，光标在最前面的话删除键删不到字。
 * 面板顶上写着「文本」，这里不另起小标题，名字放在空着时的提示里，读屏也念得到。
 */
@Composable
private fun TextInput(model: StyleBarModel, actions: StyleBarActions, onFocus: (Boolean) -> Unit) {
    var draft by remember(model.textKey) { mutableStateOf(atEnd(model.text)) }
    var sent by remember(model.textKey) { mutableStateOf(model.text) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val requester = remember { FocusRequester() }
    LaunchedEffect(model.text) {
        if (model.text != sent) {
            draft = atEnd(model.text)
            sent = model.text
        }
    }
    LaunchedEffect(model.textFocus) {
        if (!model.textFocus) return@LaunchedEffect
        requester.requestFocus()
        keyboard?.show()
        actions.onTextFocusTaken()
    }
    OutlinedTextField(
        value = draft,
        onValueChange = {
            // 只写一行：粘贴进来的换行换成空格，和图上的一样。一个换一个，光标、正在拼的字都还在原处
            val line = it.text.replace('\n', ' ')
            draft = it.copy(text = line)
            // 只挪了光标、选了字的，图上没变
            if (line != sent) {
                sent = line
                actions.onTextChange(line)
            }
        },
        singleLine = true,
        placeholder = { Text(stringResource(R.string.text_content)) },
        // 和用途水印的输入框、「画法」那一排按钮一样是圆头的
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        modifier = Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { onFocus(it.isFocused) },
    )
}

/** 光标落在最后的 [text]。 */
private fun atEnd(text: String) = TextFieldValue(text, TextRange(text.length))

/**
 * 马克笔、画笔下一笔怎么画：拉框，照着手指画，画笔还能拉箭头（见 StrokeShape）。管的是下一笔，画好的不变。
 * [shapes] 是这一种笔有的几种，按这个顺序排。
 *
 * 放不下时一步步让，三段一个样：先不画图标，名字写全；名字也写不全（窄屏上的英文）就只画图标，名字给读屏念。
 * 不截成「Freeha」：截了的名字比没有名字还难认。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShapeChoice(shape: StrokeShape, shapes: List<StrokeShape>, onShape: (StrokeShape) -> Unit) {
    PanelLabel(stringResource(R.string.stroke_shape))
    val labels = shapes.map {
        stringResource(
            when (it) {
                StrokeShape.BOX -> R.string.stroke_shape_box
                StrokeShape.FREEHAND -> R.string.stroke_shape_freehand
                StrokeShape.ARROW -> R.string.stroke_shape_arrow
            },
        )
    }
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelLarge
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // 每一段里能放名字的宽：按钮左右各 12 的内边距，带图标时再去掉图标 18 加 6 的空
        val segment = (maxWidth - SEGMENT_OVERLAP * (shapes.size - 1)) / shapes.size
        val density = LocalDensity.current
        val widest = labels.maxOf { measurer.measure(it, style, maxLines = 1).size.width }
        fun fits(room: Dp) = widest <= with(density) { room.roundToPx() }
        val icons = !fits(segment - SEGMENT_PADDING) || fits(segment - SEGMENT_PADDING - SHAPE_ICON - SHAPE_ICON_GAP)
        val names = fits(segment - SEGMENT_PADDING)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            shapes.forEachIndexed { i, value ->
                SegmentedButton(
                    selected = shape == value,
                    onClick = { if (shape != value) onShape(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, shapes.size),
                    icon = {},
                ) {
                    // 按钮里的东西是叠着摆的，图标和字自己排成一行
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (icons) {
                            Icon(
                                when (value) {
                                    StrokeShape.BOX -> Icons.Outlined.CropSquare
                                    StrokeShape.FREEHAND -> Icons.Outlined.Gesture
                                    StrokeShape.ARROW -> Icons.Outlined.NorthEast
                                },
                                contentDescription = if (names) null else labels[i],
                                modifier = Modifier.size(SHAPE_ICON),
                            )
                        }
                        if (icons && names) Spacer(Modifier.width(SHAPE_ICON_GAP))
                        if (names) Text(labels[i], maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 分段按钮里一段的左右内边距（Material 3 的 TextButton，各 12）、相邻两段压着的那一条边。 */
private val SEGMENT_PADDING = 24.dp
private val SEGMENT_OVERLAP = 1.dp
private val SHAPE_ICON = 18.dp
private val SHAPE_ICON_GAP = 6.dp

/** 马克笔没有箭头：半透明的粗箭头看不清头。 */
private val MARKER_SHAPES = listOf(StrokeShape.BOX, StrokeShape.FREEHAND)
private val PEN_SHAPES = listOf(StrokeShape.BOX, StrokeShape.FREEHAND, StrokeShape.ARROW)

/** 笔宽：滑条往右更粗，旁边写着按 1080 宽的截图算是几像素，心里有个数。 */
@Composable
private fun WidthSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    widths: SliderWidths,
    actions: StyleBarActions,
    onChange: (Float) -> Unit,
) {
    SliderRow(
        label = stringResource(R.string.stroke_width),
        value = value,
        range = range,
        valueText = pixelText(value),
        widths = widths,
        onChange = onChange,
        onValueChangeFinished = actions::onLookChangeFinished,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 笔宽、字号旁边写的像素数：[fraction] 是占截图短边的比例。 */
private fun pixelText(fraction: Float) = "${(fraction * REFERENCE_SHORT_SIDE).roundToInt()} px"

/** 笔宽旁边写的像素数按这么宽的截图算：常见手机截图的短边。 */
private const val REFERENCE_SHORT_SIDE = 1080

/**
 * 「近似色」那一颗和色块的小样子上画的颜色：这张图上真取到的近似色，最多三种，块数多的排前面。
 *
 * 选中了框时只取它的——面板改的就是它。否则取图上每一块码的（画笔下一笔多半落在它们身上），
 * 相近的并成一种。一块也没有时取整张图上占地最多的颜色，多半就是页面的底色，下一块多半也画在它上面。
 */
internal fun nearColorsOf(image: SourceImage?, plan: MaskPlan, selected: MaskItem?): List<Int> {
    image ?: return emptyList()
    val quads = selected?.let { listOf(it.quad) } ?: plan.items.map { it.quad }
    if (quads.isEmpty()) return listOf(NearColor.dominant(image.bitmap))
    val found = quads.map { NearColor.around(image.bitmap, it, image.scale) }
    return NearColor.heaps(found).take(MAX_NEAR_COLORS).map { it.color }
}

private const val MAX_NEAR_COLORS = 3

// ---------- 色板 ----------

/** 色板上的一个颜色：不透明的 RGB 和读屏念的名字。 */
@Immutable
internal data class NamedColor(val argb: Int, @StringRes val name: Int)

/** 色块：出厂的天蓝排第一，黑白灰之后是常用的几种。 */
internal val SOLID_PALETTE = listOf(
    NamedColor(MaskOptions.SKY_BLUE, R.string.color_sky_blue),
    NamedColor(0xFF000000.toInt(), R.string.color_black),
    NamedColor(0xFF5F6368.toInt(), R.string.color_gray),
    NamedColor(0xFFFFFFFF.toInt(), R.string.color_white),
    NamedColor(0xFFE53935.toInt(), R.string.color_red),
    NamedColor(0xFFFB8C00.toInt(), R.string.color_orange),
    NamedColor(0xFFFDD835.toInt(), R.string.color_yellow),
    NamedColor(0xFF43A047.toInt(), R.string.color_green),
    NamedColor(0xFF1E88E5.toInt(), R.string.color_blue),
    NamedColor(0xFF8E24AA.toInt(), R.string.color_purple),
)

/** 马克笔：荧光笔的几种颜色。浓淡另外调。 */
internal val MARKER_PALETTE = listOf(
    NamedColor(MaskOptions.DEFAULT_MARKER_COLOR or 0xFF000000.toInt(), R.string.color_yellow),
    NamedColor(0xFFFF9800.toInt(), R.string.color_orange),
    NamedColor(0xFFFF4081.toInt(), R.string.color_pink),
    NamedColor(0xFFF44336.toInt(), R.string.color_red),
    NamedColor(0xFFE040FB.toInt(), R.string.color_purple),
    NamedColor(0xFF448AFF.toInt(), R.string.color_blue),
    NamedColor(0xFF18FFFF.toInt(), R.string.color_cyan),
    NamedColor(0xFF76FF03.toInt(), R.string.color_green),
)

/** 画笔：先是出厂的红，再是黑白和几种醒目的颜色。 */
internal val PEN_PALETTE = listOf(
    NamedColor(MaskOptions.DEFAULT_PEN_COLOR, R.string.color_red),
    NamedColor(0xFF000000.toInt(), R.string.color_black),
    NamedColor(0xFFFFFFFF.toInt(), R.string.color_white),
    NamedColor(0xFFFB8C00.toInt(), R.string.color_orange),
    NamedColor(0xFFFDD835.toInt(), R.string.color_yellow),
    NamedColor(0xFF43A047.toInt(), R.string.color_green),
    NamedColor(0xFF1E88E5.toInt(), R.string.color_blue),
    NamedColor(0xFF8E24AA.toInt(), R.string.color_purple),
)

/** 马赛克颗粒的六档，按 8 - divisor 排：0 最细，2 是出厂，5 最粗。 */
private val PIXEL_LEVELS = listOf(R.string.level_fine, R.string.level_finer, R.string.level_medium, R.string.level_coarser, R.string.level_coarse, R.string.level_very_coarse)

/** 模糊强度的五档，滑条也只停在这五处。 */
private val BLUR_LEVELS = listOf(R.string.blur_light, R.string.blur_lighter, R.string.level_medium, R.string.blur_heavier, R.string.blur_heavy)

private fun blurLevel(ratio: Float): Int {
    val range = MaskOptions.BLUR_RATIO_RANGE
    val t = (ratio - range.start) / (range.endInclusive - range.start)
    return (t * (BLUR_LEVELS.size - 1)).roundToInt().coerceIn(0, BLUR_LEVELS.lastIndex)
}
