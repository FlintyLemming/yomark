package com.youma.app.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.youma.app.engine.BarcodeOption
import com.youma.app.engine.FaceOption
import com.youma.app.engine.RecognitionConfig
import com.youma.app.engine.RuleState
import com.youma.app.engine.SemanticOption
import com.youma.app.engine.TextEngineOption
import com.youma.app.ui.canvas.ScanStyle
import com.youma.app.ui.theme.ThemeColor
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
            barcode = BarcodeOption.LOOSE,
            barcodeState = RuleState.OUTLINED,
            face = FaceOption.ACCURATE,
            faceState = RuleState.OFF,
            semantic = SemanticOption.OFF,
            ruleOverrides = mapOf("url" to RuleState.MASKED, "longnum" to RuleState.OFF),
        )
        store.setRecognitionConfig(cfg)
        assertThat(store.recognitionConfig.first()).isEqualTo(cfg)
    }

    /** 枚举名是持久化格式的一部分：删掉一个选项不该让老安装打不开。 */
    @Test fun `an unknown stored enum falls back per axis, not wholesale`() = runTest {
        val store = store()
        store.setRecognitionConfig(RecognitionConfig(barcodeState = RuleState.OFF))
        store.writeRawTextEngineForTest("NOT_AN_ENGINE")
        val read = store.recognitionConfig.first()
        assertThat(read.textEngine).isEqualTo(RecognitionConfig().textEngine)   // 该项退回默认
        assertThat(read.barcodeState).isEqualTo(RuleState.OFF)                  // 其余项不受牵连
    }

    @Test fun `resetting goes back to the factory default`() = runTest {
        val store = store()
        store.setRecognitionConfig(RecognitionConfig(faceState = RuleState.OFF, ruleOverrides = mapOf("ip" to RuleState.OFF)))
        store.setRecognitionConfig(RecognitionConfig())
        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }

    // ---------- 人脸、条码的「关」从识别模式挪到处理方式（2026-10-07） ----------

    /** 关掉过人脸的老用户，升级后不能突然又被打码。 */
    @Test fun `a legacy OFF face mode reads as faces turned off`() = runTest {
        val store = store()
        store.writeLegacyDetectorModesForTest(face = "OFF", barcode = null)
        val read = store.recognitionConfig.first()
        assertThat(read.faceState).isEqualTo(RuleState.OFF)
        assertThat(read.face).isEqualTo(RecognitionConfig().face)
        assertThat(read.barcodeState).isEqualTo(RuleState.MASKED)
    }

    @Test fun `a legacy OFF barcode mode reads as barcodes turned off`() = runTest {
        val store = store()
        store.writeLegacyDetectorModesForTest(face = "ACCURATE", barcode = "OFF")
        val read = store.recognitionConfig.first()
        assertThat(read.barcodeState).isEqualTo(RuleState.OFF)
        assertThat(read.barcode).isEqualTo(RecognitionConfig().barcode)
        assertThat(read.face).isEqualTo(FaceOption.ACCURATE)
        assertThat(read.faceState).isEqualTo(RuleState.MASKED)
    }

    /** 迁移只看还没存过处理方式的老数据：存过之后以处理方式为准。 */
    @Test fun `a stored handling state wins over the legacy mode`() = runTest {
        val store = store()
        store.setRecognitionConfig(RecognitionConfig(faceState = RuleState.OUTLINED))
        assertThat(store.recognitionConfig.first().faceState).isEqualTo(RuleState.OUTLINED)
    }

    // ---------- 设置页的「恢复默认设置」 ----------

    @Test fun `reset puts recognition and the export reminder back to factory`() = runTest {
        val store = store()
        store.setRecognitionConfig(
            RecognitionConfig(
                textEngine = TextEngineOption.LATIN,
                faceState = RuleState.OUTLINED,
                barcodeState = RuleState.OFF,
                ruleOverrides = mapOf("ip" to RuleState.OFF),
            ),
        )
        store.setPendingExportReminder(false)

        store.resetSettings()

        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
        assertThat(store.pendingExportReminder.first()).isTrue()
    }

    /** 外观不归「恢复默认设置」管。 */
    @Test fun `reset leaves the appearance alone`() = runTest {
        val store = store()
        store.setThemeColor(ThemeColor.GREEN)
        store.setScanStyle(ScanStyle.FROST)

        store.resetSettings()

        assertThat(store.themeColor.first()).isEqualTo(ThemeColor.GREEN)
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.FROST)
    }

    /** 老安装存着 OFF 时点恢复默认，人脸要回到打码，不能被老值再迁成「关」。 */
    @Test fun `reset also clears a legacy OFF`() = runTest {
        val store = store()
        store.writeLegacyDetectorModesForTest(face = "OFF", barcode = "OFF")

        store.resetSettings()

        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }
}
