package moe.flinty.yomark.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import moe.flinty.yomark.debug.TraceBlock
import moe.flinty.yomark.debug.TraceRun
import moe.flinty.yomark.debug.TraceSection
import moe.flinty.yomark.debug.TraceStage
import moe.flinty.yomark.ui.components.DebugTraceContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 编辑页的调试信息面板：最新一次展开，大段原文收着，复制拿到的是整份纯文本。 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN-w412dp-h915dp-xxhdpi")
class DebugTraceSheetTest {

    @get:Rule val compose = createComposeRule()

    private val older = TraceRun(
        number = 1, title = "识别", startedAt = 0L, durationMs = 1_800,
        sections = listOf(TraceSection(TraceStage.TEXT, "文字识别 · ocr-old", "1.5 s · 1 行")),
    )

    private val newer = TraceRun(
        number = 2, title = "AI 增强识别", startedAt = 0L, durationMs = 2_100,
        sections = listOf(
            TraceSection(
                TraceStage.CLASSIFY, "Gemini Nano · 第 1/1 段", "2.1 s · 送了 2 行 · 解析出 1 处",
                lines = listOf("第 3 行（提示词里第 2 行）  人名  「张伟」"),
                blocks = listOf(TraceBlock("提示词", "1: 订单详情\n2: 让张伟明天来拿"), TraceBlock("模型的回答", "2|人名|张伟")),
            ),
        ),
    )

    private fun show(
        runs: List<TraceRun> = listOf(newer, older),
        onRerun: (() -> Unit)? = {},
        onCopy: (String) -> Unit = {},
    ) = compose.setContent {
        MaterialTheme { Surface { DebugTraceContent(runs, onRerun, onCopy = onCopy, onShare = {}) } }
    }

    @Test fun theNewestRunIsOpenAndOlderOnesAreFolded() {
        show()
        compose.onNodeWithText("AI 增强识别 #2").assertIsDisplayed()
        compose.onNodeWithText("第 3 行（提示词里第 2 行）  人名  「张伟」").assertIsDisplayed()
        compose.onNodeWithText("识别 #1").assertIsDisplayed()
        compose.onNode(hasText("文字识别 · ocr-old")).assertDoesNotExist()

        compose.onNodeWithText("识别 #1").performClick()
        compose.onNodeWithText("文字识别 · ocr-old").assertIsDisplayed()
    }

    /** 提示词动辄上千字：默认收起，标题上写着有多长，点开才看。 */
    @Test fun longTextIsFoldedUntilTapped() {
        show()
        compose.onNode(hasText("1: 订单详情", substring = true)).assertDoesNotExist()
        compose.onNodeWithText("提示词（18 字）").performClick()
        compose.onNodeWithText("1: 订单详情\n2: 让张伟明天来拿").assertIsDisplayed()
    }

    @Test fun copyingHandsOverEveryRunAsText() {
        var copied: String? = null
        show(onCopy = { copied = it })
        compose.onNodeWithContentDescription("复制全部").performClick()
        assertThat(copied).contains("== AI 增强识别 #2")
        assertThat(copied).contains("  --- 模型的回答 ---\n  2|人名|张伟")
        assertThat(copied).contains("== 识别 #1")
    }

    @Test fun anEmptyLogSaysHowToGetARecord() {
        show(runs = emptyList())
        compose.onNodeWithText("还没有记录", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("复制全部").assertIsNotEnabled()
    }

    @Test fun rerunIsDisabledWhileThereIsNothingToRerun() {
        show(onRerun = null)
        compose.onNodeWithText("重新识别").assertIsNotEnabled()
    }

    @Test fun rerunAsksForAnotherRun() {
        var reruns = 0
        show(onRerun = { reruns++ })
        compose.onNodeWithText("重新识别").performClick()
        assertThat(reruns).isEqualTo(1)
    }
}
