package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollToNode
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.engine.BarcodeOption
import moe.flinty.yomark.engine.FaceOption
import moe.flinty.yomark.engine.RecognitionConfig
import moe.flinty.yomark.engine.RuleState
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.settings.AboutSettingsScreen
import moe.flinty.yomark.ui.settings.AiSettingsScreen
import moe.flinty.yomark.ui.settings.AppearanceSettingsScreen
import moe.flinty.yomark.ui.settings.BarcodeSettingsScreen
import moe.flinty.yomark.ui.settings.DeveloperSettingsScreen
import moe.flinty.yomark.ui.settings.FEEDBACK_EMAIL
import moe.flinty.yomark.ui.settings.FaceSettingsScreen
import moe.flinty.yomark.ui.settings.GeneralSettingsScreen
import moe.flinty.yomark.ui.settings.LabsSettingsScreen
import moe.flinty.yomark.ui.settings.LanguageSettingsScreen
import moe.flinty.yomark.ui.settings.NEW_ISSUE_URL
import moe.flinty.yomark.ui.settings.PRIVACY_POLICY_URL
import moe.flinty.yomark.ui.settings.SettingsPage
import moe.flinty.yomark.ui.settings.SettingsScreen
import moe.flinty.yomark.ui.settings.TextEngineSettingsScreen
import moe.flinty.yomark.ui.settings.TextRuleSettingsScreen
import moe.flinty.yomark.ui.settings.TextSettingsScreen
import moe.flinty.yomark.ui.settings.mlKitSummary
import moe.flinty.yomark.ui.theme.ThemeColor
import moe.flinty.yomark.ui.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider

