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
 * 设置的二级页。六页共用这一个 Activity，打开哪一页由 [SettingsPage] 参数决定；
 * 每次打开都是一个新实例，预见式返回照样是跨 Activity 的动画。
 */
class SettingsPageActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        val page = intent.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } }
        if (page == null) {
            finish()
            return
        }
        val store = SettingsStore(applicationContext)
        setSettingsContent(store) { SettingsPageContent(page, store) }
    }

    @Composable
    private fun SettingsPageContent(page: SettingsPage, store: SettingsStore) {
        when (page) {
            SettingsPage.TEXT_RULES -> WithRecognitionConfig(store) { config, onChange ->
                TextRulesScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.FACE -> WithRecognitionConfig(store) { config, onChange ->
                FaceSettingsScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.BARCODE -> WithRecognitionConfig(store) { config, onChange ->
                BarcodeSettingsScreen(config, onChange, onNavigateUp = ::finish)
            }
            SettingsPage.TEXT_RECOGNITION -> WithRecognitionConfig(store) { config, onChange ->
                TextRecognitionSettingsScreen(config, onChange, onNavigateUp = ::finish)
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

    /** 识别方案的四页都直接读写 DataStore，不经过编辑器：编辑器的 ViewModel 自己在收这个 flow。 */
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

        fun intent(context: Context, page: SettingsPage): Intent =
            Intent(context, SettingsPageActivity::class.java).putExtra(EXTRA_PAGE, page.name)
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
