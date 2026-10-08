package moe.flinty.yomark.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveKindLabelsTest {

    @Test
    fun `every kind has a non-empty display name`() {
        SensitiveKind.entries.forEach { kind ->
            assertThat(SensitiveKindLabels.display(kind)).isNotEmpty()
        }
    }

    @Test
    fun `display names are distinct so the dialog never says the same thing twice`() {
        val names = SensitiveKind.entries.map { SensitiveKindLabels.display(it) }
        assertThat(names.toSet()).hasSize(names.size)
    }

    @Test
    fun `plural reads like the spec example`() {
        // spec §7.4：「2 个网址、1 个 IP 地址」
        assertThat(SensitiveKindLabels.plural(SensitiveKind.URL, 2)).isEqualTo("2 个网址")
        assertThat(SensitiveKindLabels.plural(SensitiveKind.IP_ADDR, 1)).isEqualTo("1 个 IP 地址")
    }
}
