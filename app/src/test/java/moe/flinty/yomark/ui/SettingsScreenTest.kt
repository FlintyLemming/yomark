package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import moe.flinty.yomark.ui.settings.TextEngineSettingsScreen
import moe.flinty.yomark.ui.settings.TextRuleSettingsScreen
import moe.flinty.yomark.ui.settings.TextSettingsScreen
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
        listOf("文字", "人脸", "条码", "导出", "外观", "隐私权政策", "恢复默认设置").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    /** 「文字识别」并进了「文字」：认字和认出来怎么处理不再分在两处。 */
    @Test fun textRecognitionIsNoLongerATopicOfItsOwn() {
        home()
        compose.onNode(hasText("文字识别")).assertDoesNotExist()
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
        compose.onNodeWithText("导出前提醒已关闭").assertIsDisplayed()
        val masked = RuleCatalog.all.count { RuleCatalog.factoryState(it.id) == RuleState.MASKED }
        val outlined = RuleCatalog.all.size - masked - 1
        compose.onNodeWithText("打码 $masked 类 · 圈出 $outlined 类 · 关闭 1 类 · PP-OCR").assertIsDisplayed()
    }

    @Test fun tappingATopicOpensItsPage() {
        val opened = mutableListOf<SettingsPage>()
        home(onOpen = { opened += it })
        compose.onNodeWithText("人脸").performClick()
        compose.onNodeWithText("文字").performClick()
        compose.onNodeWithText("外观").performClick()
        assertThat(opened).containsExactly(SettingsPage.FACE, SettingsPage.TEXT, SettingsPage.APPEARANCE).inOrder()
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

    // ---------- 文字 ----------

    private fun textPage(
        config: RecognitionConfig = RecognitionConfig(),
        onChange: (RecognitionConfig) -> Unit = {},
        onOpenEngine: () -> Unit = {},
        onOpenRule: (String) -> Unit = {},
    ) = show { TextSettingsScreen(config, onChange, onOpenEngine, onOpenRule, onNavigateUp = {}) }

    /** 认字的两项在上面，下面是各类；每类一行，小字是它眼下怎么处理。 */
    @Test fun theTextPageReadsTextAndListsEveryKind() {
        textPage()
        compose.onNodeWithText("识别引擎").assertIsDisplayed()
        compose.onNodeWithText("AI 复查").assertIsDisplayed()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("phone")
        compose.onNode(hasTestTag("rule-phone") and hasText("打码")).assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("url")
        compose.onNode(hasTestTag("rule-url") and hasText("仅圈出")).assertExists()
    }

    /** 设置页上常见的几类打头，规则表里的每一类都在、只出现一次。 */
    @Test fun theKindsAreListedCommonFirst() {
        assertThat(RuleCatalog.inSettingsOrder).containsExactlyElementsIn(RuleCatalog.all)
        assertThat(RuleCatalog.inSettingsOrder.take(4).map { it.id })
            .containsExactly("name", "phone", "address", "pickup").inOrder()
    }

    /** 人名那一行把没有旁证的那些怎么办也写出来：不写的话，聊天里的人名不圈了，用户只看得到「打码」。 */
    @Test fun theNameRowSaysWhatHappensWithoutEvidence() {
        textPage()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("name")
        compose.onNode(hasTestTag("rule-name") and hasText("打码 · 没有旁证的不圈")).assertExists()
    }

    @Test fun tappingTheEngineOpensItsPage() {
        var opened = false
        textPage(onOpenEngine = { opened = true })
        compose.onNodeWithText("识别引擎").performClick()
        assertThat(opened).isTrue()
    }

    @Test fun tappingAKindOpensItsPage() {
        var opened: String? = null
        textPage(onOpenRule = { opened = it })
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("url")
        compose.onNodeWithTag("rule-url").performClick()
        assertThat(opened).isEqualTo("url")
    }

    @Test fun theAiReviewSwitchTurnsItOff() {
        var latest: RecognitionConfig? = null
        textPage(onChange = { latest = it })
        compose.onNodeWithText("AI 复查").performClick()
        assertThat(latest?.semantic).isEqualTo(SemanticOption.OFF)
    }

    /** 恢复默认管这一页和它下面的各页：引擎、AI 复查、各类、人名的开关。人脸、条码不动。 */
    @Test fun resettingTheTextPageKeepsFacesAndBarcodes() {
        var latest: RecognitionConfig? = null
        val config = RecognitionConfig(
            textEngine = TextEngineOption.LATIN,
            semantic = SemanticOption.OFF,
            faceState = RuleState.OUTLINED,
            barcodeState = RuleState.OFF,
            outlineUnanchoredNames = true,
        ).withRule("phone", RuleState.OFF, RuleCatalog.factoryState("phone"))
        textPage(config, onChange = { latest = it })
        compose.onNodeWithText("恢复默认").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(faceState = RuleState.OUTLINED, barcodeState = RuleState.OFF))
    }

    @Test fun resetIsDisabledWhenTheTextPageIsAtFactory() {
        textPage(RecognitionConfig(faceState = RuleState.OFF))
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test fun pickingAnEngineEmitsIt() {
        var latest: RecognitionConfig? = null
        show { TextEngineSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("PP-OCR").assertIsSelected()
        compose.onNodeWithText("ML Kit 英文").performClick()
        assertThat(latest?.textEngine).isEqualTo(TextEngineOption.LATIN)
    }

    // ---------- 文字里的一类 ----------

    private fun rulePage(id: String, config: RecognitionConfig = RecognitionConfig(), onChange: (RecognitionConfig) -> Unit = {}) =
        show { TextRuleSettingsScreen(id, config, onChange, onNavigateUp = {}) }

    /** 和人脸、条码同一个样子：识别到时怎么办，下面写着怎么认的。 */
    @Test fun aKindPageShowsHandlingAndHowItIsFound() {
        rulePage("url")
        compose.onNodeWithText("识别到网址时").assertIsDisplayed()
        compose.onNodeWithText("仅圈出").assertIsSelected()
        compose.onNodeWithText(RuleCatalog.description(RuleCatalog.all.first { it.id == "url" })).assertIsDisplayed()
    }

    @Test fun pickingAHandlingOnAKindPageEmitsIt() {
        var latest: RecognitionConfig? = null
        rulePage("url", onChange = { latest = it })
        compose.onNodeWithText("打码").performClick()
        assertThat(latest?.ruleOverrides).containsExactly("url", RuleState.MASKED)
    }

    /** 改回出厂值不留覆盖。 */
    @Test fun pickingTheFactoryHandlingClearsTheOverride() {
        var latest: RecognitionConfig? = null
        val config = RecognitionConfig().withRule("url", RuleState.OFF, RuleCatalog.factoryState("url"))
        rulePage("url", config, onChange = { latest = it })
        compose.onNodeWithText("仅圈出").performClick()
        assertThat(latest?.ruleOverrides).isEmpty()
    }

    /** 没有旁证的人名那个开关只在人名的页上。 */
    @Test fun onlyTheNamePageHasTheEvidenceSwitch() {
        rulePage("phone")
        compose.onNodeWithTag("unanchored-names").assertDoesNotExist()
    }

    @Test fun theUnanchoredNamesSwitchTurnsItOn() {
        var latest: RecognitionConfig? = null
        rulePage("name", onChange = { latest = it })
        compose.onNodeWithText("没有旁证的也圈出").performClick()
        assertThat(latest?.outlineUnanchoredNames).isTrue()
    }

    /** 人名选了「关闭」，这个开关管不到任何东西，灰掉。 */
    @Test fun theUnanchoredNamesSwitchIsDisabledWhileNamesAreOff() {
        rulePage("name", RecognitionConfig().withRule("name", RuleState.OFF, RuleCatalog.factoryState("name")))
        compose.onNodeWithTag("unanchored-names").assertIsNotEnabled()
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
