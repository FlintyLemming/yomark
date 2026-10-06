package com.youma.app.ui.settings

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.youma.app.data.SettingsStore
import com.youma.app.engine.RecognitionConfig
import com.youma.app.ui.enableLightEdgeToEdge
import com.youma.app.ui.theme.YoumaTheme
import kotlinx.coroutines.launch

/**
 * 设置（一级页）：识别设置与主题色。首页和编辑器的右上角都进得来，所以「向上」就是 finish 回到来的那一页，
 * 不声明固定的 parentActivityName。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setRecognitionConfigContent(store) { config, onChange ->
            val scanStyle by store.scanStyle.collectAsStateWithLifecycle(initialValue = null)
            val themeColor by store.themeColor.collectAsStateWithLifecycle(initialValue = null)
            val style = scanStyle
            val color = themeColor
            if (style != null && color != null) {
                RecognitionSettingsScreen(
                    config = config,
                    onChange = onChange,
                    scanStyle = style,
                    onScanStyleChange = { next -> lifecycleScope.launch { store.setScanStyle(next) } },
                    themeColor = color,
                    onThemeColorChange = { next -> lifecycleScope.launch { store.setThemeColor(next) } },
                    onOpenRules = { startActivity(Intent(this, RuleSettingsActivity::class.java)) },
                    onNavigateUp = ::finish,
                )
            }
        }
    }
}

/** 设置的二级页：导出前提醒与逐条规则。 */
class RuleSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = SettingsStore(applicationContext)
        setRecognitionConfigContent(store) { config, onChange ->
            val reminder by store.pendingExportReminder.collectAsStateWithLifecycle(initialValue = null)
            reminder?.let { on ->
                RuleSettingsScreen(
                    config = config,
                    onChange = onChange,
                    exportReminder = on,
                    onExportReminderChange = { next -> lifecycleScope.launch { store.setPendingExportReminder(next) } },
                    onNavigateUp = ::finish,
                )
            }
        }
    }
}

/**
 * 两页都直接读写 DataStore，不经过编辑器：编辑器的 ViewModel 自己在收这个 flow，
 * 从设置页返回时它已经按新方案重跑过了。
 *
 * 还没读到（null）的那一下只画空底色。DataStore 在进程里读过一次就有缓存，实际看不出来。
 */
private fun ComponentActivity.setRecognitionConfigContent(
    store: SettingsStore,
    screen: @Composable (RecognitionConfig, (RecognitionConfig) -> Unit) -> Unit,
) {
    setContent {
        val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
        YoumaTheme(store) {
            Surface {
                config?.let { current ->
                    screen(current) { next -> lifecycleScope.launch { store.setRecognitionConfig(next) } }
                }
            }
        }
    }
}
