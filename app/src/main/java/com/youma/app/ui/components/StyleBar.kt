package com.youma.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.youma.app.core.model.MaskStyle
import com.youma.app.render.MaskStyleInfo

/**
 * 样式选择器（spec §7.5）：作用于**整张图的所有遮罩**，不是逐项。
 *
 * 安全性说明与降级说明就贴在选择器下面——用户切到模糊的那一刻就该知道
 * 模糊不是安全手段，而不是导出之后才发现。
 */
@Composable
fun StyleBar(
    style: MaskStyle,
    onChange: (MaskStyle) -> Unit,
    degradeNote: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaskStyle.entries.forEach { s ->
                FilterChip(
                    selected = s == style,
                    onClick = { onChange(s) },
                    label = { Text(MaskStyleInfo.label(s)) },
                )
            }
        }
        val note = MaskStyleInfo.note(style) ?: degradeNote
        if (note != null) {
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}
