package com.youma.app.data

import androidx.test.core.app.ApplicationProvider
import com.youma.app.core.model.MaskStyle
import com.youma.app.export.PurposeWatermarkStyle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun store() = isolatedSettingsStore(context)

    @Test
    fun `default style is solid`() = runTest {
        assertThat(store().lastStyle.first()).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `style survives a write and read`() = runTest {
        val store = store()
        store.setLastStyle(MaskStyle.PIXELATE)
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.PIXELATE)
    }

    @Test
    fun `an unknown stored style falls back to solid rather than crashing`() = runTest {
        val store = store()
        store.writeRawStyleForTest("NOT_A_STYLE")
        assertThat(store.lastStyle.first()).isEqualTo(MaskStyle.SOLID)
    }

    @Test
    fun `purpose watermark style defaults to the original look`() = runTest {
        assertThat(store().purposeWatermarkStyle.first()).isEqualTo(PurposeWatermarkStyle())
    }

    @Test
    fun `purpose watermark style survives a write and read`() = runTest {
        val store = store()
        val style = PurposeWatermarkStyle(
            color = PurposeWatermarkStyle.PALETTE[3], angle = 15f, opacity = 0.4f, density = 1.6f,
        )
        store.setPurposeWatermarkStyle(style)
        assertThat(store.purposeWatermarkStyle.first()).isEqualTo(style)
    }

    @Test
    fun `an out of range stored opacity is pulled back so the watermark never turns opaque`() = runTest {
        val store = store()
        store.writeRawPurposeOpacityForTest(1f)
        assertThat(store.purposeWatermarkStyle.first().opacity)
            .isEqualTo(PurposeWatermarkStyle.OPACITY_RANGE.endInclusive)
    }

    @Test
    fun `onboarding is unseen by default and sticks once marked`() = runTest {
        val store = store()
        assertThat(store.onboardingSeen.first()).isFalse()
        store.markOnboardingSeen()
        assertThat(store.onboardingSeen.first()).isTrue()
    }
}
