package moe.flinty.yomark.ui.settings

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import moe.flinty.yomark.ui.AppLocale
import moe.flinty.yomark.ui.YomarkActivity
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.billing.PurchaseStore
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.debug.sharedTraceLog
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.genai.NanoClient
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.export.brandWatermarkApplies
import moe.flinty.yomark.ui.layoutEdgeToEdge
import moe.flinty.yomark.ui.onboarding.TourActivity
import moe.flinty.yomark.ui.theme.AppThemeMode
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 设置（一级页）。首页和编辑器的右上角都进得来，所以「向上」就是 finish 回到来的那一页，
 * 不声明固定的 parentActivityName。
 *
 * 宽屏上分左右两栏（2026-10-17 起，见 SettingsHost）：点开的页都在这个 Activity 的右栏里，不再一页开一个 Activity。
 */
class SettingsActivity : YomarkActivity() {

    /**
     * 点开的那一页连同它下面的几页，最后一项在最上面；null 是还没点开过（见 SettingsHost）。
     * 手机上点一项开的是新的 SettingsPageActivity，这里一直是 null；分栏时它是右栏，窗口变窄后点开过的页原地占满整屏。
     */
    private var opened by mutableStateOf<List<SettingsDestination>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        layoutEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 存过就以存的为准（存的是 null 也算：退回一级页之后转个屏，不能又按启动时的 intent 打开）
        opened = if (savedInstanceState != null) savedInstanceState.opened() else intent.opened()
        val store = SettingsStore(applicationContext)
        setSettingsContent(store) {
            val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
            val debugTrace by store.debugTrace.collectAsStateWithLifecycle(initialValue = null)
            val current = config
            val developer = debugTrace
            if (current != null && developer != null) {
                // 改了语言 Activity 会重建，这里读一次就够
                val language = remember { AppLocale.current(this) }
                SettingsHost(
                    // 转向、分屏拖动时 Activity 会重建，读一次 Configuration 就够
                    twoPane = SettingsLayouts.twoPane(LocalConfiguration.current),
                    opened = opened,
                    onOpenedChange = { opened = it },
                    onLaunch = { page -> startActivity(SettingsPageActivity.intent(this, SettingsDestination(page))) },
                    directory = { selected, onOpen ->
                        SettingsScreen(
                            config = current,
                            onOpen = onOpen,
                            onNavigateUp = ::finish,
                            onReplayTour = { startActivity(TourActivity.intent(this)) },
                            developer = developer,
                            language = language,
                            selected = selected,
                        )
                    },
                    page = { destination, onOpen, onNavigateUp -> SettingsPageContent(destination, store, onOpen, onNavigateUp) },
                )
            }
        }
    }

    /** 窗口变宽时压在上面的 SettingsPageActivity 交回来的页，见 [intent]。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.opened()?.let { opened = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        opened?.let { outState.putParcelableArrayList(STATE_OPENED, ArrayList(it)) }
    }

    companion object {
        private const val EXTRA_OPENED = "moe.flinty.yomark.settings.OPENED"
        private const val STATE_OPENED = "opened"

        /**
         * 回到设置一级页，[opened] 叠在分栏的右栏里：SettingsPageActivity 在窗口变宽时把自己这几页交回来。
         * 一级页本来就在它们下面，不再开一个新的，压在上面的几页一并关掉。
         */
        fun intent(context: Context, opened: List<SettingsDestination>): Intent =
            Intent(context, SettingsActivity::class.java)
                .putParcelableArrayListExtra(EXTRA_OPENED, ArrayList(opened))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private fun Intent.opened(): List<SettingsDestination>? =
            IntentCompat.getParcelableArrayListExtra(this, EXTRA_OPENED, SettingsDestination::class.java).usable()

        private fun Bundle.opened(): List<SettingsDestination>? =
            BundleCompat.getParcelableArrayList(this, STATE_OPENED, SettingsDestination::class.java).usable()

        /** 有一页认不出（比如改名前留下的规则 id）就整串不要，退回一级页。 */
        private fun List<SettingsDestination>?.usable(): List<SettingsDestination>? =
            this?.takeIf { it.isNotEmpty() && it.all(SettingsDestination::valid) }
    }
}

/**
 * 手机上设置一级页以下的各页。共用这一个 Activity，打开哪一页由 [SettingsPage] 参数决定，
 * 文字里的一类另带规则 id；每次打开都是一个新实例，预见式返回照样是跨 Activity 的动画。
 * 宽屏上这几页都在 SettingsActivity 的右栏里，见 SettingsHost。
 */
