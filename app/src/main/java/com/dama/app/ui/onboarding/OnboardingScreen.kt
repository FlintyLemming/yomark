package com.dama.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 首次安装的一屏引导（spec §12 M5）。只出现一次。
 *
 * 内容就是产品的核心承诺本身——对一个隐私工具来说，
 * 权限列表上的空白就是最有力的产品说明。
 */
@Composable
fun OnboardingScreen(onStart: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("DAMA", style = MaterialTheme.typography.displaySmall)
        Text(
            "给截图打码，不把它交给任何人。",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        Promise("不申请任何权限", "选图走系统相册选择器，写回相册用的是你自己创建的文件。")
        Promise("识别与编辑全程不联网", "文字、人脸、条码三个模型都编译在安装包里，飞行模式照常工作。")
        Promise("导出自动清除元数据", "重新编码输出，GPS、设备型号、拍摄时间一概不带。")

        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        ) { Text("选择图片") }
    }
}

@Composable
private fun Promise(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
}
