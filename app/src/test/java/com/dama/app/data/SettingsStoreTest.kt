package com.dama.app.data

import androidx.test.core.app.ApplicationProvider
import com.dama.app.core.model.MaskStyle
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
    fun `onboarding is unseen by default and sticks once marked`() = runTest {
        val store = store()
        assertThat(store.onboardingSeen.first()).isFalse()
        store.markOnboardingSeen()
        assertThat(store.onboardingSeen.first()).isTrue()
    }
}
