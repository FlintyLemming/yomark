package moe.flinty.yomark.billing

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 开源版（spec §10 修订）：不用买就是已购，导出不带品牌水印；也不去连 Play。 */
@RunWith(RobolectricTestRunner::class)
class FreeEditionBillingTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun `the free edition is unlocked without a purchase`() = runTest {
        val store = isolatedPurchaseStore(context)
        val billing = BillingRepository(context, store, backgroundScope, freeEdition = true)
        billing.start()
        testScheduler.advanceUntilIdle()

        assertThat(billing.isPro.value).isTrue()
        // 没去读缓存、也没往缓存里写：以后换回内购版，不会凭开源版的状态白送一份已购
        assertThat(store.isPro.first()).isFalse()
    }

    @Test fun `the store edition still starts locked`() = runTest {
        val billing = BillingRepository(context, isolatedPurchaseStore(context), backgroundScope, freeEdition = false)
        assertThat(billing.isPro.value).isFalse()
    }
}
