package com.youma.app.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.youma.app.billing.BillingRepository
import com.youma.app.billing.PurchaseStore
import com.youma.app.core.image.ImageIntake
import com.youma.app.data.SettingsStore
import com.youma.app.engine.buildEngine
import com.youma.app.export.Exporter
import com.youma.app.export.MediaStoreSink
import com.youma.app.export.WatermarkDrawer
import com.youma.app.render.RendererRegistry
import com.youma.app.ui.onboarding.OnboardingScreen
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
                    engineProvider = { cfg -> buildEngine(app, cfg) },
                    settings = SettingsStore(app),
                    savedState = createSavedStateHandle(),
                )
            }
        }
    }

    private val settings by lazy { SettingsStore(applicationContext) }

    private val billing by lazy {
        BillingRepository(applicationContext, PurchaseStore(applicationContext), lifecycleScope)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) finish() else vm.onImageChosen(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 边到边，系统栏图标一律深色。界面只有浅色一套（MaterialTheme 的默认配色，不跟随系统深色模式），
        // 所以 detectDarkMode 恒为 false：手机开着深色模式时图标也不能变白，否则落在浅底上看不见。
        // Android 15 起系统强制边到边、状态栏透明，不声明的话父主题的白色图标就是这么看不见的。
        // 用 auto 而不是 light：三键导航时由系统在按钮后面垫一层半透明底，手势导航时什么都不垫。
        // 各屏自己让开系统栏和挖孔（WindowInsets），不靠系统把内容往里挤。
        val lightBars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { false }
        enableEdgeToEdge(statusBarStyle = lightBars, navigationBarStyle = lightBars)
        super.onCreate(savedInstanceState)
        val coldStart = savedInstanceState == null
        val shared = incomingUris(intent)

        // 先用缓存点亮购买态，再尝试联网校验。这是全应用唯一会碰网络的地方，
        // 而且只在 Play 商店进程里发生（spec §10 / §13）。
        billing.start()
        vm.observePro(billing.isPro)

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
                        seen == false && !dismissed && shared.isEmpty() && coldStart -> OnboardingScreen {
                            dismissed = true
                            lifecycleScope.launch { settings.markOnboardingSeen() }
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }
                        else -> EditorScreen(
                            vm = vm,
                            onBuyClicked = {
                                lifecycleScope.launch { billing.launchPurchase(this@EditorActivity) }
                            },
                            onClose = { finish() },
                        )
                    }
                }
            }
        }

        if (coldStart) {
            lifecycleScope.launch {
                // 看过引导的、以及从分享进来的，走原来的路由；没看过的等用户点按钮，
                // 否则 Picker 会盖在引导页上面弹出来。
                if (shared.isNotEmpty() || settings.onboardingSeen.first()) route(intent)
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

    override fun onDestroy() {
        billing.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route(intent)
    }

    private fun route(intent: Intent?) {
        val shared = intent?.let { incomingUris(it) }.orEmpty()
        if (shared.isNotEmpty()) {
            vm.onImagesChosen(shared)              // ACTION_SEND(_MULTIPLE) 直接跳过 Picker
        } else if (vm.state.value.image == null) {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
    }

    /**
     * Picker 一律是单选。多选 Picker 多一步「确认」，而批量按 spec §7.6 只从
     * ACTION_SEND_MULTIPLE 进——用户已经在相册里选好了，不该再选一次。
     */
    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }
}
