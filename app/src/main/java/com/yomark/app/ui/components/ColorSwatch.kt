package com.yomark.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 一个可选的圆形色块。用途水印的颜色、设置里的主题色都用它。 */
@Composable
internal fun ColorSwatch(
    color: Color,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    // 选中的外面套一圈主题色；白色色块靠常驻的细边才看得出边界
    Box(
        modifier
            .size(34.dp)
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
            .padding(if (selected) 5.dp else 2.dp)
            .background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .semantics {
                contentDescription = name
                this.selected = selected
            }
            .clickable(role = Role.RadioButton, onClick = onClick),
    )
}
