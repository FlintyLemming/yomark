package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasScrollToKeyAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import moe.flinty.yomark.engine.SemanticOption
import moe.flinty.yomark.engine.TextEngineOption
import moe.flinty.yomark.BuildConfig
import moe.flinty.yomark.engine.genai.ExternalModelException
import moe.flinty.yomark.engine.genai.ExternalModelSettings
import moe.flinty.yomark.engine.genai.NanoStatus
import moe.flinty.yomark.export.BrandWatermarkStyle
import moe.flinty.yomark.rules.RuleCatalog
import moe.flinty.yomark.ui.canvas.ScanStyle
import moe.flinty.yomark.ui.settings.AboutSettingsScreen
import moe.flinty.yomark.ui.settings.AiSettingsScreen
import moe.flinty.yomark.ui.settings.AppearanceSettingsScreen
import moe.flinty.yomark.ui.settings.BarcodeSettingsScreen
import moe.flinty.yomark.ui.settings.DeveloperSettingsScreen
import moe.flinty.yomark.ui.settings.EditSettingsScreen
import moe.flinty.yomark.ui.settings.ExternalField
import moe.flinty.yomark.ui.settings.ExportSettingsScreen
import moe.flinty.yomark.ui.settings.FaceSettingsScreen
import moe.flinty.yomark.ui.settings.LabsSettingsScreen
import moe.flinty.yomark.ui.settings.LanguageSettingsScreen
import moe.flinty.yomark.ui.settings.PRIVACY_POLICY_URL
import moe.flinty.yomark.ui.settings.SettingsPage
import moe.flinty.yomark.ui.settings.SettingsScreen
import moe.flinty.yomark.ui.settings.TextEngineSettingsScreen
import moe.flinty.yomark.ui.settings.TextRuleSettingsScreen
import moe.flinty.yomark.ui.settings.TextSettingsScreen
import moe.flinty.yomark.ui.settings.mlKitSummary
import moe.flinty.yomark.ui.theme.ThemeColor
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.content.Context
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
        listOf("文字", "人脸", "条码", "AI", "导出", "外观", "实验室", "语言", "关于").forEach {
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

    /** 隐私权政策、恢复默认都挪进了「关于」：一级页上那一行写着版本号，也提一句这两样在里面。 */
    @Test fun theAboutRowSaysTheVersionAndWhatMovedIn() {
        home()
        compose.onNodeWithText("版本 ${BuildConfig.VERSION_NAME} · 隐私权政策 · 恢复默认设置").assertIsDisplayed()
        compose.onNode(hasText("yomark.flinty.moe/privacy/")).assertDoesNotExist()
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
                semantic = SemanticOption.OFF,
                ruleOverrides = mapOf("url" to RuleState.MASKED),
            ),
        )
        compose.onNodeWithText("打码 · 精确").assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed()
        compose.onNodeWithText("水印样式").assertIsDisplayed()
        val masked = RuleCatalog.all.count { RuleCatalog.factoryState(it.id) == RuleState.MASKED } + 1
        val off = RuleCatalog.all.size - masked
        compose.onNodeWithText("打码 $masked 类 · 关闭 $off 类 · PP-OCR").assertIsDisplayed()
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

    /** 聊天记录挪进了实验室：一级页上不再单独一行，实验室那一行的小字写着它（和外部模型）。 */
    @Test fun chatScreenshotsLiveInLabs() {
        val opened = mutableListOf<SettingsPage>()
        home(onOpen = { opened += it })
        compose.onNodeWithText("聊天记录、外部模型").assertIsDisplayed()
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
        compose.onNodeWithText("聊天记录").assertIsOff().performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(chatState = RuleState.MASKED))
    }

    @Test fun chatScreenshotsCanBeTurnedOffAgain() {
        var latest: RecognitionConfig? = null
        show { LabsSettingsScreen(RecognitionConfig(chatState = RuleState.MASKED), onChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("聊天记录").assertIsOn().performClick()
        assertThat(latest).isEqualTo(RecognitionConfig())
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
        onOpenEngine: () -> Unit = {},
        onOpenRule: (String) -> Unit = {},
    ) = show { TextSettingsScreen(config, onChange, onOpenEngine, onOpenRule, onNavigateUp = {}) }

    /** 认字的两项在上面，下面是各类；每类一行，小字是它眼下怎么处理。 */
    @Test fun theTextPageReadsTextAndListsEveryKind() {
        textPage()
        compose.onNodeWithText("识别引擎").assertIsDisplayed()
        compose.onNodeWithText("AI 增强识别").assertIsDisplayed()
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
        compose.onNodeWithText("AI 增强识别").performClick()
        assertThat(latest?.semantic).isEqualTo(SemanticOption.OFF)
    }

    /** 恢复默认管这一页和它下面的各页：引擎、AI 增强识别、各类、人名的开关。人脸、条码不动。 */
    @Test fun resettingTheTextPageKeepsFacesAndBarcodes() {
        var latest: RecognitionConfig? = null
        val config = RecognitionConfig(
            textEngine = TextEngineOption.LATIN,
            semantic = SemanticOption.OFF,
            faceState = RuleState.OFF,
            barcodeState = RuleState.OFF,
            maskUnanchoredNames = true,
        ).withRule("phone", RuleState.OFF, RuleCatalog.factoryState("phone"))
        textPage(config, onChange = { latest = it })
        compose.onNodeWithText("恢复默认").performClick()
        assertThat(latest).isEqualTo(RecognitionConfig(faceState = RuleState.OFF, barcodeState = RuleState.OFF))
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

    // ---------- 外部模型（实验室） ----------

    private val filled = ExternalModelSettings(enabled = true, baseUrl = "https://api.example.com/v1", apiKey = "sk-secret", model = "glm")

    /** 出厂关着，开关在实验室里。 */
    @Test fun theExternalModelIsOffInLabsAndCanBeTurnedOn() {
        var on: Boolean? = null
        show {
            LabsSettingsScreen(RecognitionConfig(), onChange = {}, onNavigateUp = {}, externalModel = false, onExternalModelChange = { on = it })
        }
        compose.onNodeWithTag("labs-external-model").assertIsOff().performClick()
        assertThat(on).isTrue()
    }

    /** 实验室里没打开，AI 页上就没有这一组。 */
    @Test fun theAiPageHasNoExternalGroupUntilLabsTurnsItOn() {
        ai(nano = null)
        compose.onNodeWithText("外部模型").assertDoesNotExist()
    }

    /** 填了什么一看就知道，key 只说填没填；没填完不能测。 */
    @Test fun theExternalGroupSaysWhatIsFilledInWithoutShowingTheKey() {
        show { AiSettingsScreen(null, onNavigateUp = {}, external = filled.copy(model = "")) }
        compose.onNodeWithText("外部模型").assertIsDisplayed()
        compose.onNodeWithText("https://api.example.com/v1").assertIsDisplayed()
        compose.onNodeWithText("已填写").assertIsDisplayed()
        compose.onNodeWithText("sk-secret", substring = true).assertDoesNotExist()
        compose.onNodeWithText("未填写").assertIsDisplayed()
        compose.onNodeWithTag("external-test").assertIsNotEnabled()
    }

    /**
     * 弹框保存时只换掉这一项，别的几项用存着的那份。
     * 弹框本身不在这里点开：Robolectric 下弹框里的输入框会一直重新排版、耗尽内存，弹框在模拟器上验。
     */
    @Test fun savingAFieldReplacesOnlyThatField() {
        assertThat(ExternalField.MODEL.of(filled)).isEqualTo("glm")
        assertThat(ExternalField.URL.set(filled, "https://other.example.com/v1"))
            .isEqualTo(filled.copy(baseUrl = "https://other.example.com/v1"))
        assertThat(ExternalField.KEY.set(ExternalModelSettings(), "sk-2")).isEqualTo(ExternalModelSettings(apiKey = "sk-2"))
    }

    @Test fun anHttpAddressIsTurnedDownBeforeTesting() {
        show { AiSettingsScreen(null, onNavigateUp = {}, external = filled.copy(baseUrl = "http://192.168.1.2/v1")) }
        compose.onNodeWithText("接口地址要以 https:// 开头").assertIsDisplayed()
        compose.onNodeWithTag("external-test").assertIsNotEnabled()
    }

    @Test fun testingTheConnectionSaysHowLongItTook() {
        show { AiSettingsScreen(null, onNavigateUp = {}, external = filled, onTestExternal = { 1400L }) }
        compose.onNodeWithTag("external-test").performClick()
        compose.onNodeWithText("可用 · 1.4 秒").assertIsDisplayed()
    }

    /** 401、404 带着接口自己说的那句：一看就知道是 key 还是地址填错了。 */
    @Test fun aFailedTestSaysWhy() {
        show {
            AiSettingsScreen(null, onNavigateUp = {}, external = filled, onTestExternal = {
                throw ExternalModelException(ExternalModelException.Reason.HTTP, 401, "Invalid API key")
            })
        }
        compose.onNodeWithTag("external-test").performClick()
        compose.onNodeWithText("失败：HTTP 401 · Invalid API key").assertIsDisplayed()
    }

    // ---------- AI ----------

    private fun ai(nano: NanoStatus?) = show { AiSettingsScreen(nano, onNavigateUp = {}) }

    /** 进页时先问 AICore，问完之前不能空着，也不能先说「没有」。 */
    @Test fun theAiPageSaysItIsStillChecking() {
        ai(nano = null)
        compose.onNodeWithText("正在检测…").assertIsDisplayed()
        compose.onNodeWithText("本机没有").assertDoesNotExist()
    }

    @Test fun theAiPageSaysWhichNanoThisPhoneHas() {
        ai(NanoStatus.Present("nano-v3", NanoStatus.Model.READY))
        compose.onNodeWithText("Gemini Nano").assertIsDisplayed()
        compose.onNodeWithText("nano-v3 · 已就绪").assertIsDisplayed()
    }

    @Test fun theAiPageSaysWhenThisPhoneHasNoNano() {
        ai(NanoStatus.Unavailable())
        compose.onNodeWithText("本机没有").assertIsDisplayed()
        compose.onNodeWithText("官方文档", substring = true).assertIsDisplayed()
    }

    /** AICore 报了错就把错误码写出来，真机上排查时用得上。 */
    @Test fun theAiPageShowsTheAicoreErrorCode() {
        ai(NanoStatus.Unavailable(errorCode = 606))
        compose.onNodeWithText("本机没有").assertIsDisplayed()
        compose.onNodeWithText("错误码 606", substring = true).assertIsDisplayed()
    }

    /** 模型还没下到本机时也报版本：版本是机型定的，不是下载定的。 */
    @Test fun aNanoNotYetDownloadedStillShowsItsVersion() {
        ai(NanoStatus.Present("nano-v2", NanoStatus.Model.NOT_DOWNLOADED))
        compose.onNodeWithText("nano-v2 · 未下载").assertIsDisplayed()
    }

    @Test fun aNanoWhoseVersionCannotBeReadSaysSo() {
        ai(NanoStatus.Present(null, NanoStatus.Model.DOWNLOADING))
        compose.onNodeWithText("版本未知 · 下载中").assertIsDisplayed()
    }

    /** 随包的 PP-OCR 不用问谁：版本是打包时定的，进页就写出来。 */
    @Test fun theAiPageShowsTheBundledPpOcrVersion() {
        ai(nano = null)
        compose.onNodeWithText("PP-OCR").assertIsDisplayed()
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
        notify: (String) -> Unit = {},
    ) = show {
        AboutSettingsScreen(
            debugTrace = debugTrace,
            onDebugTraceChange = onDebugTraceChange,
            onReset = onReset,
            onNavigateUp = {},
            version = "0.11.1 (19)",
            openUrl = openUrl,
            notify = notify,
        )
    }

    @Test fun theAboutPageShowsTheVersion() {
        about()
        compose.onNodeWithText("版本").assertIsDisplayed()
        compose.onNodeWithText("0.11.1 (19)").assertIsDisplayed()
    }

    /** Play 要求应用内能打开隐私权政策：交给浏览器，网址同时写在摘要里。 */
    @Test fun privacyPolicyOpensInTheBrowser() {
        var opened: String? = null
        about(openUrl = { opened = it })
        compose.onNodeWithText("yomark.flinty.moe/privacy/").assertIsDisplayed()
        compose.onNodeWithText("隐私权政策").performClick()
        assertThat(opened).isEqualTo(PRIVACY_POLICY_URL)
    }

    /** 没有浏览器能接也不能闪退。 */
    @Test fun privacyPolicySurvivesHavingNoBrowser() {
        about(openUrl = { throw IllegalStateException("no browser") })
        compose.onNodeWithText("隐私权政策").performClick()
        compose.onNodeWithText("隐私权政策").assertIsDisplayed()
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

    // ---------- 编辑、导出、外观 ----------

    @Test fun theEditRowSaysWhetherSwitchingRestylesAll() {
        show { SettingsScreen(RecognitionConfig(), onOpen = {}, onNavigateUp = {}, restyleAll = false) }
        compose.onNodeWithText("换样式时只换选中的").assertExists()
    }

    @Test fun theRestyleAllSwitchTurnsItOff() {
        var latest: Boolean? = null
        show { EditSettingsScreen(restyleAll = true, onRestyleAllChange = { latest = it }, onNavigateUp = {}) }
        compose.onNodeWithText("换样式时一起换掉已有的打码").performClick()
        assertThat(latest).isFalse()
    }

    /** 导出前提醒和「圈出」一起去掉了：导出页上只有水印样式。 */
    @Test fun theExportPageNoLongerHasAReminder() {
        show {
            ExportSettingsScreen(
                watermarkStyle = BrandWatermarkStyle.ICON_ZH_EN, onWatermarkStyleChange = {},
                watermarkUnlocked = false, watermarkOff = false, onWatermarkOff = {},
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("导出前提醒").assertDoesNotExist()
        compose.onNodeWithText("水印样式").assertIsDisplayed()
    }

    @Test fun pickingTheWatermarkWithoutChineseEmitsIt() {
        var latest: BrandWatermarkStyle? = null
        show {
            ExportSettingsScreen(
                watermarkStyle = BrandWatermarkStyle.ICON_ZH_EN, onWatermarkStyleChange = { latest = it },
                watermarkUnlocked = false, watermarkOff = false, onWatermarkOff = {},
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("图标 + 有码 Yomark").assertIsSelected()
        compose.onNodeWithText("图标 + Yomark").performClick()
        assertThat(latest).isEqualTo(BrandWatermarkStyle.ICON_EN)
    }

    @Test fun noWatermarkIsOfferedOnlyOnceUnlocked() {
        show {
            ExportSettingsScreen(
                watermarkStyle = BrandWatermarkStyle.ICON_ZH_EN, onWatermarkStyleChange = {},
                watermarkUnlocked = false, watermarkOff = true, onWatermarkOff = {},
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("无水印").assertDoesNotExist()
        // 没解锁时「无水印」不算数，照样选中一个字样
        compose.onNodeWithText("图标 + 有码 Yomark").assertIsSelected()
    }

    @Test fun unlockedAndOffSelectsNoWatermarkAndPickingAStyleEmitsIt() {
        var latest: BrandWatermarkStyle? = null
        show {
            ExportSettingsScreen(
                watermarkStyle = BrandWatermarkStyle.ICON_ZH_EN, onWatermarkStyleChange = { latest = it },
                watermarkUnlocked = true, watermarkOff = true, onWatermarkOff = {},
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("无水印").assertIsSelected()
        compose.onNodeWithText("图标 + 有码 Yomark").assertIsNotSelected()
        compose.onNodeWithText("图标 + 有码 Yomark").performClick()
        assertThat(latest).isEqualTo(BrandWatermarkStyle.ICON_ZH_EN)
    }

    @Test fun pickingNoWatermarkEmitsIt() {
        var off = false
        show {
            ExportSettingsScreen(
                watermarkStyle = BrandWatermarkStyle.ICON_EN, onWatermarkStyleChange = {},
                watermarkUnlocked = true, watermarkOff = false, onWatermarkOff = { off = true },
                onNavigateUp = {},
            )
        }
        compose.onNodeWithText("图标 + Yomark").assertIsSelected()
        compose.onNodeWithText("无水印").performClick()
        assertThat(off).isTrue()
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