class SettingsPageActivity : YomarkActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        layoutEdgeToEdge()
        super.onCreate(savedInstanceState)
        val destination = intent.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
            ?.let { SettingsDestination(it, intent.getStringExtra(EXTRA_RULE)) }
        // 文字里的一类：认不出的规则 id（比如改名前留下的快捷方式）就不打开
        if (destination == null || !destination.valid) {
            finish()
            return
        }
        // 窗口变宽（折叠屏展开、分屏拖宽）时这一页跟着重建。宽屏上设置分两栏，这一页连同它下面的几页叠进
        // 一级页的右栏，自己关掉；不放过场动画，窗口本身正在变
        if (SettingsLayouts.twoPane(resources.configuration)) {
            startActivity(
                SettingsActivity.intent(this, destination.chain),
                ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle(),
            )
            finishWithoutAnimation()
            return
        }
        val store = SettingsStore(applicationContext)
        setSettingsContent(store) {
            SettingsPageContent(
                destination = destination,
                store = store,
                onOpen = { next -> startActivity(intent(this, next)) },
                onNavigateUp = ::finish,
            )
        }
    }

    companion object {
        private const val EXTRA_PAGE = "moe.flinty.yomark.settings.PAGE"
        private const val EXTRA_RULE = "moe.flinty.yomark.settings.RULE"

        /** [SettingsDestination.ruleId] 只有 [SettingsPage.TEXT_RULE] 用：文字里的哪一类。 */
        fun intent(context: Context, destination: SettingsDestination): Intent =
            Intent(context, SettingsPageActivity::class.java)
                .putExtra(EXTRA_PAGE, destination.page.name)
                .apply { if (destination.ruleId != null) putExtra(EXTRA_RULE, destination.ruleId) }
    }
}

@Suppress("DEPRECATION")
private fun Activity.finishWithoutAnimation() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        finish()
    } else {
        finish()
        overridePendingTransition(0, 0)
    }
}

/**
 * 设置里的一页，连着 DataStore：手机上一页一个 SettingsPageActivity，分栏时在一级页的右栏（见 SettingsHost）。
 *
 * 写 DataStore 用 Activity 的 lifecycleScope：点完马上换到别的页、这一页离开了组合，写也不会跟着取消。
 *
 * @param onOpen 点进下一页：文字里的一类、识别引擎、调试记录。
 * @param onNavigateUp 标题栏左边的返回；null 就不放（分栏时右栏最底下那一页）。
 */
