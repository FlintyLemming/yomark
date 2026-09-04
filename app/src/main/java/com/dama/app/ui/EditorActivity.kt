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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dama.app.core.image.ImageIntake
import com.dama.app.data.SettingsStore
import com.dama.app.engine.buildEngine
import com.dama.app.export.Exporter
import com.dama.app.export.MediaStoreSink
import com.dama.app.export.WatermarkDrawer
import com.dama.app.render.RendererRegistry
import com.dama.app.ui.onboarding.OnboardingScreen
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
                    settings = SettingsStore(app),
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }

    private val settings by lazy { SettingsStore(applicationContext) }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finish() else vm.onImageChosen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val coldStart = savedInstanceState == null
        val shared = incomingUri(intent)

        setContent {
            // null = 还没读到设置。这一瞬间什么都不画：先画编辑器再切引导页会闪一下，
            // 而这一屏恰恰是用户对这个 app 的第一印象。
            val seen by produceState<Boolean?>(initialValue = null) {
                value = settings.onboardingSeen.first()
            }
            // 点过「选择图片」之后这一次就不该再回到引导页。onboardingSeen 是异步写的，
            // 光靠它回读会在 Picker 返回后又把引导页画回来。
            var dismissed by remember { mutableStateOf(false) }

            MaterialTheme {
                Surface {
                    when {
                        seen == null -> Unit
                        seen == false && !dismissed && shared == null && coldStart -> OnboardingScreen {
                            dismissed = true
                            lifecycleScope.launch { settings.markOnboardingSeen() }
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        else -> EditorScreen(vm) { finish() }
                    }
                }
            }
        }

        if (coldStart) {
            lifecycleScope.launch {
                // 看过引导的、以及从分享进来的，走原来的路由；没看过的等用户点按钮，
                // 否则 Picker 会盖在引导页上面弹出来。
                if (shared != null || settings.onboardingSeen.first()) route(intent)
            }
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
