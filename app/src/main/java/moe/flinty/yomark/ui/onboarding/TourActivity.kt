package moe.flinty.yomark.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import moe.flinty.yomark.core.image.SourceImage
import moe.flinty.yomark.data.SettingsStore
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.ui.EditorActivity
import moe.flinty.yomark.ui.YomarkActivity
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.enableLightEdgeToEdge
import moe.flinty.yomark.ui.theme.YomarkTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置里「帮助」重新放一遍首次引导。压在设置上面单独一个 Activity，不回首页：
 * 设置也可能是从编辑器进来的，回首页就把编辑器里的图丢了。
 * 不测速，也没有「识别引擎」那一页：用了一阵的人自己知道快慢，要换在「AI」里换。
 *
 * 走完的去处跟着变：跳过、打开设置都是 finish 回到设置；选择图片照常进编辑器（Picker 里取消也回设置）。
 */
class TourActivity : YomarkActivity() {

    private val settings by lazy { SettingsStore(applicationContext) }

    private val picker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) startActivity(EditorActivity.intent(this, uris))
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableLightEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            // 样图解好、设置读到之前什么都不画，引导的头一帧就有图（同首页）
            val tour by produceState<Tour?>(null) {
                value = Tour(
                    sample = withContext(Dispatchers.Default) { TourSample.load(resources) },
                    boxes = TourSample.of(resources),
                    scanStyle = settings.scanStyle.first(),
                    chatOn = settings.recognitionConfig.first().chatState != RuleState.OFF,
                )
            }
            val config by settings.recognitionConfig.collectAsStateWithLifecycle(initialValue = null as RecognitionConfig?)

            YomarkTheme(settings) {
                Surface {
                    val current = tour ?: return@Surface
                    OnboardingTour(
                        sample = current.sample,
                        boxes = current.boxes,
                        scanStyle = current.scanStyle,
                        chatOn = config?.let { it.chatState != RuleState.OFF } ?: current.chatOn,
                        onChatChange = { on -> lifecycleScope.launch { settings.setTourChat(on) } },
                        onSkip = ::finish,
                        onPickImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onOpenSettings = ::finish,
                    )
                }
            }
        }
    }

    private class Tour(val sample: SourceImage, val boxes: TourSample, val scanStyle: ScanStyle, val chatOn: Boolean)

    companion object {
        fun intent(context: Context): Intent = Intent(context, TourActivity::class.java)
    }
}

/** 引导最后一页的「识别聊天记录」：和实验室页上的同一个开关，写法也一样（见 SettingsActivity）。 */
internal suspend fun SettingsStore.setTourChat(on: Boolean) {
    val config = recognitionConfig.first()
    setRecognitionConfig(config.copy(chatState = if (on) RuleState.MASKED else RuleState.OFF))
}

/** 首次引导「识别引擎」那一页选的：和「AI › 识别引擎」里选是同一项。没变就不写。 */
internal suspend fun SettingsStore.setTourEngine(engine: TextEngineOption) {
    val config = recognitionConfig.first()
    if (config.textEngine != engine) setRecognitionConfig(config.copy(textEngine = engine))
}