@Composable
internal fun SettingsPageContent(
    destination: SettingsDestination,
    store: SettingsStore,
    onOpen: (SettingsDestination) -> Unit,
    onNavigateUp: (() -> Unit)?,
) {
    val activity = LocalActivity.current as ComponentActivity
    val scope = activity.lifecycleScope
    when (destination.page) {
        SettingsPage.TEXT -> WithRecognitionConfig(store, scope) { config, onChange ->
            TextSettingsScreen(
                config = config,
                onChange = onChange,
                onOpenRule = { id -> onOpen(SettingsDestination(SettingsPage.TEXT_RULE, id)) },
                onNavigateUp = onNavigateUp,
            )
        }
        SettingsPage.TEXT_ENGINE -> WithRecognitionConfig(store, scope) { config, onChange ->
            TextEngineSettingsScreen(config, onChange, onNavigateUp = onNavigateUp)
        }
        SettingsPage.TEXT_RULE -> WithRecognitionConfig(store, scope) { config, onChange ->
            TextRuleSettingsScreen(checkNotNull(destination.ruleId), config, onChange, onNavigateUp = onNavigateUp)
        }
        SettingsPage.FACE -> WithRecognitionConfig(store, scope) { config, onChange ->
            FaceSettingsScreen(config, onChange, onNavigateUp = onNavigateUp)
        }
        SettingsPage.BARCODE -> WithRecognitionConfig(store, scope) { config, onChange ->
            BarcodeSettingsScreen(config, onChange, onNavigateUp = onNavigateUp)
        }
        SettingsPage.AI -> {
            // 进页时问一次 AICore；只看不下，见 NanoStatus
            val nano by produceState<NanoStatus?>(initialValue = null) { value = NanoClient.status() }
            WithRecognitionConfig(store, scope) { config, _ ->
                AiSettingsScreen(
                    nano = nano,
                    textEngine = config.textEngine,
                    onOpenEngine = { onOpen(SettingsDestination(SettingsPage.TEXT_ENGINE)) },
                    onNavigateUp = onNavigateUp,
                )
            }
        }
        SettingsPage.GENERAL -> {
            val restyleAll by store.restyleAllMasks.collectAsStateWithLifecycle(initialValue = null)
            val fullResolution by store.fullResolutionOcr.collectAsStateWithLifecycle(initialValue = null)
            // 水印的三项一起读到再画：「无水印」没选过是 null，单独收的话和「还没读到」分不开，会先闪一下
            val watermark by remember {
                combine(
                    store.brandWatermarkStyle,
                    store.brandWatermarkOff,
                    PurchaseStore(activity.applicationContext).unlocked(Edition.isFree),
                ) { style, off, unlocked -> BrandWatermarkSetting(style, unlocked, !brandWatermarkApplies(unlocked, off)) }
            }.collectAsStateWithLifecycle(initialValue = null)
            val restyle = restyleAll
            val mark = watermark
            val fullRes = fullResolution
            if (restyle != null && mark != null && fullRes != null) {
                GeneralSettingsScreen(
                    restyleAll = restyle,
                    onRestyleAllChange = { next -> scope.launch { store.setRestyleAllMasks(next) } },
                    fullResolution = fullRes,
                    onFullResolutionChange = { next -> scope.launch { store.setFullResolutionOcr(next) } },
                    watermarkStyle = mark.style,
                    onWatermarkStyleChange = { next ->
                        scope.launch { store.setBrandWatermarkStyle(next, mark.unlocked) }
                    },
                    watermarkUnlocked = mark.unlocked,
                    watermarkOff = mark.off,
                    onWatermarkOff = { scope.launch { store.setBrandWatermarkOff() } },
                    onNavigateUp = onNavigateUp,
                )
            }
        }
        SettingsPage.APPEARANCE -> {
            // 换了深浅时各页重建，读回来的就是新的；深浅没变（比如系统本来就是浅色，从跟随系统换到浅色）不重建，靠这里记着
            var themeMode by remember { mutableStateOf(AppThemeMode.current(activity)) }
            val themeColor by store.themeColor.collectAsStateWithLifecycle(initialValue = null)
            val scanStyle by store.scanStyle.collectAsStateWithLifecycle(initialValue = null)
            val color = themeColor
            val style = scanStyle
            if (color != null && style != null) {
                AppearanceSettingsScreen(
                    themeMode = themeMode,
                    onThemeModeChange = { next ->
                        themeMode = next
                        AppThemeMode.set(activity, next)
                    },
                    themeColor = color,
                    onThemeColorChange = { next -> scope.launch { store.setThemeColor(next) } },
                    scanStyle = style,
                    onScanStyleChange = { next -> scope.launch { store.setScanStyle(next) } },
                    onNavigateUp = onNavigateUp,
                )
            }
        }
        SettingsPage.LABS -> WithRecognitionConfig(store, scope) { config, onChange ->
            LabsSettingsScreen(config, onChange, onNavigateUp = onNavigateUp)
        }
        SettingsPage.DEVELOPER -> {
            val debugTrace by store.debugTrace.collectAsStateWithLifecycle(initialValue = null)
            val capacity by store.debugTraceCapacity.collectAsStateWithLifecycle(initialValue = null)
            val textBoxes by store.showTextBoxes.collectAsStateWithLifecycle(initialValue = null)
            val on = debugTrace
            val keep = capacity
            val boxes = textBoxes
            if (on != null && keep != null && boxes != null) {
                DeveloperSettingsScreen(
                    debugTrace = on,
                    onDebugTraceChange = { next -> scope.launch { store.setDebugTrace(next) } },
                    onNavigateUp = onNavigateUp,
                    traceCapacity = keep,
                    onTraceCapacityChange = { next -> scope.launch { store.setDebugTraceCapacity(next) } },
                    onOpenTraceHistory = { onOpen(SettingsDestination(SettingsPage.DEBUG_TRACE)) },
                    showTextBoxes = boxes,
                    onShowTextBoxesChange = { next -> scope.launch { store.setShowTextBoxes(next) } },
                )
            }
        }
        SettingsPage.LANGUAGE -> LanguageSettingsScreen(
            language = remember { AppLocale.current(activity) },
            onLanguageChange = { next -> AppLocale.set(activity, next) },
            onNavigateUp = onNavigateUp,
        )
        SettingsPage.ABOUT -> {
            val debugTrace by store.debugTrace.collectAsStateWithLifecycle(initialValue = null)
            debugTrace?.let { on ->
                AboutSettingsScreen(
                    debugTrace = on,
                    onDebugTraceChange = { next -> scope.launch { store.setDebugTrace(next) } },
                    onReset = { scope.launch { store.resetSettings() } },
                    onNavigateUp = onNavigateUp,
                )
            }
        }
        SettingsPage.DEBUG_TRACE -> {
            val log = sharedTraceLog(activity.applicationContext)
            val runs by log.runs.collectAsStateWithLifecycle()
            DebugTraceHistoryScreen(runs = runs, onClear = log::clear, onNavigateUp = onNavigateUp)
        }
    }
}

/** 识别方案的各页都直接读写 DataStore，不经过编辑器：编辑器的 ViewModel 自己在收这个 flow。 */
@Composable
private fun WithRecognitionConfig(
    store: SettingsStore,
    scope: CoroutineScope,
    screen: @Composable (RecognitionConfig, (RecognitionConfig) -> Unit) -> Unit,
) {
    val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
    config?.let { current ->
        screen(current) { next -> scope.launch { store.setRecognitionConfig(next) } }
    }
}

/**
 * 各页都直接读写 DataStore，不经过编辑器：编辑器的 ViewModel 自己在收这些 flow，
 * 从设置页返回时它已经按新方案重跑过了。
 *
 * 还没读到（null）的那一下只画空底色，底色和设置页的页面底色一致。
 * DataStore 在进程里读过一次就有缓存，实际看不出来。
 */
private fun ComponentActivity.setSettingsContent(store: SettingsStore, screen: @Composable () -> Unit) {
    setContent {
        YomarkTheme(store) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) { screen() }
        }
    }
}

/** 通用页上的品牌水印：选的字样、能不能去水印、现在是不是无水印。 */
private data class BrandWatermarkSetting(val style: BrandWatermarkStyle, val unlocked: Boolean, val off: Boolean)
