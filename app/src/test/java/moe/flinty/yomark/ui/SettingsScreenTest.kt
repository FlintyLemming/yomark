package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.SemanticOption
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.settings.AppearanceSettingsScreen
import moe.flinty.yomark.ui.settings.BarcodeSettingsScreen
import moe.flinty.yomark.ui.settings.ExportSettingsScreen
import moe.flinty.yomark.ui.settings.FaceSettingsScreen
import moe.flinty.yomark.ui.settings.PRIVACY_POLICY_URL
import moe.flinty.yomark.ui.settings.SettingsPage
import moe.flinty.yomark.ui.settings.SettingsScreen
import moe.flinty.yomark.ui.settings.TextRecognitionSettingsScreen
import moe.flinty.yomark.ui.settings.TextRulesScreen
import moe.flinty.yomark.ui.theme.ThemeColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 设置的一级页只是目录，选项都在二级页里；人脸、条码和文字规则一样能设成打码、仅圈出或关闭。
 * 各页改动即时发出新值（写 DataStore 由 Activity 管，这里只看发出了什么）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private fun show(screen: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { Surface { screen() } } }
    }

    private fun home(
        config: RecognitionConfig = RecognitionConfig(),
        exportReminder: Boolean = true,
        onOpen: (SettingsPage) -> Unit = {},
        onReset: () -> Unit = {},
        openUrl: (String) -> Unit = {},
    ) = show { SettingsScreen(config, exportReminder, onOpen, onReset, onNavigateUp = {}, openUrl = openUrl) }

    // ---------- 一级页 ----------

    @Test fun everyTopicIsOnTheFirstPage() {
        home()
        listOf("文字", "人脸", "条码", "文字识别", "导出", "外观", "隐私权政策", "恢复默认设置").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    /** Play 要求应用内能打开隐私权政策：交给浏览器，网址同时写在摘要里。 */
    @Test fun privacyPolicyOpensInTheBrowser() {
        var opened: String? = null
        home(openUrl = { opened = it })
        compose.onNodeWithText("yomark.flinty.moe/privacy/").assertIsDisplayed()
        compose.onNodeWithText("隐私权政策").performClick()
        assertThat(opened).isEqualTo(PRIVACY_POLICY_URL)
    }

    /** 没有浏览器能接也不能闪退。 */
    @Test fun privacyPolicySurvivesHavingNoBrowser() {
        home(openUrl = { throw IllegalStateException("no browser") })
        compose.onNodeWithText("隐私权政策").performClick()
        compose.onNodeWithText("隐私权政策").assertIsDisplayed()
    }

    /** 一级页不直接摆选项：原先铺在这里的识别模式、引擎都收进了二级页。 */
    @Test fun theFirstPageHoldsNoOptionsOfItsOwn() {
        home()
        listOf("快速", "精确", "严格", "宽松", "PP-OCR", "扫光", "磨砂").forEach {
            compose.onNode(hasText(it)).assertDoesNotExist()
        }
    }

    @Test fun eachTopicSaysWhereItStands() {
        home(
            config = RecognitionConfig(
                faceState = RuleState.OUTLINED,
                face = FaceOption.ACCURATE,
                barcodeState = RuleState.OFF,
                semantic = SemanticOption.OFF,
                ruleOverrides = mapOf("ip" to RuleState.OFF),
            ),
            exportReminder = false,
        )
        compose.onNodeWithText("仅圈出 · 精确识别").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("PP-OCR · AI 复查关闭").assertIsDisplayed()
        compose.onNodeWithText("导出前提醒已关闭").assertIsDisplayed()
        val masked = RuleCatalog.all.count { RuleCatalog.factoryState(it.id) == RuleState.MASKED }
        val outlined = RuleCatalog.all.size - masked - 1
        compose.onNodeWithText("打码 $masked 类 · 圈出 $outlined 类 · 关闭 1 类").assertIsDisplayed()
    }

    @Test fun tappingATopicOpensItsPage() {
        val opened = mutableListOf<SettingsPage>()
        home(onOpen = { opened += it })
        compose.onNodeWithText("人脸").performClick()
        compose.onNodeWithText("文字").performClick()
        compose.onNodeWithText("外观").performClick()
        assertThat(opened).containsExactly(SettingsPage.FACE, SettingsPage.TEXT_RULES, SettingsPage.APPEARANCE).inOrder()
    }

    /** 恢复默认在一级页上就是普通的一行，误点一下不能就把设置全清了。 */
    @Test fun resetAsksFirst() {
        var resets = 0
        home(onReset = { resets++ })
        compose.onNodeWithText("恢复默认设置").performClick()
        assertThat(resets).isEqualTo(0)
        compose.onNodeWithText("恢复").performClick()
        assertThat(resets).isEqualTo(1)
    }

    @Test fun resetCanBeCancelled() {
        var resets = 0
        home(onReset = { resets++ })
        compose.onNodeWithText("恢复默认设置").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("恢复").assertDoesNotExist()
        assertThat(resets).isEqualTo(0)
    }

    // ---------- 人脸 ----------

    @Test fun facesCanBeSetToOutlineOnly() {
        var latest: RecognitionConfig? = null
        show { FaceSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("打码").assertIsSelected()
        compose.onNodeWithText("仅圈出").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(faceState = RuleState.OUTLINED))
    }

    @Test fun facesCanBeTurnedOff() {
        var latest: RecognitionConfig? = null
        show { FaceSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("关闭").performClick()
        assertThat(latest?.faceState).isEqualTo(RuleState.OFF)
    }

    @Test fun pickingAFaceModeEmitsIt() {
        var latest: RecognitionConfig? = null
        show { FaceSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("精确").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(face = FaceOption.ACCURATE))
    }

    /** 人脸关掉了，模式选了也没用：灰掉、点不动，但选中的那一项还看得出来。 */
    @Test fun theFaceModeIsDisabledWhileFacesAreOff() {
        var latest: RecognitionConfig? = null
        show { FaceSettingsScreen(RecognitionConfig(faceState = RuleState.OFF), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("精确").assertIsNotEnabled().performClick()
        compose.onNodeWithText("快速").assertIsSelected()
        assertThat(latest).isNull()
    }

    // ---------- 条码 ----------

    @Test fun barcodesCanBeSetToOutlineOnly() {
        var latest: RecognitionConfig? = null
        show { BarcodeSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("仅圈出").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(barcodeState = RuleState.OUTLINED))
    }

    @Test fun pickingABarcodeStrategyEmitsIt() {
        var latest: RecognitionConfig? = null
        show { BarcodeSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("宽松").performClick()
        assertThat(latest?.barcode).isEqualTo(BarcodeOption.LOOSE)
    }

    // ---------- 文字识别 ----------

    @Test fun pickingAnEngineEmitsIt() {
        var latest: RecognitionConfig? = null
        show { TextRecognitionSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("PP-OCR").assertIsSelected()
        compose.onNodeWithText("ML Kit 英文").performClick()
        assertThat(latest?.textEngine).isEqualTo(TextEngineOption.LATIN)
    }

    @Test fun theAiReviewSwitchTurnsItOff() {
        var latest: RecognitionConfig? = null
        show { TextRecognitionSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("使用 AI 复查").performClick()
        assertThat(latest?.semantic).isEqualTo(SemanticOption.OFF)
    }

    // ---------- 文字（规则） ----------

    @Test fun aRuleSelectorEmitsTheNewState() {
        var latest: RecognitionConfig? = null
        show { TextRulesScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("url")
        compose.onNode(hasText("打码") and hasAnyAncestor(hasTestTag("rule-url"))).performClick()
        assertThat(latest?.ruleOverrides).containsExactly("url", RuleState.MASKED)
    }

    @Test fun resettingRulesKeepsTheAxes() {
        var latest: RecognitionConfig? = null
        val config = RecognitionConfig(textEngine = TextEngineOption.LATIN, faceState = RuleState.OUTLINED)
            .withRule("phone", RuleState.OFF, RuleCatalog.factoryState("phone"))
        show { TextRulesScreen(config, onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("恢复默认").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(textEngine = TextEngineOption.LATIN, faceState = RuleState.OUTLINED))
    }

    // ---------- 导出、外观 ----------

    @Test fun theExportReminderSwitchTurnsItOff() {
        var latest: Boolean? = null
        show { ExportSettingsScreen(exportReminder = true, onExportReminderChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("导出前提醒").performClick()
        assertThat(latest).isFalse()
    }

    @Test fun pickingTheFrostedScanEffectEmitsIt() {
        var latest: ScanStyle? = null
        show {
            AppearanceSettingsScreen(
                themeColor = ThemeColor.SYSTEM, onThemeColorChange = {},
                scanStyle = ScanStyle.SWEEP, onScanStyleChange = { latest = it },
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("磨砂").performClick()
        assertThat(latest).isEqualTo(ScanStyle.FROST)
    }
}
