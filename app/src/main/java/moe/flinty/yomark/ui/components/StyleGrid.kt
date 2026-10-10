package moe.flinty.yomark.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import moe.flinty.yomark.core.model.MaskOptions
import moe.flinty.yomark.core.model.MaskStyle
import moe.flinty.yomark.render.MaskStyleInfo

/**
 * 宽屏的样式栏：六种样式排成两行三列的圆钮（宽屏增补设计）。
 *
 * 照草图一列一列地放：第一列是色块和表情，往右依次是抹除、马赛克，模糊、马克笔——
 * 左边盖得严，越往右越只是做个样子。和手机的样式栏是同一份状态、同一套动作：
 * 点一种换样式并弹出调节面板，再点当前那一种收起或弹出面板；安全性说明贴在圆钮下面。
 */
@Composable
fun StyleGrid(model: StyleBarModel, actions: StyleBarActions, modifier: Modifier = Modifier) {
    val look = model.look
    Column(modifier) {
        Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(GAP)) {
            MaskStyleInfo.ORDER.chunked(ROWS).forEach { column ->
                Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
                    column.forEach { style ->
                        StyleCircle(
                            style = style,
                            options = look.options,
                            selected = style == look.style,
                            panelOpen = model.panelOpen,
                            onClick = { actions.onStyleClick(style) },
                        )
                    }
                }
            }
        }
        val note = MaskStyleInfo.note(look.style) ?: model.degradeNote
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.width(WIDTH).padding(top = 8.dp),
            )
        }
    }
}

/**
 * 一个圆钮：上面是这种样式的小样子（跟着参数走，色块是当前的颜色），下面是名字。
 * 选中的那一个垫上底色、描一圈主题色，名字后面的小箭头说明再点一下就展开（或收起）调节面板。
 */
@Composable
private fun StyleCircle(
    style: MaskStyle,
    options: MaskOptions,
    selected: Boolean,
    panelOpen: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .size(CIRCLE)
            .clip(CircleShape)
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) colors.primary else colors.outlineVariant,
                shape = CircleShape,
            )
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        StyleGlyph(style, options)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MaskStyleInfo.label(style),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
            )
            if (selected) {
                Icon(
                    if (panelOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colors.onSecondaryContainer,
                )
            }
        }
    }
}

/** 两行。 */
private const val ROWS = 2

private val CIRCLE = 64.dp
private val GAP = 12.dp

/** 三列圆钮的总宽，安全性说明按它折行。 */
private val WIDTH = CIRCLE * 3 + GAP * 2
