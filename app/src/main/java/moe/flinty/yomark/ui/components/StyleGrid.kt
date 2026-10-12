package moe.flinty.yomark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskStyleInfo
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import moe.flinty.yomark.R

/**
 * 宽屏的样式按钮：四种样式排成两行两列的圆角方块（宽屏增补设计）。
 *
 * 方块跟着给它的地方变大变小，不在旁边空出一块：竖着拿时铺满下面那一块的左半边（[fillHeight]），
 * 横着拿时铺满右边那一栏的宽、高取宽的四分之三。方块大了，里面的小样子和名字跟着放大。
 *
 * 一行一行地放：色块、马赛克，模糊、马克笔——越往后越只是做个样子。和手机的样式栏是同一份状态、同一套动作：
 * 点一种换样式，再点当前那一种才弹出或收起调节面板。
 *
 * @param fillHeight 方块往下长到给它的高度为止，但不比宽高出 [MAX_ASPECT] 倍；false 时高按宽定
 * @param holdHeight 调节面板开着、下面那一块跟着面板长高时，方块停在面板打开前的高度，不跟着变高
 */
@Composable
fun StyleGrid(
    model: StyleBarModel,
    actions: StyleBarActions,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    holdHeight: Boolean = false,
) {
    val look = model.look
    val held = remember { HeldHeight() }
    Layout(
        content = {
            MaskStyleInfo.ORDER.forEach { style ->
                StyleTile(
                    style = style,
                    options = look.options,
                    nearColors = model.nearColors,
                    selected = style == look.style,
                    panelOpen = model.panelOpen,
                    onClick = { actions.onStyleClick(style) },
                )
            }
        },
        modifier = modifier.selectableGroup(),
    ) { measurables, constraints ->
        val gap = GAP.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else (TILE_MIN * COLUMNS).roundToPx() + gap * (COLUMNS - 1)
        val tileW = ((width - gap * (COLUMNS - 1)) / COLUMNS).coerceAtLeast(0)
        val byWidth = (tileW * SIDE_ASPECT).roundToInt().coerceIn(TILE_MIN.roundToPx(), TILE_MAX.roundToPx())
        val tileH = when {
            !fillHeight || !constraints.hasBoundedHeight -> byWidth
            // 面板开着：停在打开前的高度；一次也没量过（开着面板转过来的）就按宽定
            holdHeight -> (if (held.px > 0) held.px else byWidth).coerceAtMost(((constraints.maxHeight - gap) / ROWS).coerceAtLeast(0))
            else -> min((constraints.maxHeight - gap) / ROWS, (tileW * MAX_ASPECT).roundToInt())
                .coerceAtLeast(0)
                .also { held.px = it }
        }
        val gridH = tileH * ROWS + gap * (ROWS - 1)
        val tiles = measurables.map { it.measure(Constraints.fixed(tileW, tileH)) }
        val height = gridH.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            tiles.forEachIndexed { i, tile ->
                tile.place((i % COLUMNS) * (tileW + gap), (i / COLUMNS) * (tileH + gap))
            }
        }
    }
}

/** 面板打开前方块的高度（像素）。只在量的时候读写，不触发重组。 */
private class HeldHeight {
    var px = 0
}

/**
 * 一个方块：上面是这种样式的小样子（跟着参数走，色块是当前的颜色或取到的近似色），下面是名字。
 * 选中的那一个垫上底色、描一圈主题色，名字后面的小箭头说明再点一下就展开（或收起）调节面板：
 * 收着时朝上，开着时朝下。
 * 圆角随方块大小走，小方块圆一些，大方块是圆角矩形。
 */
@Composable
private fun StyleTile(
    style: MaskStyle,
    options: MaskOptions,
    nearColors: List<Int>,
    selected: Boolean,
    panelOpen: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints {
        val side = min(maxWidth, maxHeight)
        val shape = RoundedCornerShape(min(CORNER_MAX, side * 0.3f))
        val large = side >= LARGE_TILE
        val scale = (side / TILE_MIN).coerceIn(1f, GLYPH_MAX_SCALE)
        Column(
            Modifier
                .fillMaxSize()
                .clip(shape)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) colors.primary else colors.outlineVariant,
                    shape = shape,
                )
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            StyleGlyph(style, options, nearColors, scale)
            Spacer(Modifier.height(4.dp * scale))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(MaskStyleInfo.label(style)),
                    style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                    maxLines = 1,
                )
                if (selected) {
                    Icon(
                        if (panelOpen) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
                        contentDescription = null,
                        modifier = Modifier.size(if (large) 16.dp else 14.dp),
                        tint = colors.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

private const val ROWS = 2
private const val COLUMNS = 2

private val GAP = 12.dp

/** 方块高最多是宽的这么多倍：再高就成了细长条，宁可在下面留一点空。 */
private const val MAX_ASPECT = 1.25f

/** 高按宽定时（横着拿的右边那一栏）取宽的四分之三，夹在 [TILE_MIN]～[TILE_MAX] 之间。 */
private const val SIDE_ASPECT = 0.75f
private val TILE_MIN = 64.dp
private val TILE_MAX = 96.dp

/** 短边到这么大，名字换大一号的字。 */
private val LARGE_TILE = 88.dp
private val CORNER_MAX = 24.dp
private const val GLYPH_MAX_SCALE = 1.6f
