package moe.flinty.yomark.core.model

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "zh-rCN")
class SensitiveKindLabelsTest {

    private val res = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `every kind has a non-empty display name`() {
        SensitiveKind.entries.forEach { kind ->
            assertThat(SensitiveKindLabels.display(res, kind)).isNotEmpty()
        }
    }

    @Test
    fun `display names are distinct so the dialog never says the same thing twice`() {
        val names = SensitiveKind.entries.map { SensitiveKindLabels.display(res, it) }
        assertThat(names.toSet()).hasSize(names.size)
    }

    @Test
    fun `plural reads like the spec example`() {
        // spec §7.4：「2 个网址、1 个 IP 地址」
        assertThat(SensitiveKindLabels.plural(res, SensitiveKind.URL, 2)).isEqualTo("2 个网址")
        assertThat(SensitiveKindLabels.plural(res, SensitiveKind.IP_ADDR, 1)).isEqualTo("1 个 IP 地址")
    }

    /** 英文里名字在前、数字在后，「IP address: 1」，不套中文的「个」。 */
    @Test
    @Config(qualifiers = "en-rUS")
    fun `english counts put the name first`() {
        val en = ApplicationProvider.getApplicationContext<Context>().resources
        assertThat(SensitiveKindLabels.plural(en, SensitiveKind.IP_ADDR, 1)).isEqualTo("IP address: 1")
    }
}
