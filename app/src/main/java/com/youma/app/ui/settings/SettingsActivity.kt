package com.youma.app.ui.settings

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
import com.youma.app.data.SettingsStore
import com.youma.app.engine.RecognitionConfig
import com.youma.app.ui.enableLightEdgeToEdge
import kotlinx.coroutines.launch

/**
 * 识别设置（一级页）。首页和编辑器的右上角都进得来，所以「向上」就是 finish 回到来的那一页，
 * 不声明固定的 parentActivityName。
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        setRecognitionConfigContent { config, onChange ->
            RecognitionSettingsScreen(
                config = config,
                onChange = onChange,
                onOpenRules = { startActivity(Intent(this, RuleSettingsActivity::class.java)) },
                onNavigateUp = ::finish,
            )
        }
    }
}

/** 识别设置的二级页：逐条规则。 */
class RuleSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)
        setRecognitionConfigContent { config, onChange ->
            RuleSettingsScreen(config = config, onChange = onChange, onNavigateUp = ::finish)
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
    screen: @Composable (RecognitionConfig, (RecognitionConfig) -> Unit) -> Unit,
) {
    val store = SettingsStore(applicationContext)
    setContent {
        val config by store.recognitionConfig.collectAsStateWithLifecycle(initialValue = null)
        MaterialTheme {
            Surface {
                config?.let { current ->
                    screen(current) { next -> lifecycleScope.launch { store.setRecognitionConfig(next) } }
                }
            }
        }
    }
}
