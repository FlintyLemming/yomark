package moe.flinty.yomark.billing

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

    @Test fun `a fresh purchase owes a thank-you`() = runTest {
        val store = store()
        store.grant(fresh = true)
        assertThat(store.isPro.first()).isTrue()
        assertThat(store.thanksPending.first()).isTrue()
    }

    @Test fun `restoring an old purchase is not thanked again`() = runTest {
        // 重装后恢复：那一笔早就确认过，悄悄点亮就好
        val store = store()
        store.grant(fresh = false)
        assertThat(store.isPro.first()).isTrue()
        assertThat(store.thanksPending.first()).isFalse()
    }

    @Test fun `one purchase is thanked once even when both screens report it`() = runTest {
        // 首页和编辑器各有一个 BillingClient，同一笔购买两边都可能收到
        val store = store()
        store.grant(fresh = true)
        store.clearThanks()
        store.grant(fresh = true)
        assertThat(store.thanksPending.first()).isFalse()
    }

    @Test fun `a refund drops a thank-you not yet shown`() = runTest {
        val store = store()
        store.grant(fresh = true)
        store.setPro(false)
        assertThat(store.thanksPending.first()).isFalse()
    }
}
