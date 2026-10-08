package com.yomark.app.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.yomark.app.billing.BillingRepository
import com.yomark.app.billing.Edition
import com.yomark.app.billing.PurchaseStore
import com.yomark.app.data.SettingsStore
import com.yomark.app.ui.EditorActivity
import com.yomark.app.ui.components.FreeEditionDialog
import com.yomark.app.ui.components.PaywallDialog
import com.yomark.app.ui.enableLightEdgeToEdge
import com.yomark.app.ui.onboarding.OnboardingScreen
import com.yomark.app.ui.settings.SettingsActivity
import com.yomark.app.ui.theme.YomarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 启动器入口：首页（第一次打开时先是引导页）。
 *
 * 选好图交给 EditorActivity，编辑器、设置都是压在它上面的独立 Activity，
 * 返回一律交给系统处理（finish 回到下面那一层），预见式返回的跨 Activity 动画才放得出来。
 * Picker 里取消就留在首页，不退出应用。
 */
class HomeActivity : ComponentActivity() {

    private val settings by lazy { SettingsStore(applicationContext) }

    private val billing by lazy {
        BillingRepository(applicationContext, PurchaseStore(applicationContext), lifecycleScope)
    }

    private val picker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) startActivity(EditorActivity.intent(this, uri))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 先用缓存点亮购买态，再尝试联网校验。只在 Play 商店进程里发生（spec §10 / §13）。
        billing.start()

        setContent {
            // null = 还没读到设置。这一瞬间什么都不画：先画首页再切引导页会闪一下，
            // 而这一屏恰恰是用户对这个 app 的第一印象。
            val seen by produceState<Boolean?>(initialValue = null) {
                value = settings.onboardingSeen.first()
            }
            // 点过「选择图片」之后就不该再回到引导页。onboardingSeen 是异步写的，
            // 光靠上面那一次读回来的值，从编辑器返回时又会把引导页画出来。
            var dismissed by rememberSaveable { mutableStateOf(false) }
            var paywall by rememberSaveable { mutableStateOf(false) }
            val isPro by billing.isPro.collectAsStateWithLifecycle()

            YomarkTheme(settings) {
                Surface {
                    when {
                        seen == null -> Unit
                        seen == false && !dismissed -> OnboardingScreen {
                            dismissed = true
                            lifecycleScope.launch { settings.markOnboardingSeen() }
                            pickImage()
                        }
                        else -> HomeScreen(
                            onPickImage = ::pickImage,
                            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
                            // 已购用户不该再看见购买入口；开源版留着它，点开是免费版说明与打赏
                            onRemoveWatermark = if (isPro && !Edition.isFree) null else ({ paywall = true }),
                        )
                    }
                    if (paywall && Edition.isFree) {
                        FreeEditionDialog(onDismiss = { paywall = false })
                    } else if (paywall) {
                        PaywallDialog(
                            onBuy = {
                                paywall = false
                                lifecycleScope.launch { billing.launchPurchase(this@HomeActivity) }
                            },
                            onDismiss = { paywall = false },
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        billing.stop()
        super.onDestroy()
    }

    private fun pickImage() {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
}
