package moe.flinty.yomark.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeOut
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.flinty.yomark.ui.YomarkActivity
import moe.flinty.yomark.billing.BillingRepository
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.billing.PurchaseStore
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.ui.EditorActivity
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.components.FreeEditionDialog
import moe.flinty.yomark.ui.components.PaywallDialog
import moe.flinty.yomark.ui.components.foldTween
import moe.flinty.yomark.ui.components.restorePurchase
import moe.flinty.yomark.ui.enableLightEdgeToEdge
import moe.flinty.yomark.ui.onboarding.EngineProbeModel
import moe.flinty.yomark.ui.onboarding.OnboardingTour
import moe.flinty.yomark.ui.onboarding.PpOcrProbe
import moe.flinty.yomark.ui.onboarding.TourSample
import moe.flinty.yomark.ui.onboarding.setTourChat
import moe.flinty.yomark.ui.onboarding.setTourEngine
import moe.flinty.yomark.ui.settings.SettingsActivity
import moe.flinty.yomark.ui.thanks.showThanksWhenPurchased
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 启动器入口：首页（第一次打开时先是引导，见 OnboardingTour）。
 *
 * 选好图交给 EditorActivity，编辑器、设置都是压在它上面的独立 Activity，
 * 返回一律交给系统处理（finish 回到下面那一层），预见式返回的跨 Activity 动画才放得出来。
 * Picker 里取消就留在首页，不退出应用。
 */
class HomeActivity : YomarkActivity() {

    private val settings by lazy { SettingsStore(applicationContext) }

    private val billing by lazy {
        BillingRepository(applicationContext, PurchaseStore(applicationContext), lifecycleScope)
    }

    /** 引导里给 PP-OCR 测速（见 EngineProbeModel）：转屏、进程被杀都不重测。 */
    private val probe: EngineProbeModel by viewModels {
        viewModelFactory { initializer { EngineProbeModel(createSavedStateHandle()) } }
    }

    // 多选：选一张就是单张编辑，选多张进批量会话，逐张过、走完统一导出（spec §7.6）
    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) startActivity(EditorActivity.intent(this, uris))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)

        // 先用缓存点亮购买态，再尝试联网校验。只在 Play 商店进程里发生（spec §10 / §13）。
        billing.start()
        showThanksWhenPurchased(billing)

        setContent {
            // 走完、跳过引导就不该再回到引导。onboardingSeen 是异步写的，
            // 光靠读回来的值，从编辑器、设置返回时（换语言会重建这一页）又会把引导画出来。
            var dismissed by rememberSaveable { mutableStateOf(false) }
            // null = 还没读到设置。这一瞬间什么都不画：先画首页再切引导会闪一下，
            // 而这一屏恰恰是用户对这个 app 的第一印象。引导要的样图也在这时解好，引导的头一帧就有图
            var launch by remember { mutableStateOf<Launch?>(null) }
            LaunchedEffect(Unit) {
                launch = if (dismissed || settings.onboardingSeen.first()) {
                    Launch.Home
                } else {
                    val config = settings.recognitionConfig.first()
                    Launch.Tour(
                        sample = withContext(Dispatchers.Default) { TourSample.load(resources) },
                        scanStyle = settings.scanStyle.first(),
                        chatOn = config.chatState != RuleState.OFF,
                        engine = config.textEngine,
                    )
                }
            }
            val config by settings.recognitionConfig.collectAsStateWithLifecycle(initialValue = null as RecognitionConfig?)
            var paywall by rememberSaveable { mutableStateOf(false) }
            val isPro by billing.isPro.collectAsStateWithLifecycle()
            // 买成了（或者联网校验发现早就买过）：购买框跟着关掉。入口按钮都没了，框不能还开着
            LaunchedEffect(isPro) { if (isPro && !Edition.isFree) paywall = false }

            val finishTour = {
                dismissed = true
                probe.stop()
                lifecycleScope.launch { settings.markOnboardingSeen() }
                Unit
            }

            YomarkTheme(settings) {
                Surface {
                    val current = launch ?: return@Surface
                    // 引导走完（跳过、选图、去设置）时淡出，首页先垫在底下
                    val tour = remember { MutableTransitionState(current is Launch.Tour && !dismissed) }
                    tour.targetState = current is Launch.Tour && !dismissed
                    if (!tour.currentState || !tour.targetState) {
                        HomeScreen(
                            onPickImage = ::pickImage,
                            onSettings = ::openSettings,
                            // 已购用户不该再看见购买入口；开源版留着它，点开是去除水印与打赏
                            onRemoveWatermark = if (isPro && !Edition.isFree) null else ({ paywall = true }),
                        )
                    }
                    if (current is Launch.Tour) {
                        // 引导一打开就在后台测，翻到「识别引擎」那一页时多半已经有结论
                        LaunchedEffect(Unit) {
                            val bench = PpOcrProbe(applicationContext, current.sample)
                            probe.start(prepare = bench::prepare, recognize = bench::recognize)
                        }
                        val advice by probe.advice.collectAsStateWithLifecycle()
                        AnimatedVisibility(tour, enter = EnterTransition.None, exit = fadeOut(foldTween(false))) {
                            OnboardingTour(
                                sample = current.sample,
                                scanStyle = current.scanStyle,
                                chatOn = config?.let { it.chatState != RuleState.OFF } ?: current.chatOn,
                                onChatChange = ::setChat,
                                engineAdvice = advice,
                                engine = config?.textEngine ?: current.engine,
                                onEngineChange = ::setEngine,
                                onSkip = finishTour,
                                onPickImage = {
                                    finishTour()
                                    pickImage()
                                },
                                onOpenSettings = {
                                    finishTour()
                                    openSettings()
                                },
                                interactive = tour.targetState,
                            )
                        }
                        // 淡完了就放掉样图（一张 1080×1680 的位图）
                        if (tour.isIdle && !tour.currentState) LaunchedEffect(Unit) { launch = Launch.Home }
                    }
                    if (paywall && Edition.isFree) {
                        FreeEditionDialog(unlocked = isPro, onUnlock = billing::unlockFree, onDismiss = { paywall = false })
                    } else if (paywall) {
                        PaywallDialog(
                            onBuy = { lifecycleScope.launch { billing.launchPurchase(this@HomeActivity) } },
                            onRestore = { restorePurchase(billing) },
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

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun setChat(on: Boolean) {
        lifecycleScope.launch { settings.setTourChat(on) }
    }

    private fun setEngine(engine: TextEngineOption) {
        lifecycleScope.launch { settings.setTourEngine(engine) }
    }

    /** 这一页一打开是首页还是引导。 */
    private sealed interface Launch {
        data object Home : Launch
        class Tour(val sample: SourceImage, val scanStyle: ScanStyle, val chatOn: Boolean, val engine: TextEngineOption) : Launch
    }
}
