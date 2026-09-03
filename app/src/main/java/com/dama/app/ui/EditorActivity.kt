package com.dama.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.core.content.IntentCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dama.app.core.image.ImageIntake
import com.dama.app.engine.buildEngine
import com.dama.app.export.Exporter
import com.dama.app.export.MediaStoreSink
import com.dama.app.export.WatermarkDrawer
import com.dama.app.render.RendererRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 全应用唯一的 Activity（spec §7.1）：既是启动器入口，也是分享目标。
 *
 * 冷启动无 URI 时立即拉起系统 Photo Picker，不绘制自己的首屏。
 * 用户在 Picker 里按返回 = 退出 app —— 这个 app 没有「主页」这个概念。
 */
class EditorActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels {
        val app = applicationContext
        viewModelFactory {
            initializer {
                EditorViewModel(
                    intake = ImageIntake(app),
                    exporter = Exporter(
                        RendererRegistry.default(),
                        WatermarkDrawer(),
                        MediaStoreSink(app),
                    ),
                    engine = buildEngine(app),
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finish() else vm.onImageChosen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme { Surface { EditorScreen(vm) { finish() } } }
        }
        if (savedInstanceState == null) {
            route(intent)
        } else {
            // 重建路径：状态由 SavedStateHandle 恢复（spec §15 第 2 条）。
            // 等恢复落地再判断——恢复不出东西（副本被清掉了）才退回 Picker，
            // 否则会在图正要回来的那一瞬间弹出相册。
            lifecycleScope.launch {
                vm.state.first { !it.restoring }
                if (vm.state.value.image == null) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        val shared = intent?.let { incomingUri(it) }
        if (shared != null) {
            vm.onImageChosen(shared)               // ACTION_SEND 直接跳过 Picker
        } else if (vm.state.value.image == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    private fun incomingUri(intent: Intent): Uri? = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        Intent.ACTION_SEND_MULTIPLE ->
            // 批量在 M5（计划 07）；首版先只处理第一张，行为可预期
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.firstOrNull()
        else -> null
    }
}
