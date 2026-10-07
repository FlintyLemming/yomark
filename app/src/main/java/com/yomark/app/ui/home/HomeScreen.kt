package com.yomark.app.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yomark.app.billing.Edition

/**
 * 首页：正中一个大按钮选图，右上角是与具体某张图无关的入口（去水印、设置）。
 *
 * 跟某张图有关的（撤销、用途水印……）留在编辑器里；首页不放第二个主操作，
 * 从相册分享进来的图根本不经过这一页。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPickImage: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    /** 去水印的购买入口（开源版是免费版说明与打赏）。已购用户传 null，按钮就不出现。 */
    onRemoveWatermark: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("有码") },
                actions = {
                    if (onRemoveWatermark != null) {
                        IconButton(onClick = onRemoveWatermark) { Icon(Icons.Filled.WorkspacePremium, if (Edition.isFree) "全功能免费版" else "去除水印") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "设置") }
                },
                // 边到边（见 enableLightEdgeToEdge）：横屏时左右还要让开挖孔与三键导航
                windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Button(
                    onClick = onPickImage,
                    shape = RoundedCornerShape(40.dp),
                    contentPadding = PaddingValues(24.dp),
                    modifier = Modifier.size(200.dp),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(56.dp))
                        Text("选择图片", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    "也可以在相册里分享图片到「有码」，支持多张",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
