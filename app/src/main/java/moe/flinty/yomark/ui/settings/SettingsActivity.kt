package moe.flinty.yomark.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import moe.flinty.yomark.ui.AppLocale
import moe.flinty.yomark.ui.YomarkActivity
import moe.flinty.yomark.billing.Edition
import moe.flinty.yomark.billing.PurchaseStore
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.debug.sharedTraceLog
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.engine.genai.NanoClient
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.export.brandWatermarkApplies
import moe.flinty.yomark.ui.enableLightEdgeToEdge
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 设置（一级页）。首页和编辑器的右上角都进得来，所以「向上」就是 finish 回到来的那一页，
 * 不声明固定的 parentActivityName。
 */
class SettingsActivity : YomarkActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setSettingsContent(store) {
            val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
            val reminder by store.pendingExportReminder.collectAsStateWithLifecycle(initialValue = null)
            val debugTrace by store.debugTrace.collectAsStateWithLifecycle(initialValue = null)
            val current = config
            val on = reminder
            val developer = debugTrace
            if (current != null && on != null && developer != null) {
                SettingsScreen(
                    config = current,
                    exportReminder = on,
                    onOpen = { page -> startActivity(SettingsPageActivity.intent(this, page)) },
                    onNavigateUp = ::finish,
                    developer = developer,
                    // 改了语言 Activity 会重建，这里读一次就够
                    language = remember { AppLocale.current(this) },
                )
            }
        }
    }
}

/**
 * 设置一级页以下的各页。共用这一个 Activity，打开哪一页由 [SettingsPage] 参数决定，
 * 文字里的一类另带规则 id；每次打开都是一个新实例，预见式返回照样是跨 Activity 的动画。
 */
