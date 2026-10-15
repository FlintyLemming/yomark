package moe.flinty.yomark.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.genai.ExternalModelSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 实验室里的外部模型：存在识别方案之外，改它不动识别方案；恢复默认只关开关、不清接口。 */
@RunWith(RobolectricTestRunner::class)
class ExternalModelStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun `it starts switched off and empty`() = runTest {
        assertThat(isolatedSettingsStore(context).externalModel.first()).isEqualTo(ExternalModelSettings())
    }

    @Test fun `every field survives a write and read, trimmed`() = runTest {
        val store = isolatedSettingsStore(context)
        store.updateExternalModel { ExternalModelSettings(true, " https://api.example.com/v1 ", " sk-1 ", " glm ") }
        assertThat(store.externalModel.first())
            .isEqualTo(ExternalModelSettings(true, "https://api.example.com/v1", "sk-1", "glm"))
    }

    /** 弹框只改一项：别的几项原样留着。 */
    @Test fun `changing one field keeps the others`() = runTest {
        val store = isolatedSettingsStore(context)
        store.updateExternalModel { it.copy(baseUrl = "https://api.example.com/v1", model = "glm") }
        store.updateExternalModel { it.copy(enabled = true) }
        assertThat(store.externalModel.first())
            .isEqualTo(ExternalModelSettings(true, "https://api.example.com/v1", "", "glm"))
    }

    /** 不在识别方案里：改它编辑器不重跑识别。 */
    @Test fun `it does not touch the recognition config`() = runTest {
        val store = isolatedSettingsStore(context)
        store.updateExternalModel { it.copy(enabled = true, model = "glm") }
        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }

    @Test fun `resetting settings switches it off but keeps what was typed`() = runTest {
        val store = isolatedSettingsStore(context)
        store.updateExternalModel { ExternalModelSettings(true, "https://api.example.com/v1", "sk-1", "glm") }
        store.resetSettings()
        assertThat(store.externalModel.first())
            .isEqualTo(ExternalModelSettings(false, "https://api.example.com/v1", "sk-1", "glm"))
    }
}
