package com.youma.app.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.FaceOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.RuleState
import com.youma.app.engine.SemanticOption
import com.youma.app.engine.TextEngineOption
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecognitionConfigStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private fun store() = isolatedSettingsStore(context)

    @Test fun `default config is the factory default`() = runTest {
        assertThat(store().recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }

    @Test fun `every axis survives a write and read`() = runTest {
        val store = store()
        val cfg = RecognitionConfig(
            textEngine = TextEngineOption.CHINESE,
            barcode = BarcodeOption.STRICT,
            face = FaceOption.OFF,
            semantic = SemanticOption.OFF,
            ruleOverrides = mapOf("url" to RuleState.MASKED, "longnum" to RuleState.OFF),
        )
        store.setRecognitionConfig(cfg)
        assertThat(store.recognitionConfig.first()).isEqualTo(cfg)
    }

    /** 枚举名是持久化格式的一部分：删掉一个选项不该让老安装打不开。 */
    @Test fun `an unknown stored enum falls back per axis, not wholesale`() = runTest {
        val store = store()
        store.setRecognitionConfig(RecognitionConfig(barcode = BarcodeOption.OFF))
        store.writeRawTextEngineForTest("NOT_AN_ENGINE")
        val read = store.recognitionConfig.first()
        assertThat(read.textEngine).isEqualTo(RecognitionConfig().textEngine)   // 该项退回默认
        assertThat(read.barcode).isEqualTo(BarcodeOption.OFF)                   // 其余项不受牵连
    }

    @Test fun `resetting goes back to the factory default`() = runTest {
        val store = store()
        store.setRecognitionConfig(RecognitionConfig(face = FaceOption.OFF, ruleOverrides = mapOf("ip" to RuleState.OFF)))
        store.setRecognitionConfig(RecognitionConfig())
        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }
}
