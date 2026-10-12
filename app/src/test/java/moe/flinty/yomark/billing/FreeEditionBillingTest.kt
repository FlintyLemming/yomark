package moe.flinty.yomark.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 开源版（spec §10 修订）：不用买，点「去除水印」就解锁；也不去连 Play。 */
@RunWith(RobolectricTestRunner::class)
class FreeEditionBillingTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun `the free edition starts locked and unlocks without a purchase`() = runTest {
        val store = isolatedPurchaseStore(context)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = true)
        billing.start()
        testScheduler.advanceUntilIdle()
        assertThat(billing.isPro.value).isFalse()

        billing.unlockFree()
        // 写盘不在测试调度器上，等它落地再看；落不了地就一直等到 runTest 超时
        billing.isPro.first { it }
        // 没往购买态里写：以后换回内购版，不会凭开源版的状态白送一份已购
        assertThat(store.isPro.first()).isFalse()
    }

    @Test fun `the free edition ignores a purchase left in the cache`() = runTest {
        val store = isolatedPurchaseStore(context)
        store.grant(fresh = false)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = true)
        billing.start()
        testScheduler.advanceUntilIdle()
        assertThat(billing.isPro.value).isFalse()
    }

    @Test fun `the store edition cannot be unlocked for free`() = runTest {
        val store = isolatedPurchaseStore(context)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = false)
        billing.unlockFree()
        testScheduler.advanceUntilIdle()
        assertThat(store.freeUnlocked.first()).isFalse()
        assertThat(billing.isPro.value).isFalse()
    }

    @Test fun `the store edition still starts locked`() = runTest {
        val billing = BillingRepository(context, isolatedPurchaseStore(context), backgroundScope, freeEdition = false)
        assertThat(billing.isPro.value).isFalse()
    }
}
