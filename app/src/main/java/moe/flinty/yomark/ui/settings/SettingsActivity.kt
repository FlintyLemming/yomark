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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.enableLightEdgeToEdge
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.launch

/**
 * 设置（一级页）。首页和编辑器的右上角都进得来，所以「向上」就是 finish 回到来的那一页，
 * 不声明固定的 parentActivityName。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setSettingsContent(store) {
            val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
            val reminder by store.pendingExportReminder.collectAsStateWithLifecycle(initialValue = null)
            val current = config
            val on = reminder
            if (current != null && on != null) {
                SettingsScreen(
                    config = current,
                    exportReminder = on,
                    onOpen = { page -> startActivity(SettingsPageActivity.intent(this, page)) },
                    onReset = { lifecycleScope.launch { store.resetSettings() } },
                    onNavigateUp = ::finish,
                )
            }
        }
    }
}

/**
 * 设置一级页以下的各页。共用这一个 Activity，打开哪一页由 [SettingsPage] 参数决定，
 * 文字里的一类另带规则 id；每次打开都是一个新实例，预见式返回照样是跨 Activity 的动画。
 */
class SettingsPageActivity : ComponentActivity() {

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
            SettingsPage.EXPORT -> {
                val reminder by store.pendingExportReminder.collectAsStateWithLifecycle(initialValue = null)
                reminder?.let { on ->
                    ExportSettingsScreen(
                        exportReminder = on,
                        onExportReminderChange = { next -> lifecycleScope.launch { store.setPendingExportReminder(next) } },
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
