package moe.flinty.yomark.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import moe.flinty.yomark.core.model.SensitiveKind
import moe.flinty.yomark.ui.components.PendingExportDialog
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class PendingExportDialogTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun shows_the_count_and_the_per_kind_breakdown() {
        compose.setContent {
            PendingExportDialog(
                pendingCount = 3,
                byKind = mapOf(SensitiveKind.URL to 2, SensitiveKind.IP_ADDR to 1),
                onMaskAll = {}, onExportAnyway = {}, onDismiss = {},
            )
        }
        compose.onNodeWithText("还有 3 处没打码").assertIsDisplayed()
        compose.onNodeWithText("2 个网址、1 个 IP 地址").assertIsDisplayed()
    }

    @Test
    fun mask_all_button_fires_its_callback() {
        var masked = false
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), { masked = true }, {}, {})
        }
        compose.onNodeWithText("全部打码").performClick()
        assertThat(masked).isTrue()
    }

    @Test
    fun export_anyway_button_fires_its_callback() {
        var exported = false
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), {}, { exported = true }, {})
        }
        compose.onNodeWithText("仍然导出").performClick()
        assertThat(exported).isTrue()
    }

    @Test
    fun buttons_report_whether_do_not_show_again_was_ticked() {
        var stop: Boolean? = null
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), {}, { stop = it }, {})
        }
        compose.onNodeWithText("不再提示").performClick()
        compose.onNodeWithText("仍然导出").performClick()
        assertThat(stop).isTrue()
    }

    @Test
    fun do_not_show_again_starts_unticked() {
        var stop: Boolean? = null
        compose.setContent {
            PendingExportDialog(1, mapOf(SensitiveKind.URL to 1), { stop = it }, {}, {})
        }
        compose.onNodeWithText("全部打码").performClick()
        assertThat(stop).isFalse()
    }
}
