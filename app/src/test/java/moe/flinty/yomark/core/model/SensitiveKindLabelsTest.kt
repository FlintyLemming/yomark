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
    fun `display names are distinct so the list never says the same thing twice`() {
        val names = SensitiveKind.entries.map { SensitiveKindLabels.display(res, it) }
        assertThat(names.toSet()).hasSize(names.size)
    }

}