class SettingsPageActivity : YomarkActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        val ruleId = intent.getStringExtra(EXTRA_RULE)
        // 文字里的一类：认不出的规则 id（比如改名前留下的快捷方式）就不打开
        if (page == null || (page == SettingsPage.TEXT_RULE && RuleCatalog.all.none { it.id == ruleId })) {
            finish()
            return
        }
        val store = SettingsStore(applicationContext)
        setSettingsContent(store) { SettingsPageContent(page, ruleId, store) }
    }

    @Composable
    private fun SettingsPageContent(page: SettingsPage, ruleId: String?, store: SettingsStore) {
        when (page) {
            SettingsPage.TEXT -> WithRecognitionConfig(store) { config, onChange ->
                TextSettingsScreen(
                    config = config,
                    onChange = onChange,
                    onOpenEngine = { startActivity(intent(this, SettingsPage.TEXT_ENGINE)) },
                    onOpenRule = { id -> startActivity(intent(this, SettingsPage.TEXT_RULE, id)) },
                    onNavigateUp = ::finish,
                )
            }
            SettingsPage.TEXT_ENGINE -> WithRecognitionConfig(store) { config, onChange ->
                TextEngineSettingsScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.TEXT_RULE -> WithRecognitionConfig(store) { config, onChange ->
                TextRuleSettingsScreen(checkNotNull(ruleId), config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.FACE -> WithRecognitionConfig(store) { config, onChange ->
                FaceSettingsScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.BARCODE -> WithRecognitionConfig(store) { config, onChange ->
                BarcodeSettingsScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.CHAT -> WithRecognitionConfig(store) { config, onChange ->
                ChatSettingsScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.AI -> {
                // 进页时问一次 AICore；只看不下，见 NanoStatus
                val nano by produceState<NanoStatus?>(initialValue = null) { value = NanoClient.status() }
                AiSettingsScreen(nano, onNavigateUp = ::finish)
            }
            SettingsPage.EXPORT -> {
                val reminder by store.pendingExportReminder.collectAsStateWithLifecycle(initialValue = null)
                // 三项一起读到再画：「无水印」没选过是 null，单独收的话和「还没读到」分不开，会先闪一下
                val watermark by remember {
                    combine(
                        store.brandWatermarkStyle,
                        store.brandWatermarkOff,
                        PurchaseStore(applicationContext).unlocked(Edition.isFree),
                    ) { style, off, unlocked -> BrandWatermarkSetting(style, unlocked, !brandWatermarkApplies(unlocked, off)) }
                }.collectAsStateWithLifecycle(initialValue = null)
                val on = reminder
                val mark = watermark
                if (on != null && mark != null) {
                    ExportSettingsScreen(
                        exportReminder = on,
                        onExportReminderChange = { next -> lifecycleScope.launch { store.setPendingExportReminder(next) } },
                        watermarkStyle = mark.style,
                        onWatermarkStyleChange = { next ->
                            lifecycleScope.launch { store.setBrandWatermarkStyle(next, mark.unlocked) }
                        },
                        watermarkUnlocked = mark.unlocked,
                        watermarkOff = mark.off,
                        onWatermarkOff = { lifecycleScope.launch { store.setBrandWatermarkOff() } },
                        onNavigateUp = ::finish,
                    )
                }
            }
            SettingsPage.APPEARANCE -> {
                val themeColor by store.themeColor.collectAsStateWithLifecycle(initialValue = null)
                val scanStyle by store.scanStyle.collectAsStateWithLifecycle(initialValue = null)
                val color = themeColor
                val style = scanStyle
                if (color != null && style != null) {
                    AppearanceSettingsScreen(
                        themeColor = color,
                        onThemeColorChange = { next -> lifecycleScope.launch { store.setThemeColor(next) } },
                        scanStyle = style,
                        onScanStyleChange = { next -> lifecycleScope.launch { store.setScanStyle(next) } },
                        onNavigateUp = ::finish,
                    )
                }
            }
            SettingsPage.DEVELOPER -> {
                val debugTrace by store.debugTrace.collectAsStateWithLifecycle(initialValue = null)
                val capacity by store.debugTraceCapacity.collectAsStateWithLifecycle(initialValue = null)
                val on = debugTrace
                val keep = capacity
                if (on != null && keep != null) {
                    DeveloperSettingsScreen(
                        debugTrace = on,
                        onDebugTraceChange = { next -> lifecycleScope.launch { store.setDebugTrace(next) } },
                        onNavigateUp = ::finish,
                        traceCapacity = keep,
                        onTraceCapacityChange = { next -> lifecycleScope.launch { store.setDebugTraceCapacity(next) } },
                        onOpenTraceHistory = { startActivity(intent(this, SettingsPage.DEBUG_TRACE)) },
                    )
                }
            }
            SettingsPage.LANGUAGE -> LanguageSettingsScreen(
                language = remember { AppLocale.current(this) },
                onLanguageChange = { next -> AppLocale.set(this, next) },
                onNavigateUp = ::finish,
            )
            SettingsPage.ABOUT -> {
                val debugTrace by store.debugTrace.collectAsStateWithLifecycle(initialValue = null)
                debugTrace?.let { on ->
                    AboutSettingsScreen(
                        debugTrace = on,
                        onDebugTraceChange = { next -> lifecycleScope.launch { store.setDebugTrace(next) } },
                        onReset = { lifecycleScope.launch { store.resetSettings() } },
                        onNavigateUp = ::finish,
                    )
                }
            }
            SettingsPage.DEBUG_TRACE -> {
                val log = sharedTraceLog(applicationContext)
                val runs by log.runs.collectAsStateWithLifecycle()
                DebugTraceHistoryScreen(runs = runs, onClear = log::clear, onNavigateUp = ::finish)
            }
        }
    }

    /** 识别方案的各页都直接读写 DataStore，不经过编辑器：编辑器的 ViewModel 自己在收这个 flow。 */
    @Composable
    private fun WithRecognitionConfig(
        store: SettingsStore,
        screen: @Composable (RecognitionConfig, (RecognitionConfig) -> Unit) -> Unit,
    ) {
        val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
        config?.let { current ->
            screen(current) { next -> lifecycleScope.launch { store.setRecognitionConfig(next) } }
        }
    }

    companion object {
        private const val EXTRA_PAGE = "moe.flinty.yomark.settings.PAGE"
        private const val EXTRA_RULE = "moe.flinty.yomark.settings.RULE"

        /** [ruleId] 只有 [SettingsPage.TEXT_RULE] 用：文字里的哪一类。 */
        fun intent(context: Context, page: SettingsPage, ruleId: String? = null): Intent =
            Intent(context, SettingsPageActivity::class.java)
                .putExtra(EXTRA_PAGE, page.name)
                .apply { if (ruleId != null) putExtra(EXTRA_RULE, ruleId) }
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

/** 导出页的品牌水印：选的字样、能不能去水印、现在是不是无水印。 */
private data class BrandWatermarkSetting(val style: BrandWatermarkStyle, val unlocked: Boolean, val off: Boolean)
