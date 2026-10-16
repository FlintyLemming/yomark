package moe.flinty.yomark.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.export.PurposeWatermarkStyle
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/** 用途水印的调节面板要显示的东西。 */
@Immutable
data class PurposePanelModel(
    /** 输入框里的文字。 */
    val text: String = "",
    /** 「应用」开关开着：文字画在图上。 */
    val on: Boolean = false,
    val style: PurposeWatermarkStyle = PurposeWatermarkStyle(),
    /** 面板开着。和样式的调节面板是同一块地方，一次只开一个（见 EditorViewModel.showPurposeSheet）。 */
    val open: Boolean = false,
    /** 吸管正等着给水印取色。 */
    val picking: Boolean = false,
)

/** 用途水印面板上的动作。都有空实现，测试只接自己关心的。 */
interface PurposePanelActions {
    /** 点了水印（手机底栏「编辑」里的那一项、宽屏顶栏上的按钮）：收着就打开，开着就收起。 */
    fun onToggle() {}
    fun onTextChange(text: String) {}
    fun onStyleChange(style: PurposeWatermarkStyle) {}
    fun onReset() {}

    /** 「应用」开关：加上（true）或去掉（false）图上的水印。 */
    fun onApply(on: Boolean) {}
    fun onCollapse() {}

    /** 开吸管（true）或收起吸管（false）。 */
    fun onPickColor(pick: Boolean) {}
}

