package com.dama.app.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PurchaseStoreTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun store() = isolatedPurchaseStore(context)

    @Test fun `a fresh install is not pro`() = runTest {
        assertThat(store().isPro.first()).isFalse()
    }

    @Test fun `pro state survives a write and read`() = runTest {
        val store = store()
        store.setPro(true)
        assertThat(store.isPro.first()).isTrue()
    }

    @Test fun `pro state can be revoked`() = runTest {
        val store = store()
        store.setPro(true)
        store.setPro(false)
        assertThat(store.isPro.first()).isFalse()
    }
}
