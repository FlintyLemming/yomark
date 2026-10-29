package moe.flinty.yomark.data

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.theme.ThemeColor
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
            textEngine = TextEngineOption.MLKIT,
            barcode = BarcodeOption.LOOSE,
            barcodeState = RuleState.OFF,
            face = FaceOption.ACCURATE,
            faceState = RuleState.OFF,
            chatState = RuleState.MASKED,
            cropStatusBar = true,
            maskUnanchoredNames = true,
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

    /** ML Kit 的英文、中文、中英三档 2026-10-16 并成了一档：选过哪一档的，升级后还是 ML Kit，别的设置不受牵连。 */
    @Test fun `an engine stored before ml kit was merged reads as ml kit`() = runTest {
        listOf("LATIN", "CHINESE", "BOTH").forEach { old ->
            val store = store()
            store.setRecognitionConfig(RecognitionConfig(faceState = RuleState.OFF))
            store.writeRawTextEngineForTest(old)
            val read = store.recognitionConfig.first()
            assertThat(read.textEngine).isEqualTo(TextEngineOption.MLKIT)
            assertThat(read.faceState).isEqualTo(RuleState.OFF)
        }
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
        store.setRecognitionConfig(RecognitionConfig(faceState = RuleState.OFF))
        assertThat(store.recognitionConfig.first().faceState).isEqualTo(RuleState.OFF)
    }

    /**
     * 「仅圈出」2026-10-14 去掉了。存着它的老设置读成关闭——仅圈出本来就不打码，
     * 不能落回出厂的打码：选过仅圈出的用户，升级后不该突然被打码。
     */
    @Test fun `a stored outline-only reads as off, not as the factory mask`() = runTest {
        val store = store()
        store.writeRawHandlingForTest(face = "OUTLINED", chat = "OUTLINED", rules = "card:OUTLINED;url:MASKED")
        val read = store.recognitionConfig.first()
        assertThat(read.faceState).isEqualTo(RuleState.OFF)
        assertThat(read.chatState).isEqualTo(RuleState.OFF)
        assertThat(read.ruleOverrides).containsExactly("card", RuleState.OFF, "url", RuleState.MASKED)
    }

    /**
     * 聊天记录 2026-10-15 起出厂关着、挪进实验室。setRecognitionConfig 每次整份写入，老安装的旧键里多半存着
     * 当时出厂的打码，不是用户自己选的；换了键，升级后一律关着，要用再去实验室打开。
     */
    @Test fun `a chat state stored before it moved to labs is ignored`() = runTest {
        val store = store()
        store.writeLegacyChatStateForTest("MASKED")
        assertThat(store.recognitionConfig.first().chatState).isEqualTo(RuleState.OFF)
    }

    // ---------- 设置页的「恢复默认设置」 ----------

    @Test fun `reset puts recognition back to factory`() = runTest {
        val store = store()
        store.setRecognitionConfig(
            RecognitionConfig(
                textEngine = TextEngineOption.MLKIT,
                faceState = RuleState.OFF,
                barcodeState = RuleState.OFF,
                chatState = RuleState.MASKED,
                cropStatusBar = true,
                maskUnanchoredNames = true,
                ruleOverrides = mapOf("ip" to RuleState.MASKED),
            ),
        )

        store.resetSettings()

        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }

    /** 外观不归「恢复默认设置」管。 */
    @Test fun `reset leaves the appearance alone`() = runTest {
        val store = store()
        store.setThemeColor(ThemeColor.GREEN)
        store.setScanStyle(ScanStyle.SWEEP)

        store.resetSettings()

        assertThat(store.themeColor.first()).isEqualTo(ThemeColor.GREEN)
        assertThat(store.scanStyle.first()).isEqualTo(ScanStyle.SWEEP)
    }

    /** 老安装存着 OFF 时点恢复默认，人脸要回到打码，不能被老值再迁成「关」。 */
    @Test fun `reset also clears a legacy OFF`() = runTest {
        val store = store()
        store.writeLegacyDetectorModesForTest(face = "OFF", barcode = "OFF")

        store.resetSettings()

        assertThat(store.recognitionConfig.first()).isEqualTo(RecognitionConfig())
    }
}