/**
 * 用途水印的调节面板里的东西：顶上是名字和收起，下面是文字、颜色、角度、不透明度、密度，最底下是「恢复默认」和「应用」开关。
 *
 * 和马克笔、画笔的面板一个样子、一个地方：手机上是底栏的胶囊往上长出来的那一块（见 EditorBottomBar），盖在图上，
 * 外面那一列（[PanelColumn]）由底栏摆；宽屏上占框列表的位置（见 [PurposePanel]）。原先它顶替整个底栏，样子也是另一套。
 * 每动一下，图上的水印就跟着变，所见即导出。颜色和马克笔一样有自定义和吸管，吸管等着取色时手机上的面板先缩回去。
 *
 * @param compactWhileTyping 打字时（输入框有焦点、输入法弹着）只留文字那一格，收起输入法再摊开。
 *   手机上面板盖在图上，输入法又占掉下半截，面板整个摊开的话图一点也露不出来，看不到打的字落在图上是什么样
 * @param imeVisible 输入法弹着。测试里没有输入法，由测试给
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PurposePanelContent(
    model: PurposePanelModel,
    actions: PurposePanelActions,
    compactWhileTyping: Boolean = false,
    imeVisible: Boolean = WindowInsets.isImeVisible,
) {
    val style = model.style
    // 输入框自己持有正在编辑的文字，不等 ViewModel 那边转一圈再回来
    var draft by remember { mutableStateOf(model.text) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    PanelHeader(
        title = stringResource(R.string.purpose_watermark),
        onCollapse = actions::onCollapse,
        collapseLabel = stringResource(R.string.collapse_purpose_panel),
    )
    Column(Modifier.padding(end = 12.dp)) {
        PanelLabel(stringResource(R.string.purpose_text))
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it; actions.onTextChange(it) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.purpose_placeholder)) },
            // 和「画法」那一排按钮、色板上的圆一样是圆头的
            shape = CircleShape,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        )
    }
    // 收起来时面板一点点变矮（PanelColumn 跟着里面的东西变高变矮），输入法收起后再长回来
    if (compactWhileTyping && focused && imeVisible) return

    val angleLabel = stringResource(R.string.angle)
    val opacityLabel = stringResource(R.string.opacity)
    val densityLabel = stringResource(R.string.density)
    val densityLevels = DENSITY_LEVELS.map { stringResource(it) }
    // 三条滑条和自定义颜色的三条，名字那一列按最长的名字留，写值那一格按最宽的值留（密度最长的那一档、角度和不透明度两头的数）：
    // 头尾都对齐
    val widths = rememberSliderWidths(
        listOf(angleLabel, opacityLabel, densityLabel) + hsvLabels(),
        densityLevels + PurposeWatermarkStyle.ANGLE_RANGE.endTexts(::angleText) +
            PurposeWatermarkStyle.OPACITY_RANGE.endTexts(::percentText),
    )
    Column(Modifier.padding(end = 12.dp)) {
        PanelLabel(stringResource(R.string.color))
        ColorPicker(
            color = style.color,
            palette = PURPOSE_PALETTE,
            picking = model.picking,
            onColor = { rgb, _ -> actions.onStyleChange(style.copy(color = rgb)) },
            // 水印的样子不进撤销栈，自定义颜色的滑条松手时没有要收尾的
            onColorFinished = {},
            onEyedropper = { actions.onPickColor(!model.picking) },
            sliderWidths = widths,
        )
        SliderRow(
            label = angleLabel,
            value = style.angle,
            range = PurposeWatermarkStyle.ANGLE_RANGE,
            // 15° 一档：角度是凭眼睛对齐的，连续值反而难停在 0°、±45° 上；档再细刻度点就密成一条线
            steps = ((PurposeWatermarkStyle.ANGLE_RANGE.endInclusive -
                PurposeWatermarkStyle.ANGLE_RANGE.start) / ANGLE_STEP).roundToInt() - 1,
            valueText = angleText(style.angle),
            widths = widths,
            onChange = { actions.onStyleChange(style.copy(angle = it)) },
            modifier = Modifier.padding(top = 4.dp),
        )
        SliderRow(
            // 叫「不透明度」：数字越大越显眼，和拖动方向、显示的百分比是一回事
            label = opacityLabel,
            value = style.opacity,
            range = PurposeWatermarkStyle.OPACITY_RANGE,
            valueText = percentText(style.opacity),
            widths = widths,
            onChange = { actions.onStyleChange(style.copy(opacity = it)) },
            modifier = Modifier.padding(top = 4.dp),
        )
        SliderRow(
            label = densityLabel,
            value = style.density,
            range = PurposeWatermarkStyle.DENSITY_RANGE,
            valueText = densityLevels[densityLevel(style.density)],
            widths = widths,
            onChange = { actions.onStyleChange(style.copy(density = it)) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = actions::onReset, enabled = style != PurposeWatermarkStyle()) {
            Text(stringResource(R.string.reset_defaults))
        }
        Spacer(Modifier.weight(1f))
        // 关掉只是这次不加：文字记着，再打开还是它（见 EditorViewModel.setPurposeOn）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .toggleable(value = model.on, role = Role.Switch, onValueChange = actions::onApply)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            Text(stringResource(R.string.apply), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = model.on,
                onCheckedChange = null,
                thumbContent = if (model.on) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
                // 同设置里的开关（SwitchRow）：默认的对勾在白色滑块上看不见
                colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
            )
        }
    }
}

/**
 * 宽屏上的用途水印面板：和样式面板一样占框列表的位置（见 WideEditorPanel），画布留在旁边不被盖住。
 * 样式方块里没有水印，顶上总写着名字。
 */
@Composable
internal fun PurposePanel(model: PurposePanelModel, actions: PurposePanelActions, modifier: Modifier = Modifier) {
    WidePanel(modifier) { PurposePanelContent(model, actions) }
}

/** 密度的三档：滑条不分档，右边写它落在哪一档。 */
private val DENSITY_LEVELS = listOf(R.string.density_sparse, R.string.level_medium, R.string.density_dense)

private fun densityLevel(density: Float): Int = when {
    density < 0.8f -> 0
    density <= 1.25f -> 1
    else -> 2
}

private fun angleText(angle: Float) = "${angle.roundToInt()}°"

private const val ANGLE_STEP = 15f

/** 用途水印的色板：黑、灰、白、红、蓝、绿（见 PurposeWatermarkStyle.PALETTE），色板外的颜色走自定义和吸管。 */
internal val PURPOSE_PALETTE = PurposeWatermarkStyle.PALETTE.zip(
    listOf(R.string.color_black, R.string.color_gray, R.string.color_white, R.string.color_red, R.string.color_blue, R.string.color_green),
) { argb, name -> NamedColor(argb, name) }
