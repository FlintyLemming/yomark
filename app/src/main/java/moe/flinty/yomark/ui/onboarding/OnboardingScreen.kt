package moe.flinty.yomark.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次安装的一屏引导（spec §12 M5）。只出现一次。
 *
 * 一句话说清是干什么的，再列三条承诺，每条一行，不展开解释。
 */
@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    Column(
        // 边到边（见 EditorActivity）：先让开系统栏与挖孔，再留页边距
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("有码", style = MaterialTheme.typography.displaySmall)
        Text(
            "自动找出截图里的手机号、地址、人脸并打码。",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        Promise("不需要任何权限")
        Promise("不联网，图片不离开手机")
        Promise("导出时去掉位置、机型等隐藏信息")

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        ) { Text("选择图片") }
    }
}

@Composable
private fun Promise(text: String) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