/**
 * 设置的一级页只是目录，选项都在二级页里；人脸、条码和文字规则一样能设成打码或关闭。
 * 各页改动即时发出新值（写 DataStore 由 Activity 管，这里只看发出了什么）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class SettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    /** 和 @Config 里的语言一致：Robolectric 按它选 values-zh。 */
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun show(screen: @Composable () -> Unit) {
        compose.setContent { MaterialTheme { Surface { screen() } } }
    }

    private fun home(
        config: RecognitionConfig = RecognitionConfig(),
        onOpen: (SettingsPage) -> Unit = {},
        developer: Boolean = false,
    ) = show { SettingsScreen(config, onOpen, onNavigateUp = {}, developer = developer) }

    // ---------- 一级页 ----------

    @Test fun everyTopicIsOnTheFirstPage() {
        home()
        listOf("文字", "人脸", "条码", "AI", "通用", "外观", "实验室", "语言", "帮助", "关于").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
    }

    /** 恢复默认设置挪进了「关于」，一级页上不再单独一行。 */
    @Test fun resetIsNoLongerOnTheFirstPage() {
        home()
        compose.onNodeWithText("恢复默认设置").assertDoesNotExist()
    }

    /** 「文字识别」并进了「文字」：认字和认出来怎么处理不再分在两处。 */
    @Test fun textRecognitionIsNoLongerATopicOfItsOwn() {
        home()
        compose.onNode(hasText("文字识别")).assertDoesNotExist()
    }

    /** 隐私权政策、恢复默认都挪进了「关于」，反馈也在里面：一级页上那一行写着版本号，也提一句这几样在里面。 */
    @Test fun theAboutRowSaysTheVersionAndWhatMovedIn() {
        home()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME} · 隐私权政策 · 反馈 · 恢复默认设置").assertIsDisplayed()
        compose.onNode(hasText("yomark.flinty.moe", substring = true)).assertDoesNotExist()
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
                face = FaceOption.ACCURATE,
                barcodeState = RuleState.OFF,
                ruleOverrides = mapOf("url" to RuleState.MASKED),
            ),
        )
        compose.onNodeWithText("打码 · 精确").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("编辑、识别、导出、水印样式").assertIsDisplayed()
        val masked = RuleCatalog.all.count { RuleCatalog.factoryState(it.id) == RuleState.MASKED } + 1
        val off = RuleCatalog.all.size - masked
        compose.onNodeWithText("打码 $masked 类 · 关闭 $off 类").assertIsDisplayed()
    }

    @Test fun tappingATopicOpensItsPage() {
        val opened = mutableListOf<SettingsPage>()
        home(onOpen = { opened += it })
        compose.onNodeWithText("人脸").performClick()
        compose.onNodeWithText("文字").performClick()
        compose.onNodeWithText("AI").performClick()
        compose.onNodeWithText("外观").performClick()
        compose.onNodeWithText("关于").performClick()
        assertThat(opened)
            .containsExactly(SettingsPage.FACE, SettingsPage.TEXT, SettingsPage.AI, SettingsPage.APPEARANCE, SettingsPage.ABOUT)
            .inOrder()
    }

    /** 「编辑」「导出」两页各只有一项，并成了「通用」：一级页上不再有这两行。 */
    @Test fun editAndExportAreFoldedIntoGeneral() {
        val opened = mutableListOf<SettingsPage>()
        home(onOpen = { opened += it })
        compose.onNodeWithText("编辑").assertDoesNotExist()
        compose.onNodeWithText("导出").assertDoesNotExist()
        compose.onNodeWithText("通用").performClick()
        assertThat(opened).containsExactly(SettingsPage.GENERAL)
    }

    /** 聊天记录挪进了实验室：一级页上不再单独一行，实验室那一行的小字写着它，和后来加的裁切状态栏。 */
    @Test fun chatScreenshotsLiveInLabs() {
        val opened = mutableListOf<SettingsPage>()
        home(onOpen = { opened += it })
        compose.onNodeWithText("识别聊天记录、裁切状态栏").assertIsDisplayed()
        compose.onNodeWithText("实验室").performClick()
        assertThat(opened).containsExactly(SettingsPage.LABS)
    }

    /** 实验室在开发者、语言上面。 */
    @Test fun labsSitsAboveDeveloperAndLanguage() {
        home(developer = true)
        val tops = listOf("实验室", "开发者", "语言").map {
            compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertThat(tops).isInOrder()
    }

    /** 开发者那一项平常用不着，连点版本号打开调试信息之后才出现。 */
    @Test fun theDeveloperTopicIsHiddenUntilDebugInfoIsOn() {
        home()
        compose.onNodeWithText("开发者").assertDoesNotExist()
    }

    @Test fun theDeveloperTopicOpensItsPage() {
        val opened = mutableListOf<SettingsPage>()
        home(developer = true, onOpen = { opened += it })
        compose.onNodeWithText("开发者").performClick()
        assertThat(opened).containsExactly(SettingsPage.DEVELOPER)
    }

    // ---------- 实验室 ----------

    /** 聊天记录出厂关着；打开就是打码，关上就是不认。 */
    @Test fun chatScreenshotsAreOffOutOfTheBoxAndCanBeTurnedOn() {
        var latest: RecognitionConfig? = null
        show { LabsSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("识别聊天记录").assertIsOff().performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(chatState = RuleState.MASKED))
    }

    /** 页脚只说遮什么，加一句局限。 */
    @Test fun theChatFooterSaysWhatItCoversAndThatItCanMiss() {
        show { LabsSettingsScreen(RecognitionConfig(), onChange = {}, onNavigateUp = {}) }
        compose.onNodeWithText("遮住聊天截图里的头像、昵称和聊天标题，不保证识别完全准确").assertIsDisplayed()
    }

    @Test fun chatScreenshotsCanBeTurnedOffAgain() {
        var latest: RecognitionConfig? = null
        show { LabsSettingsScreen(RecognitionConfig(chatState = RuleState.MASKED), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("识别聊天记录").assertIsOn().performClick()
        assertThat(latest).isEqualTo(RecognitionConfig())
    }

    /** 裁切状态栏出厂关着，开关只管它自己，聊天记录不受牵连。 */
    @Test fun croppingTheStatusBarIsOffOutOfTheBoxAndCanBeTurnedOnAndOff() {
        var latest: RecognitionConfig? = null
        show { LabsSettingsScreen(RecognitionConfig(chatState = RuleState.MASKED), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("裁切状态栏").assertIsOff().performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(chatState = RuleState.MASKED, cropStatusBar = true))
    }

    @Test fun croppingTheStatusBarCanBeTurnedOffAgain() {
        var latest: RecognitionConfig? = null
        show { LabsSettingsScreen(RecognitionConfig(cropStatusBar = true), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("裁切状态栏").assertIsOn().performClick()
        assertThat(latest).isEqualTo(RecognitionConfig())
    }

    /** 页脚说它做什么、怎么留着状态栏，加一句局限。 */
    @Test fun theCropFooterSaysWhatItDoesAndHowToKeepTheBar() {
        show { LabsSettingsScreen(RecognitionConfig(), onChange = {}, onNavigateUp = {}) }
        compose.onNodeWithText("导出时裁掉截图顶上的状态栏，编辑器里点压暗的那条可以留着，不保证识别完全准确").assertIsDisplayed()
    }

    // ---------- 语言 ----------

    /** 语言在关于上面，小字是眼下的界面语言；没选过就是跟随系统。 */
    @Test fun theLanguageRowSitsAboveAboutAndSaysTheCurrentLanguage() {
        val opened = mutableListOf<SettingsPage>()
        show {
            SettingsScreen(RecognitionConfig(), { opened += it }, onNavigateUp = {}, language = AppLanguage.ENGLISH)
        }
        compose.onNodeWithText("English").assertIsDisplayed()
        val language = compose.onNodeWithText("语言").fetchSemanticsNode().boundsInRoot
        val about = compose.onNodeWithText("关于").fetchSemanticsNode().boundsInRoot
        assertThat(language.bottom).isAtMost(about.top)
        compose.onNodeWithText("语言").performClick()
        assertThat(opened).containsExactly(SettingsPage.LANGUAGE)
    }

    // ---------- 帮助 ----------

    /** 帮助在语言和关于之间，点了重新放一遍引导，不进设置的哪一页。 */
    @Test fun helpReplaysTheTour() {
        val opened = mutableListOf<SettingsPage>()
        var replays = 0
        show { SettingsScreen(RecognitionConfig(), { opened += it }, onNavigateUp = {}, onReplayTour = { replays++ }) }
        compose.onNodeWithText("重新观看引导").assertIsDisplayed()
        val language = compose.onNodeWithText("语言").fetchSemanticsNode().boundsInRoot
        val help = compose.onNodeWithText("帮助").fetchSemanticsNode().boundsInRoot
        val about = compose.onNodeWithText("关于").fetchSemanticsNode().boundsInRoot
        assertThat(language.bottom).isAtMost(help.top)
        assertThat(help.bottom).isAtMost(about.top)
        compose.onNodeWithText("帮助").performClick()
        assertThat(replays).isEqualTo(1)
        assertThat(opened).isEmpty()
    }

    @Test fun theLanguageRowSaysSystemDefaultWhenNothingWasPicked() {
        home()
        compose.onNodeWithText("跟随系统").assertIsDisplayed()
    }

    /** 跟随系统排第一，其余用各自的语言写名字。 */
    @Test fun theLanguagePageListsSystemDefaultFirst() {
        show { LanguageSettingsScreen(AppLanguage.SYSTEM, onLanguageChange = {}, onNavigateUp = {}) }
        val tops = listOf("跟随系统", "简体中文", "English").map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertThat(tops).isInOrder()
        compose.onNodeWithText("跟随系统").assertIsSelected()
    }

    @Test fun pickingALanguageEmitsIt() {
        val picked = mutableListOf<AppLanguage>()
        show { LanguageSettingsScreen(AppLanguage.CHINESE_SIMPLIFIED, onLanguageChange = { picked += it }, onNavigateUp = {}) }
        compose.onNodeWithText("简体中文").performClick()
        compose.onNodeWithText("English").performClick()
        compose.onNodeWithText("跟随系统").performClick()
        assertThat(picked).containsExactly(AppLanguage.ENGLISH, AppLanguage.SYSTEM).inOrder()
    }

    // ---------- 人脸 ----------

    /** 2026-10-14 起只有打码、关闭两项，没有「仅圈出」。 */
    @Test fun facesAreEitherMaskedOrOff() {
        show { FaceSettingsScreen(RecognitionConfig(), onChange = {}, onNavigateUp = {}) }
        compose.onNodeWithText("打码").assertIsSelected()
        compose.onNodeWithText("关闭").assertIsNotSelected()
        compose.onNodeWithText("仅圈出").assertDoesNotExist()
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

    @Test fun barcodesCanBeTurnedOff() {
        var latest: RecognitionConfig? = null
        show { BarcodeSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("关闭").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(barcodeState = RuleState.OFF))
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
        onOpenRule: (String) -> Unit = {},
    ) = show { TextSettingsScreen(config, onChange, onOpenRule, onNavigateUp = {}) }

    /**
     * 每类一行，小字是它眼下怎么处理。识别引擎挪去了「AI」页，AI 增强识别没有开关了（总是开着），
     * 上面原先的「文字识别」一组都不在了。
     */
    @Test fun theTextPageListsEveryKind() {
        textPage()
        compose.onNodeWithText("识别引擎").assertDoesNotExist()
        compose.onNodeWithText("文字识别").assertDoesNotExist()
        compose.onNodeWithText("AI 增强识别").assertDoesNotExist()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("phone")
        compose.onNode(hasTestTag("rule-phone") and hasText("打码")).assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("url")
        compose.onNode(hasTestTag("rule-url") and hasText("关闭")).assertExists()
    }

    /** 设置页上常见的几类打头，规则表里的每一类都在、只出现一次。 */
    @Test fun theKindsAreListedCommonFirst() {
        assertThat(RuleCatalog.inSettingsOrder).containsExactlyElementsIn(RuleCatalog.all)
        assertThat(RuleCatalog.inSettingsOrder.take(4).map { it.id })
            .containsExactly("name", "phone", "address", "pickup").inOrder()
    }

    /** 人名那一行把没有旁证的那些怎么办也写出来：不写的话，聊天里的人名不打了，用户只看得到「打码」。 */
    @Test fun theNameRowSaysWhatHappensWithoutEvidence() {
        textPage()
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("name")
        compose.onNode(hasTestTag("rule-name") and hasText("打码 · 没有旁证的不打码")).assertExists()
    }

    @Test fun tappingAKindOpensItsPage() {
        var opened: String? = null
        textPage(onOpenRule = { opened = it })
        compose.onNode(hasScrollToKeyAction()).performScrollToKey("url")
        compose.onNodeWithTag("rule-url").performClick()
        assertThat(opened).isEqualTo("url")
    }

    /** 恢复默认管这一页和它下面的各页：各类、人名的开关。人脸、条码不动，挪去了「AI」页的识别引擎也不动。 */
    @Test fun resettingTheTextPageKeepsFacesBarcodesAndTheEngine() {
        var latest: RecognitionConfig? = null
        val config = RecognitionConfig(
            textEngine = TextEngineOption.MLKIT,
            faceState = RuleState.OFF,
            barcodeState = RuleState.OFF,
            maskUnanchoredNames = true,
        ).withRule("phone", RuleState.OFF, RuleCatalog.factoryState("phone"))
        textPage(config, onChange = { latest = it })
        compose.onNodeWithText("恢复默认").performClick()
        assertThat(latest).isEqualTo(
            RecognitionConfig(textEngine = TextEngineOption.MLKIT, faceState = RuleState.OFF, barcodeState = RuleState.OFF),
        )
    }

    /** 只改了识别引擎，「文字」页就是出厂的样子：恢复默认点不了。 */
    @Test fun theEngineAloneLeavesTheTextPageAtFactory() {
        textPage(RecognitionConfig(textEngine = TextEngineOption.MLKIT))
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    @Test fun resetIsDisabledWhenTheTextPageIsAtFactory() {
        textPage(RecognitionConfig(faceState = RuleState.OFF))
        compose.onNodeWithText("恢复默认").assertIsNotEnabled()
    }

    /** 只有 PP-OCR 和 ML Kit 两项：ML Kit 原先分英文、中文、中英三档，并成了一档，中英文都认。 */
    @Test fun pickingAnEngineEmitsIt() {
        var latest: RecognitionConfig? = null
        show { TextEngineSettingsScreen(RecognitionConfig(), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNode(hasText("PP-OCR") and hasText("识别更准，速度稍慢")).assertIsSelected()
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        listOf("ML Kit 英文", "ML Kit 中文", "ML Kit 中英").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNode(hasText("ML Kit") and hasText("中英文都能认")).performClick()
        assertThat(latest?.textEngine).isEqualTo(TextEngineOption.MLKIT)
    }

    // ---------- 文字里的一类 ----------

    private fun rulePage(id: String, config: RecognitionConfig = RecognitionConfig(), onChange: (RecognitionConfig) -> Unit = {}) =
        show { TextRuleSettingsScreen(id, config, onChange, onNavigateUp = {}) }

    /** 和人脸、条码同一个样子：识别到时怎么办，下面写着怎么认的。 */
    @Test fun aKindPageShowsHandlingAndHowItIsFound() {
        rulePage("url")
        compose.onNodeWithText("识别到网址时").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsSelected()
        compose.onNodeWithText(context.getString(RuleCatalog.description(RuleCatalog.all.first { it.id == "url" }))).assertIsDisplayed()
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
        val config = RecognitionConfig().withRule("url", RuleState.MASKED, RuleCatalog.factoryState("url"))
        rulePage("url", config, onChange = { latest = it })
        compose.onNodeWithText("关闭").performClick()
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
        compose.onNodeWithText("没有旁证的也打码").performClick()
        assertThat(latest?.maskUnanchoredNames).isTrue()
    }

    /** 人名选了「关闭」，这个开关管不到任何东西，灰掉。 */
    @Test fun theUnanchoredNamesSwitchIsDisabledWhileNamesAreOff() {
        rulePage("name", RecognitionConfig().withRule("name", RuleState.OFF, RuleCatalog.factoryState("name")))
        compose.onNodeWithTag("unanchored-names").assertIsNotEnabled()
    }

    // ---------- AI ----------

    private fun ai(
        nano: NanoStatus?,
        textEngine: TextEngineOption = RecognitionConfig().textEngine,
        onOpenEngine: () -> Unit = {},
    ) = show { AiSettingsScreen(nano, textEngine, onOpenEngine, onNavigateUp = {}) }

    /** 识别引擎从「文字」挪到了这里，在最上面：小字是眼下用的那个，点了进识别引擎页。 */
    @Test fun theAiPageSaysWhichEngineIsInUseAndOpensIt() {
        var opened = false
        ai(nano = null, textEngine = TextEngineOption.MLKIT, onOpenEngine = { opened = true })
        compose.onNode(hasTestTag("engine") and hasText("识别引擎") and hasText("ML Kit")).assertIsDisplayed()
        compose.onNodeWithTag("engine").performClick()
        assertThat(opened).isTrue()
    }

    /** 进页时先问 AICore，问完之前不能空着，也不能先说「没有」。 */
    @Test fun theAiPageSaysItIsStillChecking() {
        ai(nano = null)
        compose.onNodeWithText("正在检测…").assertIsDisplayed()
        compose.onNodeWithText("不可用").assertDoesNotExist()
    }

    @Test fun theAiPageSaysWhichNanoThisPhoneHas() {
        ai(NanoStatus.Present("nano-v3", NanoStatus.Model.READY))
        compose.onNodeWithText("Gemini Nano").assertIsDisplayed()
        compose.onNodeWithText("nano-v3 · 已就绪").assertIsDisplayed()
    }

    /** 卡片下面是一段话，不换行、不带句号：先说它是什么，接着指去看哪些机型有。 */
    @Test fun theAiPageSaysWhenThisPhoneHasNoNano() {
        ai(NanoStatus.Unavailable())
        compose.onNodeWithText("不可用").assertIsDisplayed()
        compose.onNodeWithText("Google 的端侧大模型，支持设备列表参考官方文档").assertIsDisplayed()
    }

    /** AICore 报了错就把错误码写出来，真机上排查时用得上。 */
    @Test fun theAiPageShowsTheAicoreErrorCode() {
        ai(NanoStatus.Unavailable(errorCode = 606))
        compose.onNodeWithText("不可用").assertIsDisplayed()
        compose.onNodeWithText("Google 的端侧大模型，支持设备列表参考官方文档，AICore 返回错误码 606").assertIsDisplayed()
    }

    /** 模型还没下到本机时也报版本：版本是机型定的，不是下载定的。 */
    @Test fun aNanoNotYetDownloadedStillShowsItsVersion() {
        ai(NanoStatus.Present("nano-v2", NanoStatus.Model.NOT_DOWNLOADED))
        compose.onNodeWithText("nano-v2 · 未下载").assertIsDisplayed()
        compose.onNodeWithText("Google 的端侧大模型，打开图片时由系统下载").assertIsDisplayed()
    }

    @Test fun aNanoWhoseVersionCannotBeReadSaysSo() {
        ai(NanoStatus.Present(null, NanoStatus.Model.DOWNLOADING))
        compose.onNodeWithText("版本未知 · 下载中").assertIsDisplayed()
    }

    /** 随包的 PP-OCR 不用问谁：版本是打包时定的，进页就写出来。 */
    @Test fun theAiPageShowsTheBundledPpOcrVersion() {
        ai(nano = null)
        compose.onNodeWithText("PP-OCRv6 small · 随安装包内置").assertIsDisplayed()
    }

    /** ML Kit 的几个模型并成一行，不是一个模型一项。 */
    @Test fun theAiPageListsMlKitModelsInOneRow() {
        ai(nano = null)
        compose.onNodeWithText("ML Kit").assertIsDisplayed()
        compose.onNodeWithText("文字 ", substring = true).assertIsDisplayed()
        compose.onNodeWithText("人脸 ", substring = true).assertIsDisplayed()
    }

    @Test fun mlKitTextVersionsAreWrittenOnceWhenTheyMatch() {
        val res = context.resources
        assertThat(mlKitSummary(res, "16.0.1", "16.0.1", "16.1.7", "17.3.0")).isEqualTo("文字 16.0.1 · 人脸 16.1.7 · 条码 17.3.0")
        assertThat(mlKitSummary(res, "16.0.1", "16.0.2", "16.1.7", "17.3.0"))
            .isEqualTo("文字 16.0.1（英文）/ 16.0.2（中文） · 人脸 16.1.7 · 条码 17.3.0")
    }

    // ---------- 关于 ----------

    private fun about(
        debugTrace: Boolean = false,
        onDebugTraceChange: (Boolean) -> Unit = {},
        onReset: () -> Unit = {},
        openUrl: (String) -> Unit = {},
        sendEmail: (Intent) -> Unit = {},
        notify: (String) -> Unit = {},
        play: Boolean = false,
    ) = show {
        AboutSettingsScreen(
            debugTrace = debugTrace,
            onDebugTraceChange = onDebugTraceChange,
            onReset = onReset,
            onNavigateUp = {},
            version = "0.11.1 (19)",
            play = play,
            device = pixel,
            openUrl = openUrl,
            sendEmail = sendEmail,
            notify = notify,
        )
    }

    private val pixel = "Google Pixel 10 Pro · Android 17 (API 37)"

    @Test fun theAboutPageShowsTheVersion() {
        about()
        compose.onNodeWithText("版本").assertIsDisplayed()
        compose.onNodeWithText("0.11.1 (19)").assertIsDisplayed()
    }

    /** 上架 Play 的内购版在版本号后面写明，和 GitHub 上发的开源版分得开。 */
    @Test fun thePlayEditionSaysSoAfterTheVersion() {
        about(play = true)
        compose.onNodeWithText("0.11.1 (19) · Play 版").assertIsDisplayed()
    }

    /** Play 要求应用内能打开隐私权政策：交给浏览器，网址同时写在摘要里（带 https://，末尾不带斜杠）。 */
    @Test fun privacyPolicyOpensInTheBrowser() {
        var opened: String? = null
        about(openUrl = { opened = it })
        compose.onNodeWithText("https://yomark.flinty.moe/privacy").assertIsDisplayed()
        compose.onNodeWithText("隐私权政策").performClick()
        assertThat(opened).isEqualTo(PRIVACY_POLICY_URL)
    }

    /** 没有浏览器能接也不能闪退。 */
    @Test fun privacyPolicySurvivesHavingNoBrowser() {
        about(openUrl = { throw IllegalStateException("no browser") })
        compose.onNodeWithText("隐私权政策").performClick()
        compose.onNodeWithText("隐私权政策").assertIsDisplayed()
    }

    /** 反馈一组：发邮件那一行写着地址，点了交给邮件应用，主题填好，正文最后是版本（带 Play 版）和机型。 */
    @Test fun feedbackEmailGoesToTheMailAppWithVersionAndDevice() {
        var sent: Intent? = null
        about(play = true, sendEmail = { sent = it })
        compose.onNodeWithText("反馈").assertIsDisplayed()
        compose.onNodeWithText(FEEDBACK_EMAIL).assertIsDisplayed()
        compose.onNodeWithText("发邮件").performClick()
        val mail = sent!!
        assertThat(mail.dataString).startsWith("mailto:$FEEDBACK_EMAIL?")
        assertThat(mail.getStringExtra(Intent.EXTRA_SUBJECT)).isEqualTo("有码反馈")
        assertThat(mail.getStringExtra(Intent.EXTRA_TEXT)).endsWith("\n有码 0.11.1 (19) · Play 版\n$pixel")
    }

    /** 手机上没有邮件应用：地址进剪贴板，说一句。 */
    @Test fun feedbackEmailWithoutAMailAppCopiesTheAddress() {
        val notes = mutableListOf<String>()
        about(sendEmail = { throw ActivityNotFoundException() }, notify = { notes += it })
        compose.onNodeWithText("发邮件").performClick()
        assertThat(notes).containsExactly("没有邮件应用，已复制邮箱地址")
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertThat(clip.getItemAt(0).text.toString()).isEqualTo(FEEDBACK_EMAIL)
    }

    /** GitHub Issues 交给浏览器，先到选模板那页，版本和机型带过去预填；小字说清是公开的。 */
    @Test fun githubIssuesOpenWithVersionAndDeviceFilledIn() {
        var opened: String? = null
        about(openUrl = { opened = it })
        compose.onNodeWithText("公开，需要 GitHub 账号").assertIsDisplayed()
        compose.onNodeWithText("GitHub Issues").performClick()
        val url = Uri.parse(opened)
        assertThat(url.buildUpon().clearQuery().build().toString()).isEqualTo(NEW_ISSUE_URL)
        assertThat(url.getQueryParameter("version")).isEqualTo("0.11.1 (19)")
        assertThat(url.getQueryParameter("device")).isEqualTo(pixel)
    }

    @Test fun githubIssuesSurviveHavingNoBrowser() {
        about(openUrl = { throw IllegalStateException("no browser") })
        compose.onNodeWithText("GitHub Issues").performClick()
        compose.onNodeWithText("GitHub Issues").assertIsDisplayed()
    }

    /** 恢复默认在「关于」里也是普通的一行，误点一下不能就把设置全清了。 */
    @Test fun resetAsksFirst() {
        var resets = 0
        about(onReset = { resets++ })
        compose.onNodeWithText("恢复默认设置").performClick()
        assertThat(resets).isEqualTo(0)
        compose.onNodeWithText("恢复").performClick()
        assertThat(resets).isEqualTo(1)
    }

    @Test fun resetCanBeCancelled() {
        var resets = 0
        about(onReset = { resets++ })
        compose.onNodeWithText("恢复默认设置").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("恢复").assertDoesNotExist()
        assertThat(resets).isEqualTo(0)
    }

    /** 和系统设置里打开开发者选项一样：连点七下版本号，第三下起提示还差几下，打开后说一句去哪找。 */
    @Test fun tappingTheVersionSevenTimesTurnsOnDebugInfo() {
        val changes = mutableListOf<Boolean>()
        val notes = mutableListOf<String>()
        about(onDebugTraceChange = { changes += it }, notify = { notes += it })
        repeat(6) { compose.onNodeWithText("版本").performClick() }
        assertThat(changes).isEmpty()
        assertThat(notes).containsExactly(
            "再点 4 次即可打开调试信息",
            "再点 3 次即可打开调试信息",
            "再点 2 次即可打开调试信息",
            "再点 1 次即可打开调试信息",
        ).inOrder()
        compose.onNodeWithText("版本").performClick()
        assertThat(changes).containsExactly(true)
        assertThat(notes.last()).isEqualTo("已打开调试信息，设置里多了「开发者」一项")
    }

    /** 调试的设置都挪去了「开发者」，打开了「关于」里也不多出东西。 */
    @Test fun theAboutPageHoldsNoDebugSettings() {
        about(debugTrace = true)
        listOf("调试信息", "调试记录", "保留最近几次识别").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
    }

    /** 已经打开了，再点版本号只说一句开关在哪，不再数。 */
    @Test fun tappingTheVersionWhenAlreadyOnJustSaysSo() {
        val changes = mutableListOf<Boolean>()
        val notes = mutableListOf<String>()
        about(debugTrace = true, onDebugTraceChange = { changes += it }, notify = { notes += it })
        repeat(7) { compose.onNodeWithText("版本").performClick() }
        assertThat(changes).isEmpty()
        assertThat(notes.distinct()).containsExactly("调试信息已经打开，在设置的「开发者」里")
    }

    // ---------- 开发者 ----------

    private fun developer(
        debugTrace: Boolean = true,
        onDebugTraceChange: (Boolean) -> Unit = {},
        traceCapacity: Int = 20,
        onTraceCapacityChange: (Int) -> Unit = {},
        onOpenTraceHistory: () -> Unit = {},
    ) = show {
        DeveloperSettingsScreen(
            debugTrace = debugTrace,
            onDebugTraceChange = onDebugTraceChange,
            onNavigateUp = {},
            traceCapacity = traceCapacity,
            onTraceCapacityChange = onTraceCapacityChange,
            onOpenTraceHistory = onOpenTraceHistory,
        )
    }

    @Test fun theDebugSwitchTurnsItOffAgain() {
        var latest: Boolean? = null
        developer(onDebugTraceChange = { latest = it })
        compose.onNodeWithText("调试信息").performClick()
        assertThat(latest).isFalse()
    }

    /** 关掉之后这一页不自己退出，只剩开关，下面几组收起来，说一句怎么再打开。 */
    @Test fun turnedOffOnlyTheSwitchIsLeft() {
        var latest: Boolean? = null
        developer(debugTrace = false, onDebugTraceChange = { latest = it })
        compose.onNodeWithText("调试记录").assertDoesNotExist()
        compose.onNodeWithText("保留最近几次识别").assertDoesNotExist()
        compose.onNodeWithText("在「关于」里连点版本号七下", substring = true).assertIsDisplayed()
        compose.onNodeWithText("调试信息").performClick()
        assertThat(latest).isTrue()
    }

    @Test fun theDeveloperPagePicksHowManyRunsToKeep() {
        val picked = mutableListOf<Int>()
        developer(traceCapacity = 20, onTraceCapacityChange = { picked += it })
        compose.onNodeWithText("最近 20 次识别").assertExists()
        compose.onNode(hasScrollToKeyAction()).performScrollToNode(hasText("50 次"))
        compose.onNodeWithText("50 次").performClick()
        assertThat(picked).containsExactly(50)
    }

    @Test fun theDeveloperPageOpensTheHistory() {
        var opened = false
        developer(onOpenTraceHistory = { opened = true })
        compose.onNodeWithText("调试记录").performClick()
        assertThat(opened).isTrue()
    }

    // ---------- 通用、外观 ----------

    private fun general(
        restyleAll: Boolean = true,
        onRestyleAllChange: (Boolean) -> Unit = {},
        fullResolution: Boolean = false,
        onFullResolutionChange: (Boolean) -> Unit = {},
        overwriteByDefault: Boolean = false,
        onOverwriteByDefaultChange: (Boolean) -> Unit = {},
        shareInExportMenu: Boolean = true,
        onShareInExportMenuChange: (Boolean) -> Unit = {},
        watermarkStyle: BrandWatermarkStyle = BrandWatermarkStyle.ICON_ZH_EN,
        onWatermarkStyleChange: (BrandWatermarkStyle) -> Unit = {},
        watermarkUnlocked: Boolean = false,
        watermarkOff: Boolean = false,
        onWatermarkOff: () -> Unit = {},
    ) = show {
        GeneralSettingsScreen(
            restyleAll = restyleAll, onRestyleAllChange = onRestyleAllChange,
            fullResolution = fullResolution, onFullResolutionChange = onFullResolutionChange,
            overwriteByDefault = overwriteByDefault, onOverwriteByDefaultChange = onOverwriteByDefaultChange,
            shareInExportMenu = shareInExportMenu, onShareInExportMenuChange = onShareInExportMenuChange,
            watermarkStyle = watermarkStyle, onWatermarkStyleChange = onWatermarkStyleChange,
            watermarkUnlocked = watermarkUnlocked, watermarkOff = watermarkOff, onWatermarkOff = onWatermarkOff,
            onNavigateUp = {},
        )
    }

    /** 原先「编辑」「导出」两页的项，一项一组；导出前提醒和「圈出」一起去掉了。 */
    @Test fun theGeneralPageHoldsWhatEditAndExportHad() {
        general()
        val tops = listOf(
            "编辑", "换样式时一起换掉已有的打码", "识别", "按原分辨率识别",
            "默认导出", "另存到相册", "覆盖原图", "分享", "放在导出菜单里", "单独一个按钮", "水印样式", "图标 + 有码 Yomark",
        ).map {
            compose.onNodeWithText(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top
        }
        assertThat(tops).isInOrder()
        compose.onNodeWithText("导出前提醒").assertDoesNotExist()
    }

    @Test fun theFullResolutionSwitchTurnsItOn() {
        var latest: Boolean? = null
        general(fullResolution = false, onFullResolutionChange = { latest = it })
        compose.onNodeWithText("按原分辨率识别").performClick()
        assertThat(latest).isTrue()
    }

    /** 默认导出出厂是另存；选了覆盖原图，顶栏的导出就是覆盖，另存在它右边的小箭头里。两项都不带小字。 */
    @Test fun theDefaultExportCanBeOverwritingTheOriginal() {
        var latest: Boolean? = null
        general(overwriteByDefault = false, onOverwriteByDefaultChange = { latest = it })
        compose.onNodeWithText("另存到相册").assertIsSelected()
        compose.onNodeWithText("写不回原图", substring = true).assertDoesNotExist()
        compose.onNodeWithText("覆盖原图").performClick()
        assertThat(latest).isTrue()
    }

    /** 分享出厂在导出右边的小箭头里；选「单独一个按钮」，它回到顶栏上导出左边。 */
    @Test fun shareCanGoBackToItsOwnButton() {
        var latest: Boolean? = null
        general(shareInExportMenu = true, onShareInExportMenuChange = { latest = it })
        compose.onNodeWithText("放在导出菜单里").assertIsSelected()
        compose.onNodeWithText("单独一个按钮").performClick()
        assertThat(latest).isFalse()
    }

    @Test fun theRestyleAllSwitchTurnsItOff() {
        var latest: Boolean? = null
        general(restyleAll = true, onRestyleAllChange = { latest = it })
        compose.onNodeWithText("换样式时一起换掉已有的打码").performClick()
        assertThat(latest).isFalse()
    }

    @Test fun pickingTheWatermarkWithoutChineseEmitsIt() {
        var latest: BrandWatermarkStyle? = null
        general(onWatermarkStyleChange = { latest = it })
        compose.onNodeWithText("图标 + 有码 Yomark").assertIsSelected()
        compose.onNodeWithText("图标 + Yomark").performClick()
        assertThat(latest).isEqualTo(BrandWatermarkStyle.ICON_EN)
    }

    @Test fun noWatermarkIsOfferedOnlyOnceUnlocked() {
        general(watermarkUnlocked = false, watermarkOff = true)
        compose.onNodeWithText("无水印").assertDoesNotExist()
        // 没解锁时「无水印」不算数，照样选中一个字样
        compose.onNodeWithText("图标 + 有码 Yomark").assertIsSelected()
    }

    @Test fun unlockedAndOffSelectsNoWatermarkAndPickingAStyleEmitsIt() {
        var latest: BrandWatermarkStyle? = null
        general(onWatermarkStyleChange = { latest = it }, watermarkUnlocked = true, watermarkOff = true)
        compose.onNodeWithText("无水印").assertIsSelected()
        compose.onNodeWithText("图标 + 有码 Yomark").assertIsNotSelected()
        compose.onNodeWithText("图标 + 有码 Yomark").performClick()
        assertThat(latest).isEqualTo(BrandWatermarkStyle.ICON_ZH_EN)
    }

    @Test fun pickingNoWatermarkEmitsIt() {
        var off = false
        general(watermarkStyle = BrandWatermarkStyle.ICON_EN, watermarkUnlocked = true, onWatermarkOff = { off = true })
        compose.onNodeWithText("图标 + Yomark").assertIsSelected()
        // 多了分享那一组，「无水印」在一屏以下
        compose.onNode(hasScrollToKeyAction()).performScrollToNode(hasText("无水印"))
        compose.onNodeWithText("无水印").performClick()
        assertThat(off).isTrue()
    }

    @Test fun pickingTheSweepScanEffectEmitsIt() {
        var latest: ScanStyle? = null
        show {
            AppearanceSettingsScreen(
                themeMode = ThemeMode.SYSTEM, onThemeModeChange = {},
                themeColor = ThemeColor.SYSTEM, onThemeColorChange = {},
                scanStyle = ScanStyle.FROST, onScanStyleChange = { latest = it },
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("扫光").performClick()
        assertThat(latest).isEqualTo(ScanStyle.SWEEP)
    }
}
